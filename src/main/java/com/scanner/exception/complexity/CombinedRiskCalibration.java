package com.scanner.exception.complexity;

import com.scanner.exception.complexity.AnalysisReport.ClassMetric;
import com.scanner.exception.complexity.AnalysisReport.FileMetric;
import com.scanner.exception.complexity.PercentileStats.Distribution;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * mock-modules + services markdown tablosundan enterprise-java eşik önerisi (p75/p90/p95).
 */
public final class CombinedRiskCalibration {

    private CombinedRiskCalibration() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: CombinedRiskCalibration <mock-modules-root> [services-report.md ...]");
            System.exit(1);
        }
        List<MethodScanValues> scans = new ArrayList<>();
        AnalysisReport mockReport = ScanMetricsCalibration.analyzeRoot(Path.of(args[0]));
        for (FileMetric file : mockReport.files()) {
            for (ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    scans.add(MethodScanValues.fromMetric(method));
                }
            }
        }
        for (int i = 1; i < args.length; i++) {
            for (ServicesMarkdownMetricsParser.ParsedMethod row : ServicesMarkdownMetricsParser.parse(Path.of(args[i]))) {
                scans.add(row.scan());
            }
        }
        System.out.print(renderYamlSuggestions(scans));
    }

    static String renderYamlSuggestions(List<MethodScanValues> scans) {
        Map<String, ToIntFunction<MethodScanValues>> extractors = calibrationExtractors();
        List<Distribution> rows = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        out.append("# Combined calibration (mock + services markdown)\n");
        out.append("# dikkat=p75, yuksek=p90, kritik=p95\n\n```yaml\n");
        for (Map.Entry<String, ToIntFunction<MethodScanValues>> entry : extractors.entrySet()) {
            String metricId = entry.getKey();
            String yamlKey = yamlKey(metricId);
            if (yamlKey == null) {
                continue;
            }
            Distribution d = PercentileStats.fromRecords(metricId, scans, entry.getValue());
            rows.add(d);
            int med = quantile(scans, entry.getValue(), 0.75);
            int high = quantile(scans, entry.getValue(), 0.90);
            int crit = quantile(scans, entry.getValue(), 0.95);
            if (d.p95() == 0 && d.max() <= 1) {
                med = 1;
                high = 1;
                crit = 2;
            } else {
                if (high <= med) {
                    high = med + 1;
                }
                if (crit <= high) {
                    crit = high + 1;
                }
            }
            out.append(String.format(Locale.US, "      %s:%n", yamlKey));
            out.append(String.format(Locale.US, "        thresholds: { dikkat: %d, yuksek: %d, kritik: %d }%n",
                    med, high, crit));
        }
        out.append("```\n\n").append(PercentileStats.markdownTable(rows));
        return out.toString();
    }

    private static String yamlKey(String metricId) {
        return switch (metricId) {
            case "cyclomaticComplexity" -> "branching";
            case "codeLines" -> "length";
            case "maxNestingDepth" -> "nesting";
            case "parameterCount" -> "parameters";
            case "cognitiveComplexity" -> "cognitive";
            case "exitPoints" -> "exitPoints";
            case "logicalStatements" -> "logicalStatements";
            case "lambdaCount" -> "lambdaCount";
            case "switchCases" -> "switchCases";
            case "maxTryNestingDepth" -> "maxTryNestingDepth";
            case "localVariableCount" -> "localVariableCount";
            case "maxMethodCallChainLength" -> "maxMethodCallChainLength";
            case "catchClauses" -> "catchClauses";
            case "emptyCatchBlocks" -> "emptyCatchBlocks";
            case "catchExceptionOrThrowable" -> "catchExceptionOrThrowable";
            case "catchWithOnlyPrintStackTrace" -> "catchWithOnlyPrintStackTrace";
            case "primitiveObsessionIndex" -> "primitiveObsessionIndex";
            case "maxBooleanOperatorsInCondition" -> "maxBooleanOperatorsInCondition";
            case "halsteadDifficultyRounded" -> "halsteadDifficulty";
            case "halsteadEffortRounded" -> "halsteadEffort";
            case "rawTypeUsage" -> "rawTypeUsage";
            case "stringConcatInLoop" -> "stringConcatInLoop";
            case "hardcodedLiteralCount" -> "hardcodedLiteralCount";
            case "swallowedExceptionSmells" -> "swallowedExceptionSmells";
            case "genericExceptionSmells" -> "genericExceptionSmells";
            default -> null;
        };
    }

    private static int quantile(List<MethodScanValues> scans, ToIntFunction<MethodScanValues> ex, double p) {
        List<Integer> sorted = scans.stream().mapToInt(ex).boxed().sorted().toList();
        return PercentileStats.quantile(sorted, p);
    }

    private static Map<String, ToIntFunction<MethodScanValues>> calibrationExtractors() {
        Map<String, ToIntFunction<MethodScanValues>> map = new LinkedHashMap<>();
        map.put("cyclomaticComplexity", MethodScanValues::cyclomaticComplexity);
        map.put("codeLines", MethodScanValues::codeLines);
        map.put("maxNestingDepth", MethodScanValues::maxNestingDepth);
        map.put("parameterCount", MethodScanValues::parameterCount);
        map.put("cognitiveComplexity", MethodScanValues::cognitiveComplexity);
        map.put("exitPoints", MethodScanValues::exitPoints);
        map.put("logicalStatements", MethodScanValues::logicalStatements);
        map.put("lambdaCount", MethodScanValues::lambdaCount);
        map.put("switchCases", MethodScanValues::switchCases);
        map.put("maxTryNestingDepth", MethodScanValues::maxTryNestingDepth);
        map.put("localVariableCount", MethodScanValues::localVariableCount);
        map.put("maxMethodCallChainLength", MethodScanValues::maxMethodCallChainLength);
        map.put("catchClauses", MethodScanValues::catchClauses);
        map.put("emptyCatchBlocks", MethodScanValues::emptyCatchBlocks);
        map.put("catchExceptionOrThrowable", MethodScanValues::catchExceptionOrThrowable);
        map.put("catchWithOnlyPrintStackTrace", MethodScanValues::catchWithOnlyPrintStackTrace);
        map.put("primitiveObsessionIndex", MethodScanValues::primitiveObsessionIndex);
        map.put("maxBooleanOperatorsInCondition", MethodScanValues::maxBooleanOperatorsInCondition);
        map.put("halsteadDifficultyRounded", MethodScanValues::halsteadDifficultyRounded);
        map.put("halsteadEffortRounded", MethodScanValues::halsteadEffortRounded);
        map.put("rawTypeUsage", MethodScanValues::rawTypeUsage);
        map.put("stringConcatInLoop", MethodScanValues::stringConcatInLoop);
        map.put("hardcodedLiteralCount", MethodScanValues::hardcodedLiteralCount);
        map.put("swallowedExceptionSmells", MethodScanValues::swallowedExceptionSmells);
        map.put("genericExceptionSmells", MethodScanValues::genericExceptionSmells);
        return map;
    }
}
