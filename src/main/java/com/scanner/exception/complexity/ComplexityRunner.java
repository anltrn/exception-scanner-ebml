package com.scanner.exception.complexity;

import com.scanner.exception.config.Config;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Code Scanner ile bu paketteki karmaşıklık analizörü arasındaki köprü: klonlanmış bir repoyu
 * ProjectAnalyzer ile tarar, JSON ve Markdown raporlarını yazar. Paketin geri kalanı
 * github.com/kaanerdem1/java-code-analyzer kodudur (sadece paket adı değişti).
 */
public final class ComplexityRunner {

    private final Config.Complexity cfg;
    private final RiskCalculator riskCalculator;

    public ComplexityRunner(Config.Complexity cfg) {
        this.cfg = cfg;
        this.riskCalculator = RiskProfileLoader.createCalculator(cfg.riskProfile, null);
    }

    /** Repoyu tarar; raporlar jsonFile ve mdFile'a yazılır, özet döner. */
    public AnalysisReport.Summary analyze(Path sourceRoot, Path jsonFile, Path mdFile) throws IOException {
        ScanOptions defaults = ScanOptions.defaults();
        ScanOptions options = new ScanOptions(defaults.workers(), defaults.progressEvery(), cfg.include, cfg.exclude,
                cfg.fullDetail ? ScanOptions.ReportDetail.FULL : ScanOptions.ReportDetail.SUMMARY,
                true, null, false, cfg.duplicates, defaults.minDuplicateTokens(), cfg.duplicates,
                defaults.nearDuplicateAutoSkipMinFiles(), false);
        AnalysisReport report = new ProjectAnalyzer(cfg.charset, cfg.top, cfg.languageLevel, options, riskCalculator)
                .analyze(sourceRoot, new ScanRunContext());
        Files.createDirectories(jsonFile.toAbsolutePath().getParent());
        try (Writer w = Files.newBufferedWriter(jsonFile, StandardCharsets.UTF_8)) {
            AnalysisReportJsonWriter.write(report, w, true);
        }
        try (Writer w = Files.newBufferedWriter(mdFile, StandardCharsets.UTF_8)) {
            StandaloneReportMarkdown.write(report, w);
        }
        return report.summary();
    }
}
