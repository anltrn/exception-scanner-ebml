package com.scanner.exception.complexity;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eski (enterprise-java-pre202603) vs yeni (enterprise-java) skor farkı — services markdown örnekleri.
 */
class RiskScoreComparisonTest {

    private static final Path CONFIG = Path.of("src/main/resources/config/risk-parameters-proposal.yaml");
    private static final Path SERVICES_MD = Path.of("analysis-output/parser-services-20261001-142939.md");

    @Test
    void loadsDimensionGroupsOnCalibratedProfile() throws Exception {
        RiskProfile profile = RiskProfileLoader.load("enterprise-java", CONFIG);
        assertFalse(profile.methodDimensionGroups().isEmpty());
        assertTrue(profile.methodDimensionGroups().stream()
                .anyMatch(g -> "structure".equals(g.id())));
    }

    @Test
    void servicesHotspotsScoreShiftWithinReasonableBand() throws Exception {
        if (!SERVICES_MD.toFile().exists()) {
            return;
        }
        RiskCalculator baseline = new RiskCalculator(RiskProfileLoader.load("enterprise-java-pre202603", CONFIG));
        RiskCalculator calibrated = new RiskCalculator(RiskProfileLoader.load("enterprise-java", CONFIG));
        List<ServicesMarkdownMetricsParser.ParsedMethod> rows = ServicesMarkdownMetricsParser.parse(SERVICES_MD);
        assertFalse(rows.isEmpty());

        String[] samples = {
                "RiskServices.transferRiskToTOA",
                "CrdCreditCollectionService.stokServiceIGE",
                "CrdCreditUsageService.creditUsage",
                "CrdCreditInstallmentCollectionService.getInstallmentDetail",
                "CreditInstallmentService.doBalloonPayment"
        };

        StringBuilder report = new StringBuilder();
        report.append("| Metod | Rapor (eski) | pre202603 | enterprise-java | Δ |\n");
        report.append("|-------|-------------:|----------:|----------------:|--:|\n");

        for (String needle : samples) {
            ServicesMarkdownMetricsParser.ParsedMethod row = rows.stream()
                    .filter(r -> r.label().contains(needle))
                    .findFirst()
                    .orElseThrow();
            double reported = parseReportedRisk(row.label(), rows, SERVICES_MD);
            double oldScore = baseline.assessMethod(row.scan()).score();
            double newScore = calibrated.assessMethod(row.scan()).score();
            double delta = newScore - oldScore;
            report.append(String.format("| `%s` | %.3f | %.3f | %.3f | %+.3f |%n",
                    shortLabel(row.label()), reported, oldScore, newScore, delta));
            assertTrue(Math.abs(delta) < 35.0,
                    needle + " delta too large: " + delta + " old=" + oldScore + " new=" + newScore);
        }

        Path out = Path.of("analysis-output/services-score-comparison.md");
        out.toFile().getParentFile().mkdirs();
        java.nio.file.Files.writeString(out, report.toString());
    }

    private static String shortLabel(String label) {
        return label.replace('`', ' ').trim();
    }

    /** İlk sütundaki rapor skoru — tablo satırından (markdown Risk sütunu). */
    private static double parseReportedRisk(String label, List<ServicesMarkdownMetricsParser.ParsedMethod> rows,
                                            Path md) throws Exception {
        String text = java.nio.file.Files.readString(md);
        int idx = text.indexOf(label);
        if (idx < 0) {
            return Double.NaN;
        }
        int lineStart = text.lastIndexOf('\n', idx);
        String line = text.substring(lineStart + 1, text.indexOf('\n', idx));
        String[] parts = line.split("\\|");
        if (parts.length < 2) {
            return Double.NaN;
        }
        return Double.parseDouble(parts[1].trim());
    }
}
