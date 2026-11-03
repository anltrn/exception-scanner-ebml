package com.scanner.complexity;

import com.scanner.exception.config.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Code Scanner ile bu paketteki karmaşıklık analizörü arasındaki köprü: klonlanmış bir repoyu tarar, JSON ve
 * Markdown raporlarını yazar. Paketin geri kalanı github.com/kaanerdem1/java-code-analyzer (cleanup/slim-analyzer)
 * kodudur; sadece paket adı değişti.
 *
 * İki tarama yolu vardır (complexity.engine):
 *   PARSER : JavaParser ile tam metrik (ProjectAnalyzer), varsayılan risk profili enterprise-java
 *   PMD    : sadece PMD (ruleset + PMD AST / JavaMetrics, PmdNativeProjectScanner), profil enterprise-java-pmd
 */
public final class ComplexityRunner {

    public enum Engine { PARSER, PMD, BOTH }

    /** Bir yolun sonucu */
    public record Result(String engine, AnalysisReport.Summary summary, Path json, Path markdown) { }

    private static final String RULESET_RESOURCE = "/config/pmd-ruleset-complexity.xml";

    private final Config.Complexity cfg;

    public ComplexityRunner(Config.Complexity cfg) {
        this.cfg = cfg;
    }

    /**
     * Repoyu complexity.engine'deki yol(lar)la tarar. Raporlar baseName + ".json/.md" (PMD için
     * baseName + "-pmd.json/.md" ve "-pmd-violations.xml") olarak yazılır.
     */
    public java.util.List<Result> analyze(Path sourceRoot, Path baseName) throws IOException {
        java.util.List<Result> out = new java.util.ArrayList<Result>();
        if (cfg.engine != Engine.PMD) out.add(parser(sourceRoot, baseName));
        if (cfg.engine != Engine.PARSER) out.add(pmd(sourceRoot, baseName));
        return out;
    }

    private Result parser(Path sourceRoot, Path baseName) throws IOException {
        RiskCalculator risk = RiskProfileLoader.createCalculator(profile("enterprise-java"), null);
        AnalysisReport report = new ProjectAnalyzer(cfg.charset, cfg.top, cfg.languageLevel, options(cfg.fullDetail), risk)
                .analyze(sourceRoot, new ScanRunContext());
        return write("parser", report, sibling(baseName, ".json"), sibling(baseName, ".md"));
    }

    private Result pmd(Path sourceRoot, Path baseName) throws IOException {
        RiskCalculator risk = RiskProfileLoader.createCalculator(profile("enterprise-java-pmd"), null);
        Path ruleset = Files.createTempFile("pmd-ruleset-", ".xml");
        try {
            try (InputStream in = ComplexityRunner.class.getResourceAsStream(RULESET_RESOURCE)) {
                if (in == null) throw new IOException("PMD ruleset bulunamadı: " + RULESET_RESOURCE);
                Files.copy(in, ruleset, StandardCopyOption.REPLACE_EXISTING);
            }
            Path violations = sibling(baseName, "-pmd-violations.xml");
            Files.createDirectories(violations.toAbsolutePath().getParent());
            // PMD yolu summary modunda metotları özet hesaplanmadan önce atıyor (metot sayısı 0 çıkıyor); hep full
            AnalysisReport report = new PmdNativeProjectScanner(cfg.charset, cfg.languageLevel, options(true), cfg.top,
                    risk, ruleset, violations, true).scan(sourceRoot);
            return write("pmd", report, sibling(baseName, "-pmd.json"), sibling(baseName, "-pmd.md"));
        } finally {
            Files.deleteIfExists(ruleset);
        }
    }

    private Result write(String engine, AnalysisReport report, Path json, Path md) throws IOException {
        Files.createDirectories(json.toAbsolutePath().getParent());
        try (Writer w = Files.newBufferedWriter(json, StandardCharsets.UTF_8)) {
            AnalysisReportJsonWriter.write(report, w, true);
        }
        if (cfg.markdown) {
            try (Writer w = Files.newBufferedWriter(md, StandardCharsets.UTF_8)) {
                StandaloneReportMarkdown.write(report, w);
            }
        }
        return new Result(engine, report.summary(), json, cfg.markdown ? md : null);
    }

    private ScanOptions options(boolean fullDetail) {
        ScanOptions d = ScanOptions.defaults();
        return new ScanOptions(d.workers(), d.progressEvery(), cfg.include, cfg.exclude,
                fullDetail ? ScanOptions.ReportDetail.FULL : ScanOptions.ReportDetail.SUMMARY,
                true, null, false, cfg.duplicates, d.minDuplicateTokens(), cfg.duplicates,
                d.nearDuplicateAutoSkipMinFiles(), false, d.pmdMetricAlignment());
    }

    /** complexity.risk.profile boşsa yolun kendi varsayılan profili */
    private String profile(String fallback) {
        return cfg.riskProfile.isEmpty() ? fallback : cfg.riskProfile;
    }

    private static Path sibling(Path baseName, String suffix) {
        return baseName.resolveSibling(baseName.getFileName() + suffix);
    }
}
