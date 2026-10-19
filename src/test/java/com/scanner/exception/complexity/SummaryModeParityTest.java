package com.scanner.exception.complexity;

import com.github.javaparser.ParserConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SummaryModeParityTest {

    @Test
    void summaryMatchesFullAggregatesOnMockModules() throws Exception {
        Path root = Path.of("src/test/resources/complexity/mock-modules").toAbsolutePath().normalize();
        ParserConfiguration.LanguageLevel level = ParserConfiguration.LanguageLevel.JAVA_17;

        ProjectAnalyzer fullAnalyzer = new ProjectAnalyzer(StandardCharsets.UTF_8, 20, level,
                new ScanOptions(1, 500, java.util.List.of(), java.util.List.of(), ScanOptions.ReportDetail.FULL, true,
                        null, false, true, 50, true, 0, false));
        ProjectAnalyzer summaryAnalyzer = new ProjectAnalyzer(StandardCharsets.UTF_8, 20, level,
                new ScanOptions(1, 500, java.util.List.of(), java.util.List.of(), ScanOptions.ReportDetail.SUMMARY, true,
                        null, false, true, 50, true, 0, false));

        AnalysisReport full = fullAnalyzer.analyze(root);
        AnalysisReport summary = summaryAnalyzer.analyze(root);

        assertEquals(full.summary().methodCount(), summary.summary().methodCount());
        assertEquals(full.summary().classCount(), summary.summary().classCount());
        assertEquals(full.summary().totalCodeLines(), summary.summary().totalCodeLines());
        assertEquals(full.summary().maxCyclomaticComplexity(), summary.summary().maxCyclomaticComplexity());
        assertEquals(full.summary().projectRiskScore(), summary.summary().projectRiskScore(), 0.001);
        assertEquals(full.summary().methodRiskDistribution(), summary.summary().methodRiskDistribution());
        assertEquals(full.summary().methodRiskScoreP95(), summary.summary().methodRiskScoreP95(), 0.001);
        assertEquals(full.summary().highPlusCriticalLocRatio(), summary.summary().highPlusCriticalLocRatio(), 0.001);
    }
}
