package com.scanner.complexity;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.DuplicateGroup;
import com.scanner.complexity.AnalysisReport.DuplicateStatistics;
import com.scanner.complexity.AnalysisReport.FileError;
import com.scanner.complexity.AnalysisReport.FileMetric;
import com.scanner.complexity.AnalysisReport.RiskHotspot;
import com.scanner.complexity.AnalysisReport.Summary;
import com.scanner.complexity.ScanOptions.ReportDetail;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Scans a directory tree, parses .java files and assembles the {@link AnalysisReport}. */
public final class ProjectAnalyzer {

    private static final String TOOL_NAME = "Standalone Java Code Analyzer";
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".svn", ".hg", ".idea", "target", "node_modules",
            "build", "out", "bin", "dist", ".gradle", "coverage",
            "generated", "generated-sources", "generated-test-sources",
            "__generated__", ".mvn", "vendor", "tmp");

    /** Deep expression trees (generated SQL/strings) need more stack than the default ~1 MB. */
    private static final long WORKER_STACK_BYTES = 64L << 20;
    private static final AtomicInteger WORKER_ID = new AtomicInteger();

    private final RiskCalculator riskCalculator;
    private final ParserConfiguration parserConfiguration;
    private final int topN;
    private final ParserConfiguration.LanguageLevel languageLevel;
    private final ScanOptions scanOptions;
    private final DuplicateDetectionEngine duplicateDetectionEngine = new DuplicateDetectionEngine();

    public ProjectAnalyzer(Charset sourceCharset, int topN, ParserConfiguration.LanguageLevel languageLevel) {
        this(sourceCharset, topN, languageLevel, ScanOptions.defaults(), new RiskCalculator());
    }

    public ProjectAnalyzer(Charset sourceCharset, int topN, ParserConfiguration.LanguageLevel languageLevel,
                           ScanOptions scanOptions) {
        this(sourceCharset, topN, languageLevel, scanOptions, new RiskCalculator());
    }

    public ProjectAnalyzer(Charset sourceCharset, int topN, ParserConfiguration.LanguageLevel languageLevel,
                           ScanOptions scanOptions, RiskCalculator riskCalculator) {
        this.riskCalculator = riskCalculator;
        this.languageLevel = languageLevel;
        this.scanOptions = scanOptions;
        this.parserConfiguration = new ParserConfiguration()
                .setLanguageLevel(languageLevel)
                .setCharacterEncoding(sourceCharset)
                .setStoreTokens(true)
                .setAttributeComments(false);
        this.topN = topN;
    }

    public AnalysisReport analyze(Path root) throws IOException {
        return analyze(root, new ScanRunContext());
    }

    public AnalysisReport analyze(Path root, ScanRunContext runContext) throws IOException {
        long started = System.nanoTime();
        ScanRunContext run = runContext != null ? runContext : new ScanRunContext();
        run.phase(ScanPhase.DISCOVERY);
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path base = Files.isDirectory(absoluteRoot) ? absoluteRoot : absoluteRoot.getParent();

        List<FileError> errors = Collections.synchronizedList(new ArrayList<>());
        FileDiscovery discovery = collectJavaFiles(absoluteRoot, base, errors, scanOptions);
        List<Path> sources = discovery.paths();

        System.err.println("[STANDALONE] " + sources.size() + " .java file(s) queued"
                + (discovery.skippedByFilter() > 0
                ? " (" + discovery.skippedByFilter() + " skipped by include/exclude)" : "")
                + ", parsing as " + languageLevel.name()
                + ", workers=" + scanOptions.workers() + ", detail=" + scanOptions.reportDetail() + "...");

        ModuleRootIndex moduleRoots = ModuleRootIndex.forScanRoot(absoluteRoot);
        ScanAccumulator summaryAccumulator =
                scanOptions.reportDetail() == ReportDetail.SUMMARY
                        ? new ScanAccumulator(topN, moduleRoots) : null;
        run.phase(ScanPhase.PARSING);
        Path stateFile = scanOptions.incrementalStateFile();
        boolean incremental = stateFile != null;
        if (incremental && scanOptions.workers() > 1) {
            System.err.println("[STANDALONE] Incremental cache: forcing workers=1 (parallel + state unsupported).");
        }
        AnalyzerState loadedState = incremental && !scanOptions.ignoreIncrementalCache()
                ? new StatePersistenceManager().load(stateFile)
                : new AnalyzerState();
        boolean hadPreviousScan = incremental && !scanOptions.ignoreIncrementalCache()
                && cacheIdentityMatches(loadedState, riskCalculator)
                && loadedState.files != null && !loadedState.files.isEmpty();
        if (incremental && !scanOptions.ignoreIncrementalCache() && !hadPreviousScan
                && loadedState.files != null && !loadedState.files.isEmpty()) {
            System.err.println("[STANDALONE] Cache outdated (risk profile or analyzer schema changed); full re-score.");
        }
        AnalyzerState previousState = hadPreviousScan ? loadedState : new AnalyzerState();
        IncrementalAnalysisEngine incrementalEngine = incremental ? new IncrementalAnalysisEngine(previousState) : null;
        AnalyzerState stateForDiff = hadPreviousScan ? loadedState : new AnalyzerState();
        CacheRunCounters cacheCounters = incremental ? new CacheRunCounters() : null;
        IncrementalScanContext scanContext = null;
        if (incremental && incrementalEngine != null) {
            scanContext = IncrementalModulePlanner.prepare(sources, base, moduleRoots, previousState);
            if (scanContext.modulesUnchanged() > 0) {
                System.err.printf(Locale.ROOT,
                        "[STANDALONE] Incremental: %d/%d module(s) unchanged (bulk skip parse).%n",
                        scanContext.modulesUnchanged(), scanContext.modulesScanned());
            }
        }
        Map<String, String> semanticHashesByPath = incremental ? new HashMap<>() : Map.of();

        List<FileMetric> files = parseAll(sources, base, errors, summaryAccumulator, run, incrementalEngine,
                cacheCounters, scanContext, semanticHashesByPath);
        Collections.sort(files, (a, b) -> a.path().compareTo(b.path()));
        errors.sort(Comparator.comparing(FileError::file));
        run.phase(ScanPhase.REPORT_ASSEMBLY);

        IncrementalChanges incrementalChanges = incremental
                ? IncrementalChangeDetector.detect(stateForDiff, files, hadPreviousScan, semanticHashesByPath)
                : IncrementalChanges.empty();
        if (incremental && incrementalChanges.comparedToPreviousScan()) {
            logIncrementalChanges(incrementalChanges);
        }

        if (incremental) {
            persistAnalyzerState(stateFile, files, riskCalculator, semanticHashesByPath,
                    scanContext != null ? scanContext.moduleFingerprints() : Map.of());
        }

        int filesParsed = files.size();
        int filesFailed = errors.size();
        Summary summary = summaryAccumulator != null
                ? summaryAccumulator.toSummary(sources.size(), filesParsed, filesFailed)
                : buildSummary(sources.size(), files, errors, moduleRoots);
        List<RiskHotspot> hotspots = summaryAccumulator != null
                ? summaryAccumulator.topHotspots()
                : buildHotspots(files);
        List<FileMetric> reportFiles = files;
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        System.err.println("[STANDALONE] Parse wall time: " + elapsedMs + " ms");

        AnalysisReport.CacheStatistics cacheStatistics = null;
        if (incremental && cacheCounters != null) {
            if (scanContext != null) {
                cacheCounters.modulesUnchanged = scanContext.modulesUnchanged();
            }
            cacheStatistics = new AnalysisReport.CacheStatistics(sources.size(), cacheCounters.filesSkippedViaFileHash,
                    cacheCounters.filesReanalyzed, cacheCounters.filesSemanticCosmeticOnly,
                    cacheCounters.filesSkippedViaModuleBulk, cacheCounters.modulesUnchanged,
                    cacheCounters.totalMethods, cacheCounters.methodsSkippedViaHash,
                    cacheCounters.methodsReanalyzed, elapsedMs);
            logCacheSummary(cacheStatistics);
        }

        List<DuplicateGroup> duplicateGroups = detectDuplicateGroups(absoluteRoot, reportFiles);
        DuplicateStatistics duplicateStatistics = summariseDuplicates(duplicateGroups);
        logDuplicateSummary(duplicateStatistics);

        AnalysisReport report = new AnalysisReport(TOOL_NAME, Instant.now().toString(), absoluteRoot.toString(),
                languageLevel.name(), riskCalculator.buildRiskModel(), summary, hotspots, reportFiles, errors, null,
                cacheStatistics, incrementalChanges, duplicateStatistics, duplicateGroups);
        ScanDiagnostics diagnostics = ScanDiagnostics.from(report, run, null);
        run.phase(ScanPhase.COMPLETE);
        return new AnalysisReport(TOOL_NAME, report.generatedAt(), report.analyzedPath(),
                report.parserLanguageLevel(), report.riskModel(), report.summary(), report.topRiskyMethods(),
                report.files(), report.errors(), diagnostics, cacheStatistics, incrementalChanges,
                duplicateStatistics, duplicateGroups);
    }

    private List<DuplicateGroup> detectDuplicateGroups(Path absoluteRoot, List<FileMetric> files) {
        if (!scanOptions.detectDuplicates()) {
            return List.of();
        }
        List<DuplicateGroup> groups = new ArrayList<>();
        groups.addAll(duplicateDetectionEngine.detectExactDuplicates(files));
        if (shouldRunNearMissDuplicatePass(files.size())) {
            try {
                groups.addAll(duplicateDetectionEngine.detectNearMissDuplicates(
                        absoluteRoot, scanOptions.minDuplicateTokens(), files));
            } catch (RuntimeException e) {
                System.err.println("[STANDALONE] Near-miss duplicate detection (PMD CPD) failed, "
                        + "continuing with exact-duplicate results only: " + e);
            }
        }
        return groups;
    }

    private boolean shouldRunNearMissDuplicatePass(int parsedFileCount) {
        if (!scanOptions.detectNearMissDuplicates()) {
            return false;
        }
        int threshold = scanOptions.nearDuplicateAutoSkipMinFiles();
        if (threshold > 0 && parsedFileCount >= threshold && !scanOptions.forceNearMissDuplicates()) {
            System.err.println("[STANDALONE] Near-miss duplicate (PMD CPD) skipped: "
                    + parsedFileCount + " files >= threshold " + threshold
                    + " (EXACT_TEXT/EXACT_STRUCTURE still run; use --force-near-duplicates or "
                    + "--near-duplicate-auto-skip-files=0 to change).");
            return false;
        }
        return true;
    }

    private static DuplicateStatistics summariseDuplicates(List<DuplicateGroup> groups) {
        int exactGroups = 0;
        int nearMissGroups = 0;
        int methodsInExact = 0;
        int methodsInNearMiss = 0;
        for (DuplicateGroup group : groups) {
            if ("NEAR_MISS".equals(group.similarityType())) {
                nearMissGroups++;
                methodsInNearMiss += group.members().size();
            } else {
                exactGroups++;
                methodsInExact += group.members().size();
            }
        }
        return new DuplicateStatistics(exactGroups, methodsInExact, nearMissGroups, methodsInNearMiss);
    }

    private static void logDuplicateSummary(DuplicateStatistics stats) {
        if (stats.exactGroups() == 0 && stats.nearMissGroups() == 0) {
            return;
        }
        System.err.printf(Locale.ROOT,
                "[STANDALONE] Duplicates: %d exact group(s) (%d methods) | "
                        + "%d near-miss group(s) (%d methods)%n",
                stats.exactGroups(), stats.methodsInExactGroups(),
                stats.nearMissGroups(), stats.methodsInNearMissGroups());
    }

    private List<FileMetric> parseAll(List<Path> sources, Path base, List<FileError> errors,
                                      ScanAccumulator summaryAccumulator, ScanRunContext run,
                                      IncrementalAnalysisEngine incrementalEngine, CacheRunCounters cacheCounters,
                                      IncrementalScanContext scanContext, Map<String, String> semanticHashesByPath) {
        if (sources.isEmpty()) {
            return new ArrayList<>();
        }
        int workers = incrementalEngine != null ? 1 : scanOptions.workers();
        if (sources.size() == 1 || workers == 1) {
            return parseSequential(sources, base, errors, summaryAccumulator, run, incrementalEngine, cacheCounters,
                    scanContext, semanticHashesByPath);
        }
        return parseParallel(sources, base, errors, summaryAccumulator, run, incrementalEngine, cacheCounters,
                scanContext, semanticHashesByPath);
    }

    private List<FileMetric> parseSequential(List<Path> sources, Path base, List<FileError> errors,
                                             ScanAccumulator summaryAccumulator, ScanRunContext run,
                                             IncrementalAnalysisEngine incrementalEngine, CacheRunCounters cacheCounters,
                                             IncrementalScanContext scanContext, Map<String, String> semanticHashesByPath) {
        List<FileMetric> files = new ArrayList<>();
        int processed = 0;
        for (Path source : sources) {
            parseOne(source, base, files, errors, summaryAccumulator, run, incrementalEngine, cacheCounters,
                    scanContext, semanticHashesByPath);
            logProgress(++processed, sources.size());
        }
        return files;
    }

    private List<FileMetric> parseParallel(List<Path> sources, Path base, List<FileError> errors,
                                           ScanAccumulator summaryAccumulator, ScanRunContext run,
                                           IncrementalAnalysisEngine incrementalEngine, CacheRunCounters cacheCounters,
                                           IncrementalScanContext scanContext, Map<String, String> semanticHashesByPath) {
        List<FileMetric> files = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger processed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(scanOptions.workers(), ProjectAnalyzer::newWorkerThread);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (Path source : sources) {
                tasks.add(() -> {
                    parseOne(source, base, files, errors, summaryAccumulator, run, incrementalEngine, cacheCounters,
                            scanContext, semanticHashesByPath);
                    logProgress(processed.incrementAndGet(), sources.size());
                    return null;
                });
            }
            try {
                List<Future<Void>> futures = pool.invokeAll(tasks);
                for (Future<Void> future : futures) {
                    future.get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Parallel parse interrupted", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("Parallel parse failed", e.getCause());
            }
        } finally {
            pool.shutdown();
        }
        return new ArrayList<>(files);
    }

    private void parseOne(Path source, Path base, List<FileMetric> files, List<FileError> errors,
                          ScanAccumulator summaryAccumulator, ScanRunContext run,
                          IncrementalAnalysisEngine incrementalEngine, CacheRunCounters cacheCounters,
                          IncrementalScanContext scanContext, Map<String, String> semanticHashesByPath) {
        String relative = relativePath(base, source);
        run.parsingFile(relative);
        try {
            if (incrementalEngine != null) {
                if (scanContext != null && scanContext.bulkReuse(relative)) {
                    FileMetric reused = incrementalEngine.reusableFromState(relative);
                    if (reused != null) {
                        files.add(reused);
                        if (cacheCounters != null) {
                            cacheCounters.filesSkippedViaModuleBulk++;
                            cacheCounters.totalMethods += reused.methodCount();
                            cacheCounters.methodsSkippedViaHash += reused.methodCount();
                        }
                        copyPreviousSemanticHash(incrementalEngine, semanticHashesByPath, relative);
                        ingestReused(relative, reused, summaryAccumulator);
                        return;
                    }
                }
                String precomputed = scanContext != null ? scanContext.byteHash(relative) : null;
                IncrementalAnalysisEngine.CacheCheck check =
                        incrementalEngine.checkFileCache(source, relative, precomputed);
                if (check.canSkipParsing()) {
                    FileMetric reused = check.reusableMetric().reusedCopy();
                    files.add(reused);
                    if (cacheCounters != null) {
                        cacheCounters.filesSkippedViaFileHash++;
                        cacheCounters.totalMethods += reused.methodCount();
                        cacheCounters.methodsSkippedViaHash += reused.methodCount();
                    }
                    copyPreviousSemanticHash(incrementalEngine, semanticHashesByPath, relative);
                    ingestReused(relative, reused, summaryAccumulator);
                    return;
                }
                SourceParser.Outcome outcome = SourceParser.parse(source, sourceCharset(), languageLevel);
                ParseResult<CompilationUnit> result = outcome.result();
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    CompilationUnit cu = result.getResult().get();
                    String semanticHash = HashService.semanticCompilationUnitHash(cu);
                    semanticHashesByPath.put(relative, semanticHash);
                    if (incrementalEngine.isSemanticOnlyDrift(relative, semanticHash)) {
                        FileMetric cached = incrementalEngine.reusableFromState(relative);
                        if (cached != null) {
                            FileMetric metric = cached.withRefreshedFileHash(check.fileHash());
                            files.add(metric);
                            if (cacheCounters != null) {
                                cacheCounters.filesSemanticCosmeticOnly++;
                                cacheCounters.totalMethods += metric.methodCount();
                                cacheCounters.methodsSkippedViaHash += metric.methodCount();
                            }
                            ingestReused(relative, metric, summaryAccumulator);
                            return;
                        }
                    }
                    FileMetric metric = incrementalEngine.buildFileMetric(relative, cu,
                            check.fileHash(), riskCalculator,
                            incrementalEngine.previousMethodsByKey(relative), summaryAccumulator);
                    files.add(metric);
                    if (cacheCounters != null) {
                        cacheCounters.filesReanalyzed++;
                        cacheCounters.totalMethods += metric.methodCount();
                        cacheCounters.methodsSkippedViaHash += metric.methodsReusedFromCache();
                        cacheCounters.methodsReanalyzed += metric.methodCount() - metric.methodsReusedFromCache();
                    }
                } else {
                    String detail = describe(result.getProblems());
                    if (outcome.attempted().size() > 1) {
                        detail = detail + " (tried language levels: "
                                + outcome.attempted().stream().map(Enum::name).collect(Collectors.joining(", "))
                                + ")";
                    }
                    errors.add(fileError(relative, "Parse error: " + detail));
                }
                return;
            }
            SourceParser.Outcome outcome = SourceParser.parse(source, sourceCharset(), languageLevel);
            ParseResult<CompilationUnit> result = outcome.result();
            if (result.isSuccessful() && result.getResult().isPresent()) {
                files.add(buildFileMetric(relative, result.getResult().get(), summaryAccumulator));
            } else {
                String detail = describe(result.getProblems());
                if (outcome.attempted().size() > 1) {
                    detail = detail + " (tried language levels: "
                            + outcome.attempted().stream().map(Enum::name).collect(Collectors.joining(", ")) + ")";
                }
                errors.add(fileError(relative, "Parse error: " + detail));
            }
        } catch (IOException e) {
            errors.add(fileError(relative, "I/O error: " + e.getMessage()));
        } catch (ParseProblemException e) {
            errors.add(fileError(relative, "Parse error: " + e.getMessage()));
        } catch (StackOverflowError e) {
            errors.add(fileError(relative, "Analysis aborted: expression nesting too deep (stack overflow)"));
        } catch (OutOfMemoryError e) {
            errors.add(fileError(relative, "Analysis aborted: out of memory while analyzing file"));
        } catch (RuntimeException e) {
            errors.add(fileError(relative, "Unexpected error: " + e));
        } finally {
            run.clearParsingFile();
            run.fileFinished();
        }
    }

    private static void copyPreviousSemanticHash(
            IncrementalAnalysisEngine engine, Map<String, String> semanticHashesByPath, String relative) {
        String previous = engine.previousSemanticFileHash(relative);
        if (previous != null) {
            semanticHashesByPath.put(relative, previous);
        }
    }

    private static void ingestReused(String relative, FileMetric reused, ScanAccumulator summaryAccumulator) {
        if (summaryAccumulator != null && !reused.classes().isEmpty()) {
            summaryAccumulator.ingestFile(relative, reused.packageName(), reused.classes(), reused.codeLines());
        }
    }

    private static FileError fileError(String relative, String message) {
        return new FileError(relative, message, ScanErrorClassifier.classify(message));
    }

    private void logProgress(int processed, int total) {
        if (processed % scanOptions.progressEvery() == 0 || processed == total) {
            System.err.println("[STANDALONE] " + processed + "/" + total + " files processed");
        }
    }

    // ------------------------------------------------------------------ file discovery

    private record FileDiscovery(List<Path> paths, int skippedByFilter) {
    }

    private FileDiscovery collectJavaFiles(Path root, Path base, List<FileError> errors, ScanOptions scan)
            throws IOException {
        if (Files.isRegularFile(root)) {
            return new FileDiscovery(List.of(root), 0);
        }
        FileSystem fs = root.getFileSystem();
        PathGlobFilter filter = new PathGlobFilter(fs, scan.includeGlobs(), scan.excludeGlobs());
        List<Path> result = new ArrayList<>();
        int[] skipped = {0};
        int[] skippedIgnoredDirs = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root)) {
                    if (shouldSkipAsBuildOutputDirectory(root, dir, scan.applyDefaultIgnoredDirectories())) {
                        skippedIgnoredDirs[0]++;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path probe = dir.resolve("__analyzer_probe__.java");
                    if (!filter.accept(root, probe)) {
                        skippedIgnoredDirs[0]++;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java")) {
                    if (filter.accept(root, file)) {
                        result.add(file);
                    } else {
                        skipped[0]++;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                errors.add(new FileError(relativePath(base, file), "Cannot access: " + exc.getMessage()));
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(result);
        if (skippedIgnoredDirs[0] > 0) {
            System.err.println("[STANDALONE] Skipped " + skippedIgnoredDirs[0]
                    + " directory subtree(s) (build outputs / exclude globs)");
        }
        return new FileDiscovery(result, skipped[0]);
    }

    /**
     * Skip {@code target/build/dist/...} trees but not package segments such as
     * {@code src/main/java/com/acme/build/util}.
     */
    static boolean shouldSkipAsBuildOutputDirectory(Path scanRoot, Path dir, boolean applyDefaultIgnores) {
        if (!applyDefaultIgnores) {
            return false;
        }
        Path name = dir.getFileName();
        if (name == null || !IGNORED_DIRECTORIES.contains(name.toString())) {
            return false;
        }
        String relative = scanRoot.relativize(dir).toString().replace('\\', '/');
        if (isUnderJavaSourceTree(relative)) {
            return false;
        }
        Path parent = dir.getParent();
        return parent != null && isModuleBoundaryDirectory(scanRoot, parent);
    }

    static boolean isModuleBoundaryDirectory(Path scanRoot, Path directory) {
        if (directory.equals(scanRoot)) {
            return true;
        }
        return hasBuildMarker(directory)
                || Files.isDirectory(directory.resolve("src/main/java"))
                || Files.isDirectory(directory.resolve("src"));
    }

    private static boolean hasBuildMarker(Path directory) {
        return Files.isRegularFile(directory.resolve("pom.xml"))
                || Files.isRegularFile(directory.resolve("build.gradle"))
                || Files.isRegularFile(directory.resolve("build.gradle.kts"));
    }

    private Charset sourceCharset() {
        Charset encoding = parserConfiguration.getCharacterEncoding();
        return encoding != null ? encoding : java.nio.charset.StandardCharsets.UTF_8;
    }

    static boolean isUnderJavaSourceTree(String relativeUnixPath) {
        return relativeUnixPath.contains("/src/main/java/")
                || relativeUnixPath.contains("/src/test/java/")
                || relativeUnixPath.startsWith("src/main/java/")
                || relativeUnixPath.startsWith("src/test/java/");
    }

    private static String relativePath(Path base, Path file) {
        Path relative = base != null && file.startsWith(base) ? base.relativize(file) : file;
        return relative.toString().replace('\\', '/');
    }

    private static String describe(List<Problem> problems) {
        if (problems.isEmpty()) {
            return "unknown parser failure";
        }
        return problems.stream().limit(3).map(Problem::getVerboseMessage).collect(Collectors.joining(" | "));
    }

    // ------------------------------------------------------------------ per-file assembly

    private FileMetric buildFileMetric(String relativePath, CompilationUnit cu, ScanAccumulator summaryAccumulator) {
        return FileMetricsBuilder.build(relativePath, cu, riskCalculator, Map.of(), summaryAccumulator, "", false);
    }

    private static void logIncrementalChanges(IncrementalChanges changes) {
        System.err.printf(Locale.ROOT,
                "[STANDALONE] Since last scan: files +%d -%d ~%d cosmetic %d (= %d unchanged); methods +%d -%d ~%d%n",
                changes.filesAdded(), changes.filesRemoved(), changes.filesModified(), changes.filesCosmeticOnly(),
                changes.filesUnchanged(),
                changes.methodsAdded(), changes.methodsRemoved(), changes.methodsModified());
        int limit = 15;
        int shown = 0;
        for (IncrementalChanges.MethodPathChange mc : changes.methodChanges()) {
            if (shown >= limit) {
                System.err.println("[STANDALONE]   ... more method changes in JSON incrementalChanges");
                break;
            }
            if (mc.kind() == IncrementalChanges.ChangeKind.REMOVED) {
                System.err.printf(Locale.ROOT, "[STANDALONE]   %s %s.%s removed%n",
                        mc.file(), mc.className(), mc.methodSignature());
            } else {
                System.err.printf(Locale.ROOT, "[STANDALONE]   %s %s.%s lines %d-%d (%s)%n",
                        mc.file(), mc.className(), mc.methodSignature(), mc.startLine(), mc.endLine(),
                        mc.kind().name().toLowerCase(Locale.ROOT));
            }
            shown++;
        }
    }

    private static void persistAnalyzerState(
            Path stateFile,
            List<FileMetric> files,
            RiskCalculator riskCalculator,
            Map<String, String> semanticHashesByPath,
            Map<String, String> moduleFingerprints) {
        AnalyzerState newState = new AnalyzerState();
        newState.cacheIdentity = currentCacheIdentity(riskCalculator);
        for (Map.Entry<String, String> entry : moduleFingerprints.entrySet()) {
            AnalyzerState.ModuleState moduleState = new AnalyzerState.ModuleState();
            moduleState.fingerprint = entry.getValue();
            newState.modules.put(entry.getKey(), moduleState);
        }
        for (FileMetric file : files) {
            AnalyzerState.FileState fileState = new AnalyzerState.FileState();
            fileState.fileHash = file.fileHash();
            fileState.semanticFileHash = semanticHashesByPath.get(file.path());
            fileState.cachedFileMetric = file;
            for (ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    AnalyzerState.MethodState methodState = new AnalyzerState.MethodState();
                    methodState.methodHash = method.methodHash();
                    methodState.lastCalculatedRiskScore = method.riskScore();
                    fileState.methods.put(type.name() + "#" + method.signature(), methodState);
                }
            }
            newState.files.put(file.path(), fileState);
        }
        new StatePersistenceManager().save(stateFile, newState);
    }

    private static void logCacheSummary(AnalysisReport.CacheStatistics s) {
        double seconds = s.scanTimeMillis() / 1000.0;
        System.err.printf(Locale.ROOT,
                "[STANDALONE] Incremental cache: files %d | skip file-hash %d | skip module-bulk %d | "
                        + "re-parsed %d | skip cosmetic (AST) %d | modules unchanged %d | methods re-analyzed %d | "
                        + "methods reused %d | %.1fs%n",
                s.totalFiles(), s.filesSkippedViaFileHash(), s.filesSkippedViaModuleBulk(), s.filesReanalyzed(),
                s.filesSemanticCosmeticOnly(), s.modulesUnchanged(), s.methodsReanalyzed(),
                s.methodsSkippedViaHash(), seconds);
    }

    private static final class CacheRunCounters {
        int filesSkippedViaFileHash;
        int filesSkippedViaModuleBulk;
        int filesReanalyzed;
        int filesSemanticCosmeticOnly;
        int modulesUnchanged;
        int totalMethods;
        int methodsSkippedViaHash;
        int methodsReanalyzed;
    }

    // ------------------------------------------------------------------ report-level aggregation

    private Summary buildSummary(int scanned, List<FileMetric> files, List<FileError> errors,
                                 ModuleRootIndex moduleRoots) {
        ProjectSummaryStats stats = new ProjectSummaryStats();
        int classCount = 0;
        int totalLoc = 0;
        for (FileMetric file : files) {
            totalLoc += file.codeLines();
            classCount += file.classCount();
            String moduleRoot = moduleRoots.moduleRoot(file.path());
            for (ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    stats.addMethod(moduleRoot, method);
                }
            }
        }
        return stats.toSummary(scanned, files.size(), errors.size(), classCount, totalLoc);
    }

    private List<RiskHotspot> buildHotspots(List<FileMetric> files) {
        List<RiskHotspot> hotspots = new ArrayList<>();
        for (FileMetric file : files) {
            for (ClassMetric type : file.classes()) {
                for (MethodMetric m : type.methods()) {
                    if (MethodAccessorFilter.isSimpleGetterOrSetter(m)) {
                        continue;
                    }
                    hotspots.add(new RiskHotspot(file.path(), file.packageName(), type.name(), m.signature(),
                            m.startLine(), m.riskScore(), m.riskLevel(), m.cyclomaticComplexity(), m.codeLines(),
                            m.maxNestingDepth(), m.parameterCount(), m.cognitiveComplexity(),
                            m.outboundDistinctCalls(), RiskBreakdownUtil.dominantDriver(m.riskBreakdown()),
                            m.riskFactors()));
                }
            }
        }
        hotspots.sort((a, b) -> {
            int byScore = Double.compare(b.riskScore(), a.riskScore());
            return byScore != 0 ? byScore : Integer.compare(b.cyclomaticComplexity(), a.cyclomaticComplexity());
        });
        return hotspots.size() > topN ? new ArrayList<>(hotspots.subList(0, topN)) : hotspots;
    }

    private static Thread newWorkerThread(Runnable task) {
        int id = WORKER_ID.incrementAndGet();
        return new Thread(null, task, "standalone-parser-" + id, WORKER_STACK_BYTES);
    }

    private static final int CACHE_SCHEMA_VERSION = 4;

    private static String currentCacheIdentity(RiskCalculator calculator) {
        RiskProfile profile = calculator.profile();
        return "schema=" + CACHE_SCHEMA_VERSION
                + ";profile=" + profile.profileId()
                + ";model=" + profile.modelVersion();
    }

    private static boolean cacheIdentityMatches(AnalyzerState state, RiskCalculator calculator) {
        if (state == null || state.cacheIdentity == null || state.cacheIdentity.isBlank()) {
            return false;
        }
        return state.cacheIdentity.equals(currentCacheIdentity(calculator));
    }
}
