package com.example.exscan;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnionType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bir reponun Java kaynaklarını ayrıştırıp hedef exception kullanımlarını bulur. */
final class JavaSourceScanner {

    private static final Pattern TEST_PATH = Pattern.compile("(^|.*/)(src/test|test|tests)/.*");

    private final Config cfg;

    JavaSourceScanner(Config cfg) {
        this.cfg = cfg;
    }

    /** Alt sınıf tespiti için bir sınıfın sadece başlık bilgisi (ayrıştırılmış dosya saklanmaz). */
    private static final class ClassHeader {
        Path file;
        String fqn;
        int line;
        ImportContext ctx;
        final List<String> extendsWritten = new ArrayList<String>();
    }

    /**
     * Bellek kullanımını düşük tutmak için iki geçişte tarar ve hiçbir dosyanın ayrıştırılmış hâlini saklamaz:
     * 1. geçiş: "extends" içeren dosyalardan sadece sınıf başlıklarını toplayıp alt sınıfları bulur.
     * 2. geçiş: sadece aranan sınıf veya metot adlarını metin olarak içeren dosyaları ayrıştırıp kullanımları bulur.
     */
    RepoResult scan(RepoInfo repo) {
        RepoResult result = new RepoResult(repo);
        Path root = repo.localPath;

        List<Path> files;
        try {
            int[] kotlin = new int[1];
            files = collectJavaFiles(root, kotlin);
            result.kotlinFiles = kotlin[0];
        } catch (IOException e) {
            result.errors.add(new ScanError(repo, "DOSYA_LISTELEME", "", e.getMessage()));
            return result;
        }
        result.javaFiles = files.size();

        // JavaParser thread-safe değildir; her repo taramasında yeni örnekler kullanılır.
        // Önce Java 8 seviyesiyle (Java 6 kodu için en güvenlisi), olmazsa en yeni Java sürümüyle ayrıştırılır.
        JavaParser legacyParser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8)
                .setAttributeComments(false));
        JavaParser modernParser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setAttributeComments(false));

        Map<Path, String> moduleCache = new HashMap<Path, String>();
        long maxBytes = cfg.maxFileKb * 1024L;
        Set<Path> skipped = new HashSet<Path>();

        // ---- 1. geçiş: sınıf başlıkları
        List<ClassHeader> headers = new ArrayList<ClassHeader>();
        for (Path f : files) {
            String rel = relative(root, f);
            try {
                long size = Files.size(f);
                if (size > maxBytes) {
                    skipped.add(f);
                    result.errors.add(new ScanError(repo, "ATLANDI", rel, "Dosya çok büyük (" + (size / 1024)
                            + " KB). Sınırı scan.max.file.kb ayarıyla değiştirebilirsiniz."));
                    continue;
                }
                String source = readSource(f);
                if (!source.contains("extends")) continue;
                ParseResult<CompilationUnit> pr = parse(source, legacyParser, modernParser);
                if (!pr.getResult().isPresent()) continue;
                CompilationUnit cu = pr.getResult().get();
                ImportContext ctx = ImportContext.of(cu);
                for (ClassOrInterfaceDeclaration cd : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    if (cd.isInterface() || cd.getExtendedTypes().isEmpty()) continue;
                    ClassHeader h = new ClassHeader();
                    h.file = f;
                    h.fqn = fqnOf(cd, cu);
                    h.line = cd.getBegin().map(p -> p.line).orElse(0);
                    h.ctx = ctx;
                    for (ClassOrInterfaceType ext : cd.getExtendedTypes()) h.extendsWritten.add(ext.getNameWithScope());
                    headers.add(h);
                }
            } catch (IOException | RuntimeException e) {
                // okuma/ayrıştırma hataları 2. geçişte raporlanır
            }
        }
        Set<String> targets = resolveTargets(repo, root, headers, result, moduleCache);
        headers.clear();

        // Kullanım içerebilecek dosyaları ayrıştırmadan önce metin olarak ayıklamak için anahtar kelimeler
        Set<String> keywords = new LinkedHashSet<String>();
        for (String t : targets) keywords.add(TypeMatcher.simpleName(t));
        for (CallPattern cp : cfg.callPatterns) keywords.add(cp.method);
        List<String> configuredNames = new ArrayList<String>();
        for (String c : cfg.exceptionClasses) configuredNames.add(TypeMatcher.simpleName(c));

        // ---- 2. geçiş: kullanımlar
        for (Path f : files) {
            if (skipped.contains(f)) continue;
            String rel = relative(root, f);
            try {
                String source = readSource(f);
                if (containsAny(source, configuredNames)) result.filesMentioningTarget++;
                if (!containsAny(source, keywords)) continue;

                ParseResult<CompilationUnit> pr = parse(source, legacyParser, modernParser);
                if (!pr.isSuccessful()) {
                    result.parseFailures++;
                    String msg = pr.getProblems().isEmpty() ? "Ayrıştırılamadı" : pr.getProblem(0).getVerboseMessage();
                    result.errors.add(new ScanError(repo, "PARSE", rel, BitbucketClient.abbreviate(msg, 400)));
                }
                if (!pr.getResult().isPresent()) continue;
                String module = moduleOf(root, f.getParent(), moduleCache);
                scanUnit(pr.getResult().get(), repo, rel, module, TEST_PATH.matcher(rel).matches(), targets, result);
            } catch (IOException | RuntimeException e) {
                result.parseFailures++;
                result.errors.add(new ScanError(repo, "OKUMA", rel, String.valueOf(e.getMessage())));
            }
        }

        Collections.sort(result.usages, (a, b) -> a.file.equals(b.file)
                ? Integer.compare(a.line, b.line) : a.file.compareTo(b.file));
        return result;
    }

    private static ParseResult<CompilationUnit> parse(String source, JavaParser legacy, JavaParser modern) {
        ParseResult<CompilationUnit> pr = legacy.parse(source);
        if (!pr.isSuccessful()) {
            ParseResult<CompilationUnit> m = modern.parse(source);
            if (m.isSuccessful() || !pr.getResult().isPresent()) pr = m;
        }
        return pr;
    }

    private static boolean containsAny(String text, Iterable<String> words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    /** Tek bir dosyadaki exception oluşturma, metot çağrısı ve catch kullanımlarını bulur. */
    private void scanUnit(CompilationUnit cu, RepoInfo repo, String rel, String module, boolean test,
                          Set<String> targets, RepoResult result) {
        ImportContext ictx = ImportContext.of(cu);

        for (ObjectCreationExpr oce : cu.findAll(ObjectCreationExpr.class)) {
            String target = TypeMatcher.match(oce.getType().getNameWithScope(), ictx, targets, cfg.lenientMatch);
            if (target == null) continue;
            String ctx = context(oce);
            if (cfg.throwOnly && !"throw".equals(ctx)) continue;

            List<ArgumentClassifier.Result> args = ArgumentClassifier.analyzeAll(oce.getArguments());
            UsagePattern matched = null;
            if (!cfg.patterns.isEmpty()) {
                for (UsagePattern p : cfg.patterns) {
                    if (p.matches(args)) {
                        matched = p;
                        break;
                    }
                }
                if (matched == null) continue; // aranan desenlerden hiçbirine uymuyor
            }

            UsageFinding u = new UsageFinding();
            fill(u, repo, rel, module, test, oce, cu);
            u.targetClass = TypeMatcher.simpleName(target);
            u.pattern = matched == null ? UsagePattern.ALL : matched.name;
            u.typeCode = matched == null ? UsagePattern.ALL_TYPE : matched.type;
            u.action = matched == null ? "" : matched.action;
            u.context = ctx;
            u.expression = BitbucketClient.abbreviate(oce.toString().replaceAll("\\s+", " "), 500);
            fillArgs(u, args);
            result.usages.add(u);
        }

        if (!cfg.callPatterns.isEmpty()) scanCalls(cu, repo, rel, module, test, result);

        if (!cfg.reportCatches) return;
        for (CatchClause cc : cu.findAll(CatchClause.class)) {
            List<String> matched = new ArrayList<String>();
            for (ClassOrInterfaceType t : caughtTypes(cc.getParameter().getType())) {
                String m = TypeMatcher.match(t.getNameWithScope(), ictx, targets, cfg.lenientMatch);
                if (m != null) matched.add(TypeMatcher.simpleName(m));
            }
            if (matched.isEmpty()) continue;
            CatchFinding c = new CatchFinding();
            fill(c, repo, rel, module, test, cc, cu);
            c.caughtTypes = String.join(" | ", matched);
            c.variable = cc.getParameter().getNameAsString();
            c.emptyBody = cc.getBody().getStatements().isEmpty();
            c.rethrows = !cc.getBody().findAll(ThrowStmt.class).isEmpty();
            result.catches.add(c);
        }
    }

    /** Argümanlardaki ilk sayıyı id, ilk mesajı da mesaj olarak bulguya yazar. */
    private static void fillArgs(UsageFinding u, List<ArgumentClassifier.Result> args) {
        for (ArgumentClassifier.Result r : args) {
            if (u.idValue.isEmpty() && r.kind == ArgKind.SAYI) u.idValue = r.text;
            if (u.messageKind == null && r.kind.hasMessage) {
                u.messageKind = r.kind;
                u.message = r.text;
            }
        }
        if (u.messageKind == null) {
            for (ArgumentClassifier.Result r : args) {
                if (r.kind == ArgKind.EXCEPTION_MESAJI) {
                    u.messageKind = r.kind;
                    break;
                }
            }
        }
    }

    /** call.N desenlerine uyan metot çağrılarını bulur, ör. outBag.put(GENERALERRORCODE.ERROR_CODE, 0) */
    private void scanCalls(CompilationUnit cu, RepoInfo repo, String rel, String module, boolean test,
                           RepoResult result) {
        for (MethodCallExpr mc : cu.findAll(MethodCallExpr.class)) {
            String methodName = mc.getNameAsString();
            String scopeText = mc.getScope().map(Node::toString).orElse(null);
            CallPattern matched = null;
            List<ArgumentClassifier.Result> args = null;
            for (CallPattern cp : cfg.callPatterns) {
                if (!cp.method.equals(methodName) || !cp.scopeMatches(scopeText)) continue;
                if (args == null) args = ArgumentClassifier.analyzeAll(mc.getArguments());
                if (cp.args.matches(args)) {
                    matched = cp;
                    break;
                }
            }
            if (matched == null) continue;

            UsageFinding u = new UsageFinding();
            fill(u, repo, rel, module, test, mc, cu);
            u.targetClass = matched.display();
            u.pattern = matched.name;
            u.typeCode = matched.type();
            u.action = matched.action;
            u.context = "metot çağrısı";
            u.expression = BitbucketClient.abbreviate(mc.toString().replaceAll("\\s+", " "), 500);
            fillArgs(u, args);
            result.usages.add(u);
        }
    }

    /**
     * Ayarlardaki sınıflara, bu repoda onlardan türeyen alt sınıfları da ekler (alt sınıfın alt sınıfı dahil).
     * Yeni yapıya ait olup exception.ignore.classes'a yazılan sınıflar hariç tutulur.
     */
    private Set<String> resolveTargets(RepoInfo repo, Path root, List<ClassHeader> headers,
                                       RepoResult result, Map<Path, String> moduleCache) {
        Set<String> targets = new LinkedHashSet<String>(cfg.exceptionClasses);
        if (targets.isEmpty()) return targets;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ClassHeader h : headers) {
                if (targets.contains(h.fqn) || cfg.ignoreClasses.contains(h.fqn)) continue;
                for (String ext : h.extendsWritten) {
                    String parent = TypeMatcher.match(ext, h.ctx, targets, cfg.lenientMatch);
                    if (parent == null) continue;
                    targets.add(h.fqn);
                    changed = true;
                    String rel = relative(root, h.file);
                    SubclassFinding sf = new SubclassFinding();
                    sf.projectKey = repo.projectKey;
                    sf.projectName = repo.projectName;
                    sf.repo = repo.slug;
                    sf.module = moduleOf(root, h.file.getParent(), moduleCache);
                    sf.file = rel;
                    sf.testCode = TEST_PATH.matcher(rel).matches();
                    sf.className = h.fqn;
                    sf.member = "";
                    sf.line = h.line;
                    sf.link = repo.fileLink(rel, h.line);
                    sf.parent = parent;
                    result.subclasses.add(sf);
                    break;
                }
            }
        }
        return targets;
    }

    // ------------------------------------------------------------------ konum bilgileri

    private static void fill(Located l, RepoInfo repo, String rel, String module, boolean test,
                             Node node, CompilationUnit cu) {
        l.projectKey = repo.projectKey;
        l.projectName = repo.projectName;
        l.repo = repo.slug;
        l.module = module;
        l.file = rel;
        l.testCode = test;
        l.className = enclosingType(node, cu);
        l.packageName = TypeMatcher.packageOf(cu);
        l.member = enclosingMember(node);
        l.memberRef = namedMemberRef(node);
        l.line = node.getBegin().map(p -> p.line).orElse(0);
        l.link = repo.fileLink(rel, l.line);
    }

    private static String fqnOf(TypeDeclaration<?> td, CompilationUnit cu) {
        Deque<String> parts = new ArrayDeque<String>();
        Node cur = td;
        while (cur != null) {
            if (cur instanceof TypeDeclaration) parts.addFirst(((TypeDeclaration<?>) cur).getNameAsString());
            cur = cur.getParentNode().orElse(null);
        }
        String pkg = TypeMatcher.packageOf(cu);
        String name = String.join(".", parts);
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    /** Bulgunun içinde bulunduğu sınıf: paket.Dis.Ic veya paket.Dis.<anonim Runnable> */
    private static String enclosingType(Node node, CompilationUnit cu) {
        Deque<String> parts = new ArrayDeque<String>();
        if (node instanceof TypeDeclaration) parts.addFirst(((TypeDeclaration<?>) node).getNameAsString());
        Node child = node;
        Node cur = node.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof TypeDeclaration) {
                parts.addFirst(((TypeDeclaration<?>) cur).getNameAsString());
            } else if (cur instanceof ObjectCreationExpr && isInAnonymousBody((ObjectCreationExpr) cur, child)) {
                parts.addFirst("<anonim " + ((ObjectCreationExpr) cur).getType().getNameAsString() + ">");
            }
            child = cur;
            cur = cur.getParentNode().orElse(null);
        }
        String pkg = TypeMatcher.packageOf(cu);
        String name = String.join(".", parts);
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    private static boolean isInAnonymousBody(ObjectCreationExpr oce, Node child) {
        if (!oce.getAnonymousClassBody().isPresent()) return false;
        for (BodyDeclaration<?> b : oce.getAnonymousClassBody().get()) {
            if (b == child) return true;
        }
        return false;
    }

    /** Bulgunun içinde bulunduğu metot, constructor, blok veya alan. */
    private static String enclosingMember(Node node) {
        Node cur = node.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof MethodDeclaration) {
                return ((MethodDeclaration) cur).getSignature().asString();
            }
            if (cur instanceof ConstructorDeclaration) {
                return ((ConstructorDeclaration) cur).getSignature().asString() + " [constructor]";
            }
            if (cur instanceof InitializerDeclaration) {
                return ((InitializerDeclaration) cur).isStatic() ? "<static blok>" : "<instance blok>";
            }
            if (cur instanceof FieldDeclaration) {
                List<String> names = new ArrayList<String>();
                for (VariableDeclarator v : ((FieldDeclaration) cur).getVariables()) names.add(v.getNameAsString());
                return "<alan: " + String.join(", ", names) + ">";
            }
            if (cur instanceof TypeDeclaration) break;
            cur = cur.getParentNode().orElse(null);
        }
        return "";
    }

    /**
     * Veritabanı eşleştirmesi için üye bilgisi. Yukarı doğru ilk adlandırılmış tipe (TypeDeclaration)
     * kadar çıkılır ve yolda görülen en dıştaki üye alınır. Normal kodda bu, bulgunun doğrudan içinde
     * olduğu metottur; anonim sınıf içindeyse (anonim sınıflar TypeDeclaration değildir) onu içeren
     * dış metottur.
     */
    private static MemberRef namedMemberRef(Node node) {
        MemberRef found = MemberRef.NONE;
        Node cur = node.getParentNode().orElse(null);
        while (cur != null && !(cur instanceof TypeDeclaration)) {
            if (cur instanceof MethodDeclaration) {
                MethodDeclaration md = (MethodDeclaration) cur;
                found = new MemberRef(MemberRef.Kind.METHOD, md.getNameAsString(), paramTypes(md.getParameters()));
            } else if (cur instanceof ConstructorDeclaration) {
                ConstructorDeclaration cd = (ConstructorDeclaration) cur;
                found = new MemberRef(MemberRef.Kind.CONSTRUCTOR, cd.getNameAsString(), paramTypes(cd.getParameters()));
            } else if (cur instanceof InitializerDeclaration) {
                boolean st = ((InitializerDeclaration) cur).isStatic();
                found = new MemberRef(st ? MemberRef.Kind.STATIC_INIT : MemberRef.Kind.INSTANCE_INIT, "",
                        Collections.<String>emptyList());
            } else if (cur instanceof FieldDeclaration) {
                List<String> names = new ArrayList<String>();
                for (VariableDeclarator v : ((FieldDeclaration) cur).getVariables()) names.add(v.getNameAsString());
                found = new MemberRef(MemberRef.Kind.FIELD, String.join(",", names), Collections.<String>emptyList());
            }
            cur = cur.getParentNode().orElse(null);
        }
        return found;
    }

    private static List<String> paramTypes(NodeList<Parameter> params) {
        List<String> out = new ArrayList<String>();
        for (Parameter p : params) out.add(p.getType().asString() + (p.isVarArgs() ? "[]" : ""));
        return out;
    }

    /** Exception'ın oluşturulduğu bağlam: doğrudan throw mu, bir değişkene mi atanıyor vb. */
    private static String context(ObjectCreationExpr oce) {
        Node p = oce.getParentNode().orElse(null);
        while (p instanceof EnclosedExpr || p instanceof CastExpr) p = p.getParentNode().orElse(null);
        if (p instanceof ThrowStmt) return "throw";
        if (p instanceof ReturnStmt) return "return";
        if (p instanceof VariableDeclarator || p instanceof AssignExpr) return "değişkene atama";
        if (p instanceof MethodCallExpr || p instanceof ObjectCreationExpr) return "parametre olarak";
        return "diğer";
    }

    private static List<ClassOrInterfaceType> caughtTypes(Type t) {
        List<ClassOrInterfaceType> out = new ArrayList<ClassOrInterfaceType>();
        if (t instanceof UnionType) {
            for (ReferenceType rt : ((UnionType) t).getElements()) {
                if (rt.isClassOrInterfaceType()) out.add(rt.asClassOrInterfaceType());
            }
        } else if (t.isClassOrInterfaceType()) {
            out.add(t.asClassOrInterfaceType());
        }
        return out;
    }

    // ------------------------------------------------------------------ dosya işlemleri

    private List<Path> collectJavaFiles(final Path root, final int[] kotlinCount) throws IOException {
        final List<Path> out = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root)) {
                    String n = dir.getFileName().toString();
                    if (n.startsWith(".") || cfg.excludeDirs.contains(n)) return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String n = file.getFileName().toString();
                if (n.endsWith(".java")) out.add(file);
                else if (n.endsWith(".kt")) kotlinCount[0]++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(out);
        return out;
    }

    /** Önce UTF-8 dener; geçersiz bayt varsa eski projelerde yaygın olan yedek karakter setiyle okur. */
    private String readSource(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int offset = 0;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            offset = 3; // UTF-8 BOM
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, cfg.fallbackCharset);
        }
    }

    private static String moduleOf(Path root, Path dir, Map<Path, String> cache) {
        return ModuleResolver.moduleOf(root, dir, cache);
    }

    private static String relative(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }
}
