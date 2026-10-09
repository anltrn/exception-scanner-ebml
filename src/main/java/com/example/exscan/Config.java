package com.example.exscan;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** scanner.properties dosyasını okur. Token gibi gizli değerler ortam değişkeninden de verilebilir. */
final class Config {

    enum Source { SERVER, CLOUD, GIT, LOCAL }

    final Source source;
    final String baseUrl;
    final String workspace;
    final String username;
    final String token;
    final String gitUsername;
    final Set<String> projects;
    final Set<String> excludeRepos;
    final boolean includeArchived;
    final String cloneProtocol;
    final String branch;
    final boolean sslInsecure;
    final int gitTimeoutMinutes;
    final Path workDir;
    final Path outputDir;
    final Path localDir;
    final List<String> gitUrls;
    final List<String> exceptionClasses;
    final Set<String> ignoreClasses;
    final boolean lenientMatch;
    final Set<String> excludeDirs;
    final Charset fallbackCharset;
    final int threads;
    final boolean perProjectReports;
    final List<UsagePattern> patterns;
    final List<CallPattern> callPatterns;
    final boolean throwOnly;
    final boolean reportCatches;
    final int consoleLimit;
    final int maxFileKb;
    final Db db;
    final Ebml ebml;

    /** Ekran (.ebml), region (.ebml), Jasper rapor (.dsxml) ve process envanteri ayarları (ebml.*) */
    static final class Ebml {

        enum ProjectNameSource { REPO, REPO_SLUG, MODULE, BITBUCKET_PROJECT }

        final boolean enabled;
        final String regionPrefix;
        final List<String> regionPackage;
        final List<String> pagePackage;
        final List<String> popupPackage;
        final List<String> reportPackage;
        final List<String> sourceRoots;
        final boolean fileNameWithExtension;
        final ProjectNameSource projectNameSource;
        final boolean projectCaseInsensitive;
        final boolean insertUnmatched;
        final String projectTable;
        final String projectIdColumn;
        final String projectNameColumn;
        final String screenTable;
        final String popupTable;
        final String regionTable;
        final String reportTable;
        final String processDir;
        final String processTable;
        // ---- Mevcut ekran / popup / rapor / process tablolarında project_id güncellemesi
        final boolean updateExisting;
        final boolean updateOnlyEmpty;
        final String existingProjectColumn;
        final String existingScreenTable;
        final String existingScreenNameColumn;
        final String existingScreenTypeColumn;
        final String existingScreenTypePage;
        final String existingScreenTypeRegion;
        final String existingPopupTable;
        final String existingPopupNameColumn;
        final String existingReportTable;
        final String existingReportNameColumn;
        final String existingProcessTable;
        final String existingProcessNoColumn;
        final String existingProcessNameColumn;

        Ebml(Properties p) {
            enabled = Boolean.parseBoolean(get(p, "ebml.enabled", "false"));
            regionPrefix = get(p, "ebml.region.prefix", "RG_");
            regionPackage = segments(get(p, "ebml.region.package", "ebml.region"));
            pagePackage = segments(get(p, "ebml.page.package", "ebml.page"));
            popupPackage = segments(get(p, "ebml.popup.package", "ebml.popup"));
            reportPackage = segments(get(p, "ebml.report.package", "ebml.report"));
            processDir = get(p, "ebml.process.dir", "process").trim();
            sourceRoots = list(get(p, "ebml.source.roots",
                    "src/main/java,src/main/resources,src/java,src/resources,JavaSource,source,resources,src"));
            fileNameWithExtension = Boolean.parseBoolean(get(p, "ebml.file.name.with.extension", "true"));
            projectNameSource = ProjectNameSource.valueOf(
                    get(p, "ebml.project.name.from", "REPO").toUpperCase(Locale.ROOT));
            projectCaseInsensitive = Boolean.parseBoolean(get(p, "ebml.project.case.insensitive", "true"));
            insertUnmatched = Boolean.parseBoolean(get(p, "ebml.insert.unmatched", "true"));
            String schema = get(p, "db.schema", "env");
            projectTable = Db.ident(get(p, "db.project.table", schema + ".project"), "db.project.table");
            projectIdColumn = Db.ident(get(p, "db.project.id.column", "id"), "db.project.id.column");
            projectNameColumn = Db.ident(get(p, "db.project.name.column", "project_name"), "db.project.name.column");
            screenTable = Db.ident(get(p, "db.screen.table", schema + ".all_screens"), "db.screen.table");
            popupTable = Db.ident(get(p, "db.popup.table", schema + ".all_popups"), "db.popup.table");
            regionTable = Db.ident(get(p, "db.region.table", schema + ".all_regions"), "db.region.table");
            reportTable = Db.ident(get(p, "db.report.table", schema + ".all_reports"), "db.report.table");
            processTable = Db.ident(get(p, "db.process.table", schema + ".all_processes"), "db.process.table");
            updateExisting = Boolean.parseBoolean(get(p, "ebml.update.existing", "true"));
            updateOnlyEmpty = Boolean.parseBoolean(get(p, "ebml.update.only.empty", "false"));
            existingProjectColumn = Db.ident(get(p, "db.existing.project.column", "project_id"), "db.existing.project.column");
            existingScreenTable = Db.ident(get(p, "db.existing.screen.table", schema + ".screen"), "db.existing.screen.table");
            existingScreenNameColumn = Db.ident(get(p, "db.existing.screen.name.column", "name"), "db.existing.screen.name.column");
            existingScreenTypeColumn = Db.ident(get(p, "db.existing.screen.type.column", "page_type"), "db.existing.screen.type.column");
            existingScreenTypePage = get(p, "db.existing.screen.type.page", "page").trim();
            existingScreenTypeRegion = get(p, "db.existing.screen.type.region", "region").trim();
            existingPopupTable = Db.ident(get(p, "db.existing.popup.table", schema + ".popup"), "db.existing.popup.table");
            existingPopupNameColumn = Db.ident(get(p, "db.existing.popup.name.column", "popup_name"), "db.existing.popup.name.column");
            existingReportTable = Db.ident(get(p, "db.existing.report.table", schema + ".report"), "db.existing.report.table");
            existingReportNameColumn = Db.ident(get(p, "db.existing.report.name.column", "report_name"), "db.existing.report.name.column");
            existingProcessTable = Db.ident(get(p, "db.existing.process.table", schema + ".process"), "db.existing.process.table");
            existingProcessNoColumn = Db.ident(get(p, "db.existing.process.no.column", "no"), "db.existing.process.no.column");
            existingProcessNameColumn = Db.ident(get(p, "db.existing.process.name.column", "name"), "db.existing.process.name.column");
        }

        /** "ebml.region" -> [ebml, region] (küçük harf) */
        private static List<String> segments(String pkg) {
            List<String> out = new ArrayList<String>();
            for (String s : pkg.split("[./]")) {
                if (!s.trim().isEmpty()) out.add(s.trim().toLowerCase(Locale.ROOT));
            }
            return Collections.unmodifiableList(out);
        }
    }

    /** PostgreSQL envanter eşleştirmesi ve kayıt ayarları (db.*) */
    static final class Db {
        final boolean enabled;
        final boolean dryRun;
        final String url;
        final String user;
        final String password;
        final String classTable;
        final String classIdColumn;
        final String classFqcnColumn;
        final String classFilter;
        final String methodTable;
        final String methodIdColumn;
        final String methodClassColumn;
        final String methodNameColumn;
        final String methodSignatureColumn;
        final String usageTable;
        final String typeTable;
        final String runTable;
        final boolean caseInsensitive;
        final List<String> constructorNames;
        final boolean insertUnmatched;
        final String note;

        Db(Properties p) {
            enabled = Boolean.parseBoolean(get(p, "db.enabled", "false"));
            dryRun = Boolean.parseBoolean(get(p, "db.dry.run", "false"));
            url = get(p, "db.url", "");
            user = firstNonEmpty(System.getenv("DB_USER"), get(p, "db.user", ""));
            password = firstNonEmpty(System.getenv("DB_PASSWORD"), get(p, "db.password", ""));
            String schema = get(p, "db.schema", "env");
            classTable = ident(get(p, "db.class.table", schema + ".java_class"), "db.class.table");
            classIdColumn = ident(get(p, "db.class.id.column", "id"), "db.class.id.column");
            classFqcnColumn = ident(get(p, "db.class.fqcn.column", "fqcn"), "db.class.fqcn.column");
            classFilter = get(p, "db.class.filter", "");
            methodTable = ident(get(p, "db.method.table", schema + ".java_method"), "db.method.table");
            methodIdColumn = ident(get(p, "db.method.id.column", "id"), "db.method.id.column");
            methodClassColumn = ident(get(p, "db.method.class.column", "class_id"), "db.method.class.column");
            methodNameColumn = ident(get(p, "db.method.name.column", "name"), "db.method.name.column");
            String sig = get(p, "db.method.signature.column", "");
            methodSignatureColumn = sig.isEmpty() ? null : ident(sig, "db.method.signature.column");
            usageTable = ident(get(p, "db.usage.table", schema + ".exception_usage"), "db.usage.table");
            typeTable = ident(get(p, "db.usage.type.table", schema + ".exception_usage_type"), "db.usage.type.table");
            runTable = ident(get(p, "db.run.table", schema + ".exception_scan_run"), "db.run.table");
            caseInsensitive = Boolean.parseBoolean(get(p, "db.fqcn.case.insensitive", "false"));
            constructorNames = list(get(p, "db.method.constructor.names", "<init>,{class}"));
            insertUnmatched = Boolean.parseBoolean(get(p, "db.insert.unmatched", "true"));
            note = get(p, "db.note", "");
            if (enabled && url.isEmpty()) {
                throw new IllegalArgumentException("db.enabled=true için db.url gerekli. "
                        + "Örnek: db.url=jdbc:postgresql://localhost:5432/envanter");
            }
        }

        /** Tablo ve sütun adları SQL'e yazıldığı için sadece harf, rakam, _ ve şema noktasına izin verilir */
        static String ident(String value, String key) {
            if (!value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")) {
                throw new IllegalArgumentException(key + " geçersiz: " + value + " (örnek: env.java_class)");
            }
            return value;
        }
    }

    private Config(Properties p) {
        // Locale.ROOT: Türkçe locale'de i/İ dönüşümü büyük-küçük harf çevirmeyi bozmasın diye
        source = Source.valueOf(get(p, "source", "server").toUpperCase(Locale.ROOT));
        baseUrl = stripTrailingSlash(get(p, "bitbucket.url",
                source == Source.CLOUD ? "https://api.bitbucket.org" : ""));
        workspace = get(p, "bitbucket.workspace", "");
        username = firstNonEmpty(System.getenv("BITBUCKET_USERNAME"), get(p, "bitbucket.username", ""));
        token = firstNonEmpty(System.getenv("BITBUCKET_TOKEN"), get(p, "bitbucket.token", ""));
        gitUsername = get(p, "git.username", "");
        projects = new LinkedHashSet<String>(list(get(p, "bitbucket.projects", "")));
        excludeRepos = new LinkedHashSet<String>(list(get(p, "bitbucket.exclude.repos", "")));
        includeArchived = Boolean.parseBoolean(get(p, "bitbucket.include.archived", "false"));
        cloneProtocol = get(p, "git.protocol", "http").toLowerCase(Locale.ROOT);
        branch = get(p, "git.branch", "");
        sslInsecure = Boolean.parseBoolean(get(p, "ssl.insecure", "false"));
        gitTimeoutMinutes = Integer.parseInt(get(p, "git.timeout.minutes", "15"));
        workDir = Paths.get(get(p, "work.dir", "./scannedRepos"));
        outputDir = Paths.get(get(p, "output.dir", "./executeReports"));
        String local = get(p, "local.dir", "");
        localDir = local.isEmpty() ? null : Paths.get(local);
        gitUrls = list(get(p, "git.urls", ""));
        exceptionClasses = list(get(p, "exception.classes", ""));
        ignoreClasses = new LinkedHashSet<String>(list(get(p, "exception.ignore.classes", "")));
        lenientMatch = Boolean.parseBoolean(get(p, "match.lenient", "false"));
        excludeDirs = new LinkedHashSet<String>(list(get(p, "scan.exclude.dirs",
                "target,build,bin,out,classes,node_modules,generated,generated-sources")));
        fallbackCharset = Charset.forName(get(p, "scan.fallback.charset", "windows-1254"));
        threads = Math.max(1, Integer.parseInt(get(p, "threads", "4")));
        perProjectReports = Boolean.parseBoolean(get(p, "report.per.project", "true"));
        patterns = UsagePattern.load(p);
        callPatterns = CallPattern.load(p);
        throwOnly = Boolean.parseBoolean(get(p, "scan.throw.only", "false"));
        reportCatches = Boolean.parseBoolean(get(p, "report.catch.blocks", "false"));
        consoleLimit = Integer.parseInt(get(p, "console.limit", "200"));
        maxFileKb = Integer.parseInt(get(p, "scan.max.file.kb", "2048"));
        db = new Db(p);
        ebml = new Ebml(p);
        validate();
    }

    /**
     * Ayar dosyasını okur ve komut satırından gelen değerleri üzerine yazar.
     * Komut satırında desen verilmişse dosyadaki desenler yok sayılır.
     * Dosya yoksa ve komut satırı değerleri varsa varsayılanlarla devam edilir.
     */
    static Config load(Path file, Properties overrides) throws IOException {
        Properties p = new Properties();
        if (file != null && Files.exists(file)) {
            try (Reader r = new StringReader(readText(file))) {
                p.load(r);
            } catch (IllegalArgumentException e) {
                throw new IOException("Ayar dosyası okunamadı (" + file.toAbsolutePath() + "): " + e.getMessage(), e);
            }
        } else if (overrides.isEmpty()) {
            throw new IOException("Ayar dosyası bulunamadı: "
                    + (file == null ? "scanner.properties" : file.toAbsolutePath().toString())
                    + ". Parametreler için: java -jar exception-scanner.jar --help");
        }
        // Komut satırında desen verilmişse dosyadaki aynı türden desenler yok sayılır
        for (String prefix : new String[]{"pattern.", "call."}) {
            boolean fromCli = false;
            for (String k : overrides.stringPropertyNames()) if (k.startsWith(prefix)) fromCli = true;
            if (!fromCli) continue;
            for (String k : new ArrayList<String>(p.stringPropertyNames())) {
                if (k.startsWith(prefix)) p.remove(k);
            }
        }
        p.putAll(overrides);
        return new Config(p);
    }

    /**
     * Ayar dosyasını önce UTF-8 olarak okur; geçersiz karakter varsa (ör. TextEdit ile farklı
     * kodlamada kaydedilmişse) Türkçe karakter setiyle okur.
     */
    private static String readText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int offset = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1254"));
        }
    }

    private void validate() {
        if (exceptionClasses.isEmpty() && callPatterns.isEmpty() && !ebml.enabled) {
            throw new IllegalArgumentException("Aranacak bir şey tanımlanmadı. Ayar dosyasında exception.classes "
                    + "boş ve komut satırında --class veya --call verilmedi. Ayar dosyasının doğru klasörde olduğunu "
                    + "ve exception.classes satırının dolu olduğunu kontrol edin. "
                    + "Örnek: --class com.firma.framework.CustomException (sadece ekran/rapor envanteri için --ebml)");
        }
        for (String c : exceptionClasses) {
            if (!c.contains(".")) {
                throw new IllegalArgumentException(
                        "exception.classes paket adıyla birlikte (tam nitelikli) yazılmalı: " + c);
            }
        }
        switch (source) {
            case SERVER:
                if (baseUrl.isEmpty()) throw new IllegalArgumentException("source=server için bitbucket.url gerekli");
                break;
            case CLOUD:
                if (workspace.isEmpty()) throw new IllegalArgumentException("source=cloud için bitbucket.workspace gerekli");
                break;
            case GIT:
                if (gitUrls.isEmpty()) throw new IllegalArgumentException("source=git için git.urls gerekli");
                break;
            case LOCAL:
                if (localDir == null) throw new IllegalArgumentException("source=local için local.dir gerekli");
                break;
            default:
                break;
        }
    }

    private static String get(Properties p, String key, String def) {
        String v = p.getProperty(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private static List<String> list(String value) {
        List<String> out = new ArrayList<String>();
        for (String s : value.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return Collections.unmodifiableList(out);
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a.trim() : b;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
