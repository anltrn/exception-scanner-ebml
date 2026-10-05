package com.example.exscan;

import com.github.javaparser.ast.CompilationUnit;

import java.util.Set;

/**
 * Koddaki bir tip adının hedef exception sınıflarından birine karşılık gelip gelmediğini
 * import'lara ve paket adına bakarak belirler. Derleme classpath'i gerektirmez.
 */
final class TypeMatcher {

    private TypeMatcher() { }

    /**
     * @param written kodda yazıldığı hâliyle tip adı: "CustomException", "a.b.CustomException" veya "Outer.Inner"
     * @return eşleşen hedefin tam adı, eşleşme yoksa null
     */
    static String match(String written, ImportContext ctx, Set<String> targets, boolean lenient) {
        for (String fqn : targets) {
            if (written.equals(fqn)) return fqn;
            if (!fqn.endsWith("." + written)) continue;
            if (lenient) return fqn;
            String first = written.contains(".") ? written.substring(0, written.indexOf('.')) : written;
            String firstFqn = fqn.substring(0, fqn.length() - written.length()) + first;
            if (resolvesTo(ctx, first, firstFqn)) return fqn;
        }
        return null;
    }

    private static boolean resolvesTo(ImportContext ctx, String simple, String fqn) {
        String pkg = packageOf(fqn);
        // Aynı dosyada aynı isimde bir tip tanımlıysa o tip kullanılır
        if (ctx.declaredTypes.contains(simple)) {
            return (ctx.pkg.isEmpty() ? simple : ctx.pkg + "." + simple).equals(fqn);
        }
        if (ctx.singleImports.contains(fqn)) return true;
        for (String imp : ctx.singleImports) {
            if (simpleName(imp).equals(simple)) return false; // aynı isimli başka bir sınıf import edilmiş
        }
        // java.lang paketindeki sınıflar (RuntimeException, Exception ...) import edilmeden kullanılabilir
        return ctx.wildcardPackages.contains(pkg) || ctx.pkg.equals(pkg) || "java.lang".equals(pkg);
    }

    static String packageOf(CompilationUnit cu) {
        return cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
    }

    static String packageOf(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? "" : fqn.substring(0, i);
    }

    static String simpleName(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }
}
