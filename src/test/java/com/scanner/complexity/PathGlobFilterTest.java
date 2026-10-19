package com.scanner.complexity;

import org.junit.jupiter.api.Test;

import java.nio.file.FileSystems;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathGlobFilterTest {

    private static final PathGlobFilter FILTER = new PathGlobFilter(
            FileSystems.getDefault(),
            java.util.List.of("**/src/main/java/**"),
            java.util.List.of("**/generated/**"));

    @Test
    void includeMatchesSingleModuleLayoutAtRepoRoot() {
        Path root = Path.of("/scan");
        assertTrue(FILTER.accept(root, root.resolve("src/main/java/com/app/A.java")));
    }

    @Test
    void includeMatchesNestedModuleLayout() {
        Path root = Path.of("/scan");
        assertTrue(FILTER.accept(root, root.resolve("services/billing/src/main/java/com/app/B.java")));
    }

    @Test
    void excludeGeneratedUnderMainJava() {
        Path root = Path.of("/scan");
        assertFalse(FILTER.accept(root, root.resolve("src/main/java/generated/X.java")));
    }

    @Test
    void pathMatcherHelperRootAnchored() {
        var fs = FileSystems.getDefault();
        var matcher = fs.getPathMatcher("glob:**/src/main/java/**");
        assertTrue(PathGlobFilter.matches(matcher, "src/main/java/com/A.java"));
    }
}
