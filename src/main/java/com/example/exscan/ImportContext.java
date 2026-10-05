package com.example.exscan;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.HashSet;
import java.util.Set;

/**
 * Bir dosyadaki tip adlarını çözmek için gereken bilgiler: paket, import'lar ve dosyada tanımlı tipler.
 * Ayrıştırılmış dosyanın tamamı yerine bu küçük özet saklanır; böylece bellek kullanımı düşük kalır.
 */
final class ImportContext {

    final String pkg;
    final Set<String> singleImports = new HashSet<String>();
    final Set<String> wildcardPackages = new HashSet<String>();
    final Set<String> declaredTypes = new HashSet<String>();

    private ImportContext(String pkg) {
        this.pkg = pkg;
    }

    static ImportContext of(CompilationUnit cu) {
        ImportContext ctx = new ImportContext(TypeMatcher.packageOf(cu));
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isStatic()) continue;
            if (imp.isAsterisk()) ctx.wildcardPackages.add(imp.getNameAsString());
            else ctx.singleImports.add(imp.getNameAsString());
        }
        for (TypeDeclaration<?> td : cu.getTypes()) ctx.declaredTypes.add(td.getNameAsString());
        return ctx;
    }
}
