package com.scanner.exception.complexity;

import com.scanner.exception.complexity.AnalysisReport.ClassMetric;
import com.scanner.exception.complexity.AnalysisReport.FileMetric;
import com.scanner.exception.complexity.PercentileStats.Distribution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scans a tree and prints method/class metric percentile tables (Markdown).
 *
 * <pre>
 *   java -cp java-code-analyzer.jar com.scanner.exception.complexity.ScanMetricsCalibration /path/to/mock-modules
 * </pre>
 */
public final class ScanMetricsCalibration {

    private ScanMetricsCalibration() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: ScanMetricsCalibration <java-source-root>");
            System.exit(1);
        }
        Path root = Path.of(args[0]);
        System.out.print(renderMarkdown(analyzeRoot(root), root));
    }

    static AnalysisReport analyzeRoot(Path root) throws IOException {
        Path config = Path.of("config/risk-parameters-proposal.yaml");
        RiskProfile profile = RiskProfileLoader.load("enterprise-java", config);
        ScanOptions options = new ScanOptions(1, 500, List.of(), List.of("**/src/test/**"),
                ScanOptions.ReportDetail.FULL, true, null, false, false, 50, true, 0, false);
        ProjectAnalyzer analyzer = new ProjectAnalyzer(StandardCharsets.UTF_8, 5,
                com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17, options,
                new RiskCalculator(profile));
        return analyzer.analyze(root);
    }

    static String renderMarkdown(AnalysisReport report, Path root) {
        List<MethodMetric> methods = new ArrayList<>();
        List<ClassMetric> classes = new ArrayList<>();
        for (FileMetric file : report.files()) {
            for (ClassMetric type : file.classes()) {
                classes.add(type);
                methods.addAll(type.methods());
            }
        }

        List<Distribution> methodRows = List.of(
                PercentileStats.fromRecords("cyclomaticComplexity", methods, MethodMetric::cyclomaticComplexity),
                PercentileStats.fromRecords("codeLines", methods, MethodMetric::codeLines),
                PercentileStats.fromRecords("maxNestingDepth", methods, MethodMetric::maxNestingDepth),
                PercentileStats.fromRecords("parameterCount", methods, MethodMetric::parameterCount),
                PercentileStats.fromRecords("cognitiveComplexity", methods, MethodMetric::cognitiveComplexity),
                PercentileStats.fromRecords("logicalStatements", methods, MethodMetric::logicalStatements),
                PercentileStats.fromRecords("exitPoints", methods, MethodMetric::exitPoints),
                PercentileStats.fromRecords("catchClauses", methods, MethodMetric::catchClauses),
                PercentileStats.fromRecords("switchCases", methods, MethodMetric::switchCases),
                PercentileStats.fromRecords("outboundDistinctCalls", methods, MethodMetric::outboundDistinctCalls),
                PercentileStats.fromRecords("primitiveObsessionIndex", methods, MethodMetric::primitiveObsessionIndex),
                PercentileStats.fromRecords("maxBooleanOperatorsInCondition", methods,
                        MethodMetric::maxBooleanOperatorsInCondition),
                PercentileStats.fromRecords("lambdaCount", methods, MethodMetric::lambdaCount),
                PercentileStats.fromRecords("maxTryNestingDepth", methods, MethodMetric::maxTryNestingDepth),
                PercentileStats.fromRecords("localVariableCount", methods, MethodMetric::localVariableCount),
                PercentileStats.fromRecords("maxMethodCallChainLength", methods, MethodMetric::maxMethodCallChainLength),
                PercentileStats.fromRecords("emptyCatchBlocks", methods, MethodMetric::emptyCatchBlocks),
                PercentileStats.fromRecords("catchExceptionOrThrowable", methods, MethodMetric::catchExceptionOrThrowable),
                PercentileStats.fromRecords("catchWithOnlyPrintStackTrace", methods,
                        MethodMetric::catchWithOnlyPrintStackTrace));

        List<Distribution> classRows = List.of(
                PercentileStats.fromRecords("methodCount", classes, ClassMetric::methodCount),
                PercentileStats.fromRecords("codeLines (class)", classes, ClassMetric::codeLines),
                PercentileStats.fromRecords("weightedMethodComplexity (WMC)", classes,
                        ClassMetric::weightedMethodComplexity),
                PercentileStats.fromRecords("publicMethodCount", classes, ClassMetric::publicMethodCount),
                PercentileStats.fromRecords("efferentCouplingProxy", classes, ClassMetric::efferentCouplingProxy));

        StringBuilder md = new StringBuilder();
        md.append("# mock-modules tarama metrikleri — yüzdelikler\n\n");
        md.append("Otomatik üretim: `ScanMetricsCalibration` — ").append(LocalDate.now()).append("\n\n");
        md.append("Kök: `").append(root.toString().replace('\\', '/')).append("`\n\n");
        md.append(String.format(Locale.US,
                "Özet: %d dosya, %d sınıf, %d metod, %d kod satırı.%n%n",
                report.summary().filesParsed(),
                report.summary().classCount(),
                report.summary().methodCount(),
                report.summary().totalCodeLines()));
        md.append("## Metod\n\n");
        md.append(PercentileStats.markdownTable(methodRows));
        md.append("\n## Sınıf\n\n");
        md.append(PercentileStats.markdownTable(classRows));
        md.append("\n");
        md.append(PercentileStats.yamlThresholdBlock(methodRows, PercentileStats.enterpriseJavaMetricKeys()));
        md.append("\nNot: FOUT artık import-aware + JDK/same-type filtreli; eşikleri bu tabloya göre güncelle.\n");
        return md.toString();
    }
}
