package com.scanner.exception.complexity;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanMetricsCalibrationTest {

    @Test
    void mockModulesPercentileTableContainsCoreMetrics() throws Exception {
        Path mock = Path.of("src/test/resources/complexity/mock-modules").toAbsolutePath().normalize();
        if (!mock.toFile().isDirectory()) {
            return;
        }
        ProjectAnalyzer analyzer = new ProjectAnalyzer(
                java.nio.charset.StandardCharsets.UTF_8, 5,
                com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
        AnalysisReport report = analyzer.analyze(mock);
        String md = ScanMetricsCalibration.renderMarkdown(report, mock);
        assertTrue(md.contains("outboundDistinctCalls"));
        assertTrue(md.contains("efferentCouplingProxy"));
        assertTrue(md.contains("publicMethodCount"));
        assertTrue(report.summary().methodCount() > 0);
    }
}
