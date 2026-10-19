package com.scanner.exception.complexity;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScanExitEvaluatorTest {

    @Test
    void failsOnHighParseFailureRatio() {
        AnalysisReport report = minimalReport(10, 3, RiskLevel.LOW);
        assertEquals(ScanExitEvaluator.EXIT_PARSE_FAILURE_RATIO,
                ScanExitEvaluator.evaluate(report, 0.2, null));
    }

    @Test
    void failsOnRiskGate() {
        AnalysisReport report = minimalReport(10, 0, RiskLevel.HIGH);
        assertEquals(ScanExitEvaluator.EXIT_RISK_GATE,
                ScanExitEvaluator.evaluate(report, null, RiskLevel.HIGH));
    }

    private static AnalysisReport minimalReport(int scanned, int failed, RiskLevel projectLevel) {
        Map<RiskLevel, Long> dist = new EnumMap<>(RiskLevel.class);
        for (RiskLevel level : RiskLevel.values()) {
            dist.put(level, 0L);
        }
        AnalysisReport.Summary summary = new AnalysisReport.Summary(
                scanned, scanned - failed, failed, 0, 0, 0, 0.0, 0, 0,
                0.5, projectLevel, dist, 0.0, 0.0, 0.5, 0.5, List.of());
        return new AnalysisReport("t", "now", "/", "JAVA_17",
                new AnalysisReport.RiskModel("v2", "f", Map.of(), Map.of()),
                summary, List.of(), List.of(), List.of(), null, null, null, null, null);
    }
}
