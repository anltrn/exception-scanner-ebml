package com.scanner.complexity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModuleRootIndexTest {

    @TempDir
    Path temp;

    @Test
    void resolvesNearestPomModule() throws Exception {
        Path scanRoot = temp.resolve("repo");
        Path module = scanRoot.resolve("module-payments");
        Path javaFile = module.resolve("src/main/java/com/app/Service.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Files.writeString(javaFile, "package com.app; class Service {}");

        ModuleRootIndex index = ModuleRootIndex.forScanRoot(scanRoot);
        assertEquals("module-payments", index.moduleRoot("module-payments/src/main/java/com/app/Service.java"));
    }
}
