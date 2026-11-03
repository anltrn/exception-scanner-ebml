package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.scanner.complexity.AnalysisReport.FileError;
import com.scanner.complexity.AnalysisReport.FileMetric;
import com.scanner.complexity.AnalysisReport.PmdRulesetPhase;
import com.scanner.complexity.AnalysisReport.PmdViolationRow;
import com.scanner.complexity.ClassicPmdRulesetScanner.ScanOutcome;
import com.scanner.complexity.ScanOptions.ReportDetail;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Yalnizca PMD: ruleset + PMD AST/JavaMetrics + ihlal zenginlestirme + RiskCalculator.
 * {@code run-pmd-analyze.sh} bu hattı kullanır; JavaParser bu scriptte calismaz.
 */
public final class PmdNativeProjectScanner {

    private static final String TOOL_NAME =
            "PMD-only scan (ruleset + JavaMetrics + PMD AST stats + violation enrich)";

    private final Charset charset;
    private final ParserConfiguration.LanguageLevel languageLevel;
    private final ScanOptions scanOptions;
    private final RiskCalculator riskCalculator;
    private final int topN;
    private final Path rulesetFile;
    private final Path violationsXmlPath;
    private final boolean runRulesetPhase;

    public PmdNativeProjectScanner(Charset charset, ParserConfiguration.LanguageLevel languageLevel,
                                   ScanOptions scanOptions, int topN, RiskCalculator riskCalculator,
                                   Path rulesetFile, Path violationsXmlPath, boolean runRulesetPhase) {
        this.charset = charset;
        this.languageLevel = languageLevel;
        this.scanOptions = scanOptions;
        this.riskCalculator = riskCalculator;
        this.topN = topN;
        this.rulesetFile = rulesetFile;
        this.violationsXmlPath = violationsXmlPath;
        this.runRulesetPhase = runRulesetPhase;
    }

    public AnalysisReport scan(Path root) throws IOException {
        long started = System.nanoTime();
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path base = Files.isDirectory(absoluteRoot) ? absoluteRoot : absoluteRoot.getParent();

        List<FileError> errors = Collections.synchronizedList(new ArrayList<>());
        JavaSourceDiscovery.Result discovery = JavaSourceDiscovery.collect(absoluteRoot, base, errors, scanOptions);
        List<Path> sources = discovery.paths();
        logDiscovery(discovery, sources.size());

        Map<String, List<PmdViolationRow>> violationsByAbsolutePath = Map.of();
        PmdRulesetPhase pmdPhase = null;
        if (runRulesetPhase && rulesetFile != null && Files.isRegularFile(rulesetFile)) {
            System.err.println("[PMD-NATIVE] Ruleset scan → " + rulesetFile.toAbsolutePath().normalize());
            long p1 = System.nanoTime();
            ScanOutcome outcome = ClassicPmdRulesetScanner.scanWithViolations(
                    sources, base, rulesetFile, violationsXmlPath, charset, languageLevel, scanOptions.workers());
            pmdPhase = outcome.phase();
            violationsByAbsolutePath = indexViolations(outcome.allViolations());
            System.err.println("[PMD-NATIVE] Ruleset done: " + pmdPhase.violationCount() + " violation(s), "
                    + (System.nanoTime() - p1) / 1_000_000L + " ms");
        } else if (runRulesetPhase) {
            System.err.println("[PMD-NATIVE] Ruleset skipped (file missing)");
        }

        System.err.println("[PMD-NATIVE] Risk: PMD AST metrics + ruleset violation signals (no JavaParser)");
        Map<String, List<PmdViolationRow>> violationsByPath = violationsByAbsolutePath;
        boolean attachHashes = scanOptions.detectDuplicates();
        List<FileMetric> files = metricsPass(sources, base, errors, violationsByPath, attachHashes);
        files.sort(Comparator.comparing(FileMetric::path));
        errors.sort(Comparator.comparing(FileError::file));

        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        System.err.println("[PMD-NATIVE] Total wall time: " + elapsedMs + " ms");

        List<AnalysisReport.DuplicateGroup> duplicateGroups =
                DuplicateScanSupport.detectGroups(absoluteRoot, scanOptions, files);
        AnalysisReport.DuplicateStatistics duplicateStatistics = DuplicateScanSupport.summarise(duplicateGroups);
        DuplicateScanSupport.logSummary(duplicateStatistics);

        AnalysisReport.Summary summary = ReportAggregates.buildSummary(sources.size(), files, errors, absoluteRoot);
        List<AnalysisReport.RiskHotspot> hotspots = ReportAggregates.buildHotspots(files, topN);

        return new AnalysisReport(
                TOOL_NAME,
                Instant.now().toString(),
                absoluteRoot.toString(),
                languageLevel.name(),
                riskCalculator.buildRiskModel(),
                summary,
                hotspots,
                files,
                errors,
                null,
                null,
                null,
                duplicateStatistics,
                duplicateGroups,
                pmdPhase);
    }

    private void logDiscovery(JavaSourceDiscovery.Result discovery, int count) {
        if (discovery.skippedDirectorySubtrees() > 0) {
            System.err.println("[PMD-NATIVE] Skipped " + discovery.skippedDirectorySubtrees()
                    + " directory subtree(s)");
        }
        System.err.println("[PMD-NATIVE] " + count + " .java file(s), language " + languageLevel.name()
                + ", workers=" + scanOptions.workers());
    }

    private static Map<String, List<PmdViolationRow>> indexViolations(List<PmdViolationRow> all) {
        Map<String, List<PmdViolationRow>> map = new HashMap<>();
        for (PmdViolationRow row : all) {
            String key = Path.of(row.file()).toAbsolutePath().normalize().toString();
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return map;
    }

    private List<FileMetric> metricsPass(List<Path> sources, Path base, List<FileError> errors,
                                         Map<String, List<PmdViolationRow>> violationsByPath,
                                         boolean attachDuplicateHashes) throws IOException {
        if (sources.isEmpty()) {
            return List.of();
        }
        List<FileMetric> files = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger processed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(scanOptions.workers());
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (Path source : sources) {
                tasks.add(() -> {
                    metricsOne(source, base, files, errors, violationsByPath, attachDuplicateHashes);
                    int n = processed.incrementAndGet();
                    if (n % scanOptions.progressEvery() == 0 || n == sources.size()) {
                        System.err.println("[PMD-NATIVE] PMD metrics: " + n + "/" + sources.size() + " files");
                    }
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("PMD metrics interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("PMD metrics failed", e.getCause());
        } finally {
            pool.shutdown();
        }
        if (scanOptions.reportDetail() == ReportDetail.SUMMARY) {
            return files.stream().map(PmdNativeProjectScanner::summaryStub).collect(Collectors.toList());
        }
        return new ArrayList<>(files);
    }

    private void metricsOne(Path source, Path base, List<FileMetric> files, List<FileError> errors,
                            Map<String, List<PmdViolationRow>> violationsByPath, boolean attachDuplicateHashes) {
        String relative = JavaSourceDiscovery.relativePath(base, source);
        try {
            String text = SourceEncoding.read(source, charset);
            String abs = source.toAbsolutePath().normalize().toString();
            List<PmdViolationRow> fileViolations = violationsByPath.getOrDefault(abs, List.of());
            FileMetric metric = PmdFileMetricsBuilder.build(relative, text, languageLevel, fileViolations,
                    riskCalculator, attachDuplicateHashes);
            files.add(metric);
        } catch (Exception e) {
            errors.add(new FileError(relative, "PMD metrics: " + e.getMessage()));
        }
    }

    private static FileMetric summaryStub(FileMetric full) {
        return new FileMetric(full.path(), full.packageName(), full.physicalLines(), full.codeLines(),
                full.classCount(), full.methodCount(), full.totalCyclomaticComplexity(),
                full.maxCyclomaticComplexity(), full.riskScore(), full.riskLevel(), full.riskFactors(), List.of(),
                full.halstead(), full.swallowedExceptionCount(), full.genericExceptionCatchCount(),
                full.rawTypeUsageCount(), full.stringConcatInLoopCount(), full.hardcodedLiteralCount(),
                full.fileHash(), full.fullyReusedFromCache(), full.methodsReusedFromCache());
    }
}
