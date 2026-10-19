package com.scanner.complexity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Nearest-rank percentiles for calibration tables (mock-modules, profiles). */
public final class PercentileStats {

    public record Distribution(String metricId, int count, int p50, int p90, int p95, int max) {
    }

    private PercentileStats() {
    }

    public static Distribution fromValues(String metricId, List<Integer> values) {
        if (values.isEmpty()) {
            return new Distribution(metricId, 0, 0, 0, 0, 0);
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return new Distribution(
                metricId,
                sorted.size(),
                quantile(sorted, 0.50),
                quantile(sorted, 0.90),
                quantile(sorted, 0.95),
                sorted.get(sorted.size() - 1));
    }

    public static <T> Distribution fromRecords(String metricId, List<T> rows, ToIntFunction<T> extractor) {
        return fromValues(metricId, rows.stream().mapToInt(extractor).boxed().toList());
    }

    static int quantile(List<Integer> sorted, double p) {
        int n = sorted.size();
        int index = (int) Math.ceil(p * n) - 1;
        index = Math.max(0, Math.min(n - 1, index));
        return sorted.get(index);
    }

    static String markdownTable(List<Distribution> rows) {
        StringBuilder md = new StringBuilder();
        md.append("| Metrik | n | p50 | p90 | p95 | max |\n");
        md.append("|--------|---|-----|-----|-----|-----|\n");
        for (Distribution row : rows) {
            md.append(String.format(Locale.US, "| %s | %d | %d | %d | %d | %d |\n",
                    row.metricId(), row.count(), row.p50(), row.p90(), row.p95(), row.max()));
        }
        return md.toString();
    }

    /** dikkat=p50, yuksek=p90, kritik=p95 önerisi (enterprise-java YAML güncellemesi için). */
    static String yamlThresholdBlock(List<Distribution> rows, Map<String, String> metricIdToYamlKey) {
        StringBuilder md = new StringBuilder();
        md.append("## Önerilen YAML eşikleri (dikkat=p50, yüksek=p90, kritik=p95)\n\n");
        md.append("```yaml\n");
        for (Distribution row : rows) {
            String yamlKey = metricIdToYamlKey.get(row.metricId());
            if (yamlKey == null) {
                continue;
            }
            md.append(String.format(Locale.US, "      %s:%n", yamlKey));
            md.append(String.format(Locale.US, "        thresholds: { dikkat: %d, yuksek: %d, kritik: %d }%n",
                    row.p50(), row.p90(), row.p95()));
        }
        md.append("```\n");
        return md.toString();
    }

    static Map<String, String> enterpriseJavaMetricKeys() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("cyclomaticComplexity", "branching");
        map.put("codeLines", "length");
        map.put("maxNestingDepth", "nesting");
        map.put("parameterCount", "parameters");
        map.put("cognitiveComplexity", "cognitive");
        map.put("outboundDistinctCalls", "outboundDistinctCalls");
        map.put("exitPoints", "exitPoints");
        map.put("logicalStatements", "logicalStatements");
        map.put("lambdaCount", "lambdaCount");
        map.put("switchCases", "switchCases");
        map.put("maxTryNestingDepth", "maxTryNestingDepth");
        map.put("localVariableCount", "localVariableCount");
        map.put("maxMethodCallChainLength", "maxMethodCallChainLength");
        map.put("catchClauses", "catchClauses");
        map.put("emptyCatchBlocks", "emptyCatchBlocks");
        map.put("catchExceptionOrThrowable", "catchExceptionOrThrowable");
        map.put("catchWithOnlyPrintStackTrace", "catchWithOnlyPrintStackTrace");
        map.put("primitiveObsessionIndex", "primitiveObsessionIndex");
        map.put("maxBooleanOperatorsInCondition", "maxBooleanOperatorsInCondition");
        return map;
    }
}
