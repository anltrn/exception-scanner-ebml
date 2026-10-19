package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalCacheTest {

    @TempDir
    Path temp;

    @Test
    void secondRunSkipsUnchangedFiles() throws Exception {
        Path root = Path.of("src/test/resources/complexity/mock-modules").toAbsolutePath().normalize();
        Path state = temp.resolve("analyzer-state.json");
        ScanOptions options = new ScanOptions(1, 500, java.util.List.of(), java.util.List.of(),
                ScanOptions.ReportDetail.FULL, true, state, false, true, 50, true, 0, false);
        ProjectAnalyzer analyzer = new ProjectAnalyzer(StandardCharsets.UTF_8, 5,
                ParserConfiguration.LanguageLevel.JAVA_17, options, new RiskCalculator());

        AnalysisReport first = analyzer.analyze(root);
        assertTrue(first.summary().filesParsed() > 0);

        AnalysisReport second = analyzer.analyze(root);
        assertTrue(second.cacheStatistics() != null);
        assertTrue(second.cacheStatistics().filesSkippedViaFileHash() > 0
                        || second.cacheStatistics().filesSkippedViaModuleBulk() > 0);
    }
}
