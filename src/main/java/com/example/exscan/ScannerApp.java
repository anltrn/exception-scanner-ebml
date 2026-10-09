package com.example.exscan;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bitbucket'taki (veya yerel klasördeki) tüm Java kodunu tarayıp verilen exception sınıfının
 * kullanımlarını Excel raporu olarak çıkarır.
 *
 * Kullanım: java -jar exception-scanner-1.0.0.jar [scanner.properties] [parametreler]
 * Parametre listesi için: --help. REST API + Swagger arayüzüyle çalıştırmak için: --server
 */
public final class ScannerApp {

    private ScannerApp() { }

    public static void main(String[] args) {
        // POI'nin kullandığı log4j için ayrı bir logging kütüphanesi gerekmesin
        System.setProperty("log4j2.loggerContextFactory",
                "org.apache.logging.log4j.simple.SimpleLoggerContextFactory");
        try {
            run(args);
        } catch (Exception e) {
            System.err.println("HATA: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static final String USAGE = String.join(System.lineSeparator(),
            "Kullanım: java -jar exception-scanner-1.0.0.jar [ayar-dosyası] [parametreler]",
            "",
            "Ayar dosyası verilmezse çalışılan klasördeki scanner.properties okunur (varsa).",
            "Komut satırı parametreleri ayar dosyasındaki değerlerin üzerine yazar.",
            "",
            "  --local <klasör>     Bilgisayardaki bir projeyi veya proje klasörlerini tara",
            "  --git <adres>        GitHub/GitLab/herhangi bir git adresini klonlayıp tara (birden fazla verilebilir)",
            "  --branch <dal>       Taranacak dal (git ve Bitbucket için)",
            "  --class <sınıf>      Aranacak exception'ın tam adı (birden fazla verilebilir)",
            "  --pattern <desen>    Aranacak argüman şekli, ör. 0,STRING veya TIP_KODU=0,STRING (birden fazla verilebilir;",
            "                       verilirse dosyadaki desenler yok sayılır)",
            "  --call <çağrı>       Aranacak metot çağrısı: [nesne.]metot:argümanlar (birden fazla verilebilir)",
            "                       ör. \"put:GENERALERRORCODE.ERROR_CODE,ANY\" veya \"outBag.put:GENERALERRORCODE.ERROR_CODE,0\"",
            "  --throw-only         Sadece doğrudan throw new ... şeklindekiler",
            "  --lenient            Import/paket kontrolü yapmadan sadece sınıf adına göre eşleştir",
            "  --db                 Kullanımları PostgreSQL envanteriyle eşleştirip tabloya yaz (db.* ayarları)",
            "  --db-dry-run         Eşleştir ve raporda göster ama tabloya yazma",
            "  --db-note <metin>    Tarama kaydına açıklama ekle, ör. \"Ekim takibi\"",
            "  --ebml               Ekran/popup/region (.ebml), Jasper rapor (.dsxml) ve process (.par) tanımlarını da bul",
            "                       (--class verilmezse sadece bu envanter çıkarılır)",
            "  --out <klasör>       Raporların yazılacağı klasör",
            "  --threads <n>        Aynı anda ayrıştırılacak repo sayısı (varsayılan 4; bellek yetmezse düşürün)",
            "  --git-threads <n>    Aynı anda klonlanacak/güncellenecek repo sayısı (varsayılan 8)",
            "  --console-limit <n>  Konsola yazılacak en fazla kullanım sayısı (varsayılan 200, 0 = yazma)",
            "  --server             Komut satırı yerine REST API + Swagger arayüzü olarak çalış (port: PORT, varsayılan 8080)",
            "  --help               Bu yardımı göster",
            "",
            "Örnekler:",
            "  java -jar exception-scanner-1.0.0.jar --local C:/projeler/musteri-servisi \\",
            "       --class com.firma.framework.CustomException --pattern 0,STRING,*",
            "  java -jar exception-scanner-1.0.0.jar --git https://github.com/sahip/repo \\",
            "       --class com.firma.framework.CustomException --pattern 0,STRING,*");

    /** Komut satırı parametrelerinin ayrıştırılmış hali */
    static final class Args {
        final Properties overrides = new Properties();
        String cfgFile;
        boolean help;
    }

    private static void run(String[] args) throws Exception {
        for (String a : args) {
            if ("--server".equals(a)) {
                ScanServerApplication.start(args);
                return;
            }
        }
        Args parsed = parseArgs(args);
        if (parsed.help) {
            System.out.println(USAGE);
            return;
        }
        Path cfgPath = Paths.get(parsed.cfgFile != null ? parsed.cfgFile : "scanner.properties");
        if (parsed.cfgFile != null && !java.nio.file.Files.exists(cfgPath)) {
            throw new java.io.IOException("Ayar dosyası bulunamadı: " + cfgPath.toAbsolutePath());
        }
        if (java.nio.file.Files.exists(cfgPath)) log("Ayar dosyası: " + cfgPath.toAbsolutePath());
        Config cfg = Config.load(cfgPath, parsed.overrides);
        scan(cfg, cfg.outputDir.resolve(new SimpleDateFormat("yyyyMMdd_HHmm").format(new Date())));
    }

    /**
     * Komut satırı parametrelerini ayar değerlerine çevirir. Sunucu modu da tarama isteklerini
     * aynı parametrelere çevirip buradan geçirir.
     */
    static Args parseArgs(String[] args) {
        Args parsed = new Args();
        Properties overrides = parsed.overrides;
        String cfgFile = null;
        List<String> classes = new ArrayList<String>();
        List<String> gitUrls = new ArrayList<String>();
        int patternNo = 0;
        int callNo = 0;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h":
                case "--help":
                    parsed.help = true;
                    return parsed;
                case "--local":
                    overrides.setProperty("source", "local");
                    overrides.setProperty("local.dir", value(args, ++i, a));
                    break;
                case "--git":
                    overrides.setProperty("source", "git");
                    gitUrls.add(value(args, ++i, a));
                    break;
                case "--branch":
                    overrides.setProperty("git.branch", value(args, ++i, a));
                    break;
                case "--class":
                    classes.add(value(args, ++i, a));
                    break;
                case "--pattern":
                    patternNo++;
                    String pat = value(args, ++i, a);
                    String patType = typePrefix(pat);
                    if (patType != null) pat = pat.substring(pat.indexOf('=') + 1).trim();
                    overrides.setProperty("pattern." + patternNo + ".args", pat);
                    overrides.setProperty("pattern." + patternNo + ".name", "Desen " + patternNo + " (" + pat + ")");
                    overrides.setProperty("pattern." + patternNo + ".type",
                            patType != null ? patType : UsagePattern.typeCodeOf("ARGS " + pat));
                    break;
                case "--call": {
                    callNo++;
                    String spec = value(args, ++i, a);
                    String callType = typePrefix(spec);
                    if (callType != null) spec = spec.substring(spec.indexOf('=') + 1).trim();
                    String methodPart = spec;
                    String argPart = "*";
                    int colon = spec.indexOf(':');
                    if (colon >= 0) {
                        methodPart = spec.substring(0, colon).trim();
                        argPart = spec.substring(colon + 1).trim();
                    }
                    int dot = methodPart.lastIndexOf('.');
                    overrides.setProperty("call." + callNo + ".scope", dot >= 0 ? methodPart.substring(0, dot) : "");
                    overrides.setProperty("call." + callNo + ".method", dot >= 0 ? methodPart.substring(dot + 1) : methodPart);
                    overrides.setProperty("call." + callNo + ".args", argPart);
                    overrides.setProperty("call." + callNo + ".name", "Çağrı " + callNo + " (" + spec + ")");
                    overrides.setProperty("call." + callNo + ".type",
                            callType != null ? callType : UsagePattern.typeCodeOf("CALL " + methodPart));
                    break;
                }
                case "--throw-only":
                    overrides.setProperty("scan.throw.only", "true");
                    break;
                case "--db":
                    overrides.setProperty("db.enabled", "true");
                    break;
                case "--db-dry-run":
                    overrides.setProperty("db.enabled", "true");
                    overrides.setProperty("db.dry.run", "true");
                    break;
                case "--db-note":
                    overrides.setProperty("db.note", value(args, ++i, a));
                    break;
                case "--ebml":
                    overrides.setProperty("ebml.enabled", "true");
                    break;
                case "--lenient":
                    overrides.setProperty("match.lenient", "true");
                    break;
                case "--console-limit":
                    overrides.setProperty("console.limit", value(args, ++i, a));
                    break;
                case "--threads":
                    overrides.setProperty("threads", value(args, ++i, a));
                    break;
                case "--git-threads":
                    overrides.setProperty("git.threads", value(args, ++i, a));
                    break;
                case "--out":
                    overrides.setProperty("output.dir", value(args, ++i, a));
                    break;
                default:
                    if (a.startsWith("--")) throw new IllegalArgumentException("Bilinmeyen parametre: " + a + "\n\n" + USAGE);
                    cfgFile = a;
            }
        }
        if (!classes.isEmpty()) overrides.setProperty("exception.classes", String.join(",", classes));
        if (!gitUrls.isEmpty()) overrides.setProperty("git.urls", String.join(",", gitUrls));
        parsed.cfgFile = cfgFile;
        return parsed;
    }

    /**
     * Taramayı yapar ve raporları outDir klasörüne yazar. Hem komut satırı hem sunucu modu kullanır.
     *
     * @return özet sayılar (repo, Java dosyası, kullanım, hata sayısı ve rapor klasörü)
     */
    static Map<String, Object> scan(final Config cfg, Path outDir) throws Exception {
        Map<String, Object> summary = new java.util.LinkedHashMap<String, Object>();
        log("Kaynak: " + cfg.source + " | Aranan sınıflar: "
                + (cfg.exceptionClasses.isEmpty() ? "-" : String.join(", ", cfg.exceptionClasses)));
        if (!cfg.exceptionClasses.isEmpty()) {
            if (cfg.patterns.isEmpty()) {
                log("Desen tanımlı değil: sınıfın tüm kullanımları raporlanacak.");
            } else {
                for (UsagePattern p : cfg.patterns) log("Desen: " + p.name + " -> (" + p.argsText + ")");
            }
        }
        for (CallPattern c : cfg.callPatterns) log("Çağrı: " + c.name + " -> " + c.spec());

        List<RepoInfo> all;
        if (cfg.source == Config.Source.LOCAL) all = LocalRepos.list(cfg);
        else if (cfg.source == Config.Source.GIT) all = GitUrlRepos.list(cfg);
        else all = new BitbucketClient(cfg).listRepositories();
        List<RepoInfo> repos = new ArrayList<RepoInfo>();
        for (RepoInfo r : all) {
            if (cfg.isExcluded(r)) continue;
            repos.add(r);
        }
        log(repos.size() + " repo taranacak.");
        summary.put("repositories", repos.size());
        summary.put("excludedRepositories", all.size() - repos.size());
        if (repos.isEmpty()) return summary;

        final GitRunner git = new GitRunner(cfg);
        final JavaSourceScanner scanner = new JavaSourceScanner(cfg);
        final AtomicInteger done = new AtomicInteger();
        final int total = repos.size();

        // Klonlama (ağ) ve ayrıştırma (işlemci/bellek) ayrı havuzlarda: repolar klonlandıkça taranır.
        // Repolar projeler arasında sırayla dağıtılır ki tüm projeler aynı anda ilerlesin.
        log(String.format("Paralellik: %d klonlama, %d tarama işçisi; %d proje aynı anda", cfg.gitThreads, cfg.threads,
                countProjects(repos)));
        ExecutorService clonePool = Executors.newFixedThreadPool(cfg.gitThreads);
        ExecutorService scanPool = Executors.newFixedThreadPool(cfg.threads);
        List<CompletableFuture<RepoResult>> futures =
                new ArrayList<CompletableFuture<RepoResult>>(java.util.Collections.nCopies(total, null));
        try {
            for (final int idx : interleaveByProject(repos)) {
                final RepoInfo repo = repos.get(idx);
                futures.set(idx, CompletableFuture
                        .supplyAsync(() -> checkout(repo, cfg, git), clonePool)
                        .thenApplyAsync(failed -> failed != null ? failed : scanRepo(repo, cfg, scanner), scanPool)
                        .thenApply(r -> {
                            String ebmlInfo = !cfg.ebml.enabled ? "" : String.format(", %d ekran, %d popup, %d region, %d rapor, %d process",
                                    r.count(EbmlFile.Kind.SCREEN), r.count(EbmlFile.Kind.POPUP), r.count(EbmlFile.Kind.REGION), r.count(EbmlFile.Kind.REPORT),
                                    r.count(EbmlFile.Kind.PROCESS));
                            log(String.format("[%d/%d] %s (%s): %d Java dosyası, %d eşleşme%s%s (klon %s, tarama %s)",
                                    done.incrementAndGet(), total, repo.id(), repo.localPath, r.javaFiles, r.usages.size(),
                                    ebmlInfo, r.errors.isEmpty() ? "" : ", " + r.errors.size() + " hata",
                                    seconds(repo.cloneMillis), seconds(repo.scanMillis)));
                            return r;
                        }));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
        } finally {
            clonePool.shutdownNow();
            scanPool.shutdownNow();
        }
        List<RepoResult> results = new ArrayList<RepoResult>();
        for (CompletableFuture<RepoResult> f : futures) results.add(f.join());
        long cloneTotal = 0, scanTotal = 0;
        RepoInfo slowest = null;
        for (RepoInfo r : repos) {
            cloneTotal += r.cloneMillis;
            scanTotal += r.scanMillis;
            if (slowest == null || r.cloneMillis > slowest.cloneMillis) slowest = r;
        }
        log(String.format("Repolar bitti: toplam klonlama %s, toplam tarama %s (işçiler paralel çalıştığı için gerçek "
                + "süre daha kısa); en uzun klon %s (%s)", seconds(cloneTotal), seconds(scanTotal),
                slowest == null ? "-" : slowest.id(), slowest == null ? "-" : seconds(slowest.cloneMillis)));
        summary.put("cloneSeconds", cloneTotal / 1000);
        summary.put("scanSeconds", scanTotal / 1000);

        // Veritabanı eşleştirmesi raporlardan önce yapılır ki Excel'de de class_id / method_id görünsün
        PostgresExporter pg = cfg.db.enabled ? resolveInDatabase(cfg, results) : null;

        Date now = new Date();
        ExcelReportWriter writer = new ExcelReportWriter(cfg, now);

        Path master = outDir.resolve("exception_scan_report.xlsx");
        writer.write(master, "Exception Kullanım Taraması - Tüm Projeler", results);
        log("Ana rapor: " + master.toAbsolutePath());
        Path csv = outDir.resolve("usages.csv");
        CsvReportWriter.write(csv, results);

        if (cfg.perProjectReports) {
            Map<String, List<RepoResult>> byProject = new TreeMap<String, List<RepoResult>>();
            for (RepoResult r : results) {
                List<RepoResult> list = byProject.get(r.repo.projectKey);
                if (list == null) {
                    list = new ArrayList<RepoResult>();
                    byProject.put(r.repo.projectKey, list);
                }
                list.add(r);
            }
            for (Map.Entry<String, List<RepoResult>> en : byProject.entrySet()) {
                String name = en.getValue().get(0).repo.projectName;
                Path file = outDir.resolve("projects").resolve("report_" + GitRunner.safe(en.getKey()) + ".xlsx");
                writer.write(file, "Exception Kullanım Taraması - " + name + " (" + en.getKey() + ")", en.getValue());
            }
            log(byProject.size() + " proje raporu: " + outDir.resolve("projects").toAbsolutePath());
        }

        printFindings(results, cfg.consoleLimit);
        log("Detaylı Excel raporu: " + master.toAbsolutePath() + "  (detaylar 'Kullanımlar' sayfasında)");
        log("CSV: " + csv.toAbsolutePath());

        if (pg != null) {
            try {
                if (cfg.db.dryRun) {
                    log("Veritabanı: deneme modu (--db-dry-run), kayıt yapılmadı.");
                } else {
                    PostgresExporter.InsertResult ir = pg.insert(results);
                    log("Veritabanı: " + ir.inserted + " kullanım " + cfg.db.usageTable + " tablosuna yazıldı"
                            + " (scan_run_id=" + ir.runId + ")"
                            + (ir.skipped > 0 ? ", sınıfı bulunamayan " + ir.skipped + " kullanım atlandı" : "") + ".");
                    if (cfg.ebml.enabled) {
                        log("Veritabanı: " + ir.screens + " ekran, " + ir.popups + " popup, " + ir.regions + " region, " + ir.reports
                                + " Jasper rapor, " + ir.processes + " process yazıldı" + (ir.ebmlSkipped > 0
                                ? ", projesi bulunamayan " + ir.ebmlSkipped + " dosya atlandı" : "") + ".");
                        for (String line : ir.existing) log("project_id güncellemesi: " + line);
                    }
                }
            } catch (Exception e) {
                log("HATA: kullanımlar veritabanına yazılamadı, hiçbir kayıt eklenmedi: " + e.getMessage());
            } finally {
                pg.close();
            }
        }

        long usages = 0, errors = 0, javaFiles = 0, kotlin = 0, mentions = 0, parseFailures = 0;
        for (RepoResult r : results) {
            usages += r.usages.size();
            errors += r.errors.size();
            javaFiles += r.javaFiles;
            kotlin += r.kotlinFiles;
            mentions += r.filesMentioningTarget;
            parseFailures += r.parseFailures;
        }
        log(String.format("Bitti. %d Java dosyası tarandı, %d eşleşen kullanım bulundu. Tarama hatası: %d",
                javaFiles, usages, errors));
        printRepoErrors(results);
        boolean javaSearch = !cfg.exceptionClasses.isEmpty() || !cfg.callPatterns.isEmpty();
        if (javaSearch && usages == 0) diagnose(cfg, results, javaFiles, kotlin, mentions, parseFailures);
        if (cfg.ebml.enabled) {
            long sc = 0, pu = 0, rg = 0, rp = 0, pr = 0, un = 0;
            for (RepoResult r : results) {
                sc += r.count(EbmlFile.Kind.SCREEN);
                pu += r.count(EbmlFile.Kind.POPUP);
                rg += r.count(EbmlFile.Kind.REGION);
                rp += r.count(EbmlFile.Kind.REPORT);
                pr += r.count(EbmlFile.Kind.PROCESS);
                un += r.count(EbmlFile.Kind.UNCLASSIFIED);
            }
            log(String.format("EBML: %d ekran, %d popup, %d region, %d Jasper rapor, %d process bulundu%s.", sc, pu, rg, rp, pr,
                    un > 0 ? "; " + un + " dosya beklenen paketlerde değil (raporda 'EBML Dosyaları' sayfası)" : ""));
        }
        summary.put("javaFiles", javaFiles);
        summary.put("usages", usages);
        summary.put("errors", errors);
        summary.put("reportDir", outDir.toAbsolutePath().toString());
        return summary;
    }

    /**
     * Kullanımları PostgreSQL'deki sınıf ve metot kayıtlarıyla eşleştirir. Bağlantı kurulamazsa
     * tarama boşa gitmesin diye raporlar yine yazılır; sadece veritabanı adımı atlanır.
     */
    private static PostgresExporter resolveInDatabase(Config cfg, List<RepoResult> results) {
        List<UsageFinding> all = new ArrayList<UsageFinding>();
        for (RepoResult r : results) all.addAll(r.usages);
        PostgresExporter pg = null;
        try {
            pg = new PostgresExporter(cfg);
            new DbMatcher(cfg.db.caseInsensitive, cfg.db.constructorNames).resolveAll(all, pg);
            if (cfg.ebml.enabled) {
                List<EbmlFile> ebmlFiles = new ArrayList<EbmlFile>();
                for (RepoResult r : results) ebmlFiles.addAll(r.ebmlFiles);
                pg.resolveProjects(ebmlFiles);
                Map<String, Integer> pc = new TreeMap<String, Integer>();
                for (EbmlFile e : ebmlFiles) {
                    if (e.kind == EbmlFile.Kind.UNCLASSIFIED) continue;
                    Integer c = pc.get(e.projectMatch);
                    pc.put(e.projectMatch, c == null ? 1 : c + 1);
                }
                log("Proje eşleştirmesi (" + cfg.ebml.projectTable + "): "
                        + (pc.isEmpty() ? "dosya yok" : pc.toString()));
            }
            Map<String, Integer> counts = new TreeMap<String, Integer>();
            for (UsageFinding u : all) {
                Integer c = counts.get(u.matchStatus);
                counts.put(u.matchStatus, c == null ? 1 : c + 1);
            }
            log("Veritabanı eşleştirmesi: " + (counts.isEmpty() ? "eşleştirilecek kullanım yok" : counts.toString()));
            return pg;
        } catch (Exception e) {
            log("HATA: veritabanı eşleştirmesi yapılamadı, raporlar eşleştirmesiz yazılacak: " + e.getMessage());
            if (pg != null) pg.close();
            for (UsageFinding u : all) {
                u.matchStatus = "";
                u.dbClassId = null;
                u.dbMethodId = null;
            }
            for (RepoResult r : results) {
                for (EbmlFile ef : r.ebmlFiles) {
                    ef.projectId = null;
                    ef.projectMatch = "";
                }
            }
            return null;
        }
    }

    /** Bulunan kullanımları konsola yazar; çok fazlaysa ilk consoleLimit tanesini. */
    private static void printFindings(List<RepoResult> results, int limit) {
        int total = 0;
        for (RepoResult r : results) total += r.usages.size();
        if (total == 0 || limit <= 0) return;

        System.out.println();
        System.out.println("=== Bulunan kullanımlar (" + total + ") ===");
        int printed = 0;
        for (RepoResult r : results) {
            if (r.usages.isEmpty()) continue;
            System.out.println();
            System.out.println("[" + r.repo.id() + "]");
            for (UsageFinding u : r.usages) {
                if (printed >= limit) break;
                printed++;
                String member = u.member.isEmpty() ? "" : "#" + u.member;
                System.out.println("  " + u.file + ":" + u.line);
                System.out.println("      Sınıf/Metot : " + u.className + member);
                if (!u.message.isEmpty()) System.out.println("      Mesaj       : " + u.message);
                System.out.println("      Kod         : " + BitbucketClient.abbreviate(u.expression, 160));
                if (u.link != null) System.out.println("      Bağlantı    : " + u.link);
            }
            if (printed >= limit) break;
        }
        if (total > printed) {
            System.out.println();
            System.out.println("  ... ve " + (total - printed) + " kullanım daha. Tamamı Excel raporunda.");
        }
        System.out.println();
    }

    /** Klonlama gibi repo seviyesindeki hataları konsola yazar (dosya bazlı ayrıştırma hataları raporda). */
    private static void printRepoErrors(List<RepoResult> results) {
        for (RepoResult r : results) {
            for (ScanError e : r.errors) {
                if ("PARSE".equals(e.stage) || "OKUMA".equals(e.stage) || "ATLANDI".equals(e.stage)) continue;
                log("HATA [" + r.repo.id() + "] " + e.stage + ": " + e.message);
                if ("BELLEK".equals(e.stage)) continue;
                if (e.message.contains("xcrun") || e.message.contains("developer path")) {
                    log("   macOS'ta git için Xcode komut satırı araçları eksik. Kurmak için: xcode-select --install");
                } else if (e.message.contains("Cannot run program \"git\"") || e.message.contains("error=2")) {
                    log("   git bulunamadı. git kurulu ve PATH'te olmalı (macOS: xcode-select --install veya brew install git).");
                } else if (e.message.contains("not found") || e.message.contains("Repository not found")) {
                    log("   Repo bulunamadı ya da erişim yetkiniz yok. Adresi ve (özel repoysa) git oturumunuzu kontrol edin.");
                }
            }
        }
    }

    /** Hiç sonuç çıkmadığında olası sebebi açıklar. */
    private static void diagnose(Config cfg, List<RepoResult> results, long javaFiles, long kotlin,
                                 long mentions, long parseFailures) {
        int repoFailures = 0;
        for (RepoResult r : results) {
            for (ScanError e : r.errors) {
                if (!"PARSE".equals(e.stage) && !"OKUMA".equals(e.stage) && !"ATLANDI".equals(e.stage)) repoFailures++;
            }
        }
        List<String> simple = new ArrayList<String>();
        for (String c : cfg.exceptionClasses) simple.add(TypeMatcher.simpleName(c));
        String names = String.join(", ", simple);
        log("--- Neden sonuç çıkmadı? ---");
        if (javaFiles == 0 && repoFailures > 0) {
            log("Repo indirilemediği için taranacak dosya yok. Yukarıdaki HATA satırlarına bakın.");
            return;
        }
        if (javaFiles > 0 && cfg.exceptionClasses.isEmpty()) {
            log("Çağrı desenlerine uyan kullanım yok. Metot adını ve argümanları kontrol edin; önce \"put:*\" "
                    + "gibi geniş bir desenle çalıştırıp Kod sütunundan gerçek kullanım şeklini görebilirsiniz.");
            return;
        }
        if (javaFiles == 0) {
            log("Hiç .java dosyası bulunamadı. Klasör yolunu kontrol edin"
                    + (kotlin > 0 ? "; klasörde " + kotlin + " Kotlin dosyası var ama tarayıcı sadece Java'yı tarar." : "."));
            return;
        }
        if (mentions == 0) {
            log(names + " adı hiçbir Java dosyasında geçmiyor. Sınıf adının yazımını kontrol edin.");
            if (kotlin > 0) log("Klasörde " + kotlin + " Kotlin dosyası var; kullanım oradaysa tarayıcı onu görmez.");
            return;
        }
        log(names + " adı " + mentions + " Java dosyasında geçiyor ama aranan şekle uyan kullanım yok. Olası sebepler:");
        if (!cfg.patterns.isEmpty()) {
            log(" - Desenler argümanların sayısını ve türünü birlikte kontrol eder. Örneğin HttpStatus.BAD_REQUEST "
                    + "gibi bir sabit STRING değil CONST sayılır; ANY ise tam olarak 1 argüman demektir.");
            log("   Kullanımların gerçek şeklini görmek için --pattern \"*\" ile çalıştırıp Kod sütununa bakın.");
        }
        if (cfg.throwOnly) log(" - --throw-only açık: değişkene atanıp sonra fırlatılanlar sayılmıyor.");
        log(" - Paket adı farklı olabilir: --class değeri, sınıf dosyasının başındaki package satırı + sınıf adı olmalı "
                + "(" + String.join(", ", cfg.exceptionClasses) + "). Sadece adla eşleştirmek için --lenient deneyin.");
        if (parseFailures > 0) log(" - " + parseFailures + " dosya ayrıştırılamadı; raporda 'Tarama Hataları' sayfasına bakın.");
    }

    /** "KOD0_METIN=0,STRING,*" gibi değerlerdeki tip kodu önekini döndürür, yoksa null */
    private static String typePrefix(String spec) {
        int eq = spec.indexOf('=');
        if (eq <= 0) return null;
        String left = spec.substring(0, eq).trim();
        return left.matches("[A-Za-z][A-Za-z0-9_]*") ? UsagePattern.checkType(left) : null;
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length || args[i].startsWith("--")) {
            throw new IllegalArgumentException(option + " parametresi bir değer bekliyor");
        }
        return args[i];
    }

    /** Repoyu klonlar / günceller. Başarılıysa null, değilse hatayı taşıyan sonucu döndürür. */
    private static RepoResult checkout(RepoInfo repo, Config cfg, GitRunner git) {
        long t0 = System.nanoTime();
        try {
            if (cfg.source == Config.Source.LOCAL) git.readHeadQuietly(repo);
            else git.checkout(repo);
            return null;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            RepoResult r = new RepoResult(repo);
            r.errors.add(new ScanError(repo, "KLONLAMA", "", e.getMessage()));
            return r;
        } finally {
            repo.cloneMillis = (System.nanoTime() - t0) / 1_000_000;
        }
    }

    private static RepoResult scanRepo(RepoInfo repo, Config cfg, JavaSourceScanner scanner) {
        long t0 = System.nanoTime();
        try {
            boolean javaSearch = !cfg.exceptionClasses.isEmpty() || !cfg.callPatterns.isEmpty();
            RepoResult result = javaSearch ? scanner.scan(repo) : new RepoResult(repo);
            if (cfg.ebml.enabled) new EbmlScanner(cfg).scan(repo, result);
            return result;
        } catch (RuntimeException e) {
            RepoResult r = new RepoResult(repo);
            r.errors.add(new ScanError(repo, "TARAMA", "", String.valueOf(e)));
            return r;
        } catch (OutOfMemoryError e) {
            // Tek bir çok büyük repo tüm taramayı durdurmasın; o reponun verisi serbest kalınca devam edilir
            RepoResult r = new RepoResult(repo);
            r.errors.add(new ScanError(repo, "BELLEK", "", "Bellek yetmedi. java -Xmx4g -jar ... ile daha fazla "
                    + "bellek verin veya --threads 1 ile tekrar deneyin."));
            return r;
        } finally {
            repo.scanMillis = (System.nanoTime() - t0) / 1_000_000;
        }
    }

    private static String seconds(long millis) {
        long sec = Math.round(millis / 1000.0);
        return sec >= 60 ? (sec / 60) + " dk " + (sec % 60) + " sn" : sec + " sn";
    }

    /**
     * Repoların işlenme sırası: projeler arasında sırayla (A1, B1, C1, A2, B2, ...). Böylece bir projenin
     * çok sayıda reposu diğer projeleri bekletmez. Dönen değerler repos listesindeki indekslerdir.
     */
    static List<Integer> interleaveByProject(List<RepoInfo> repos) {
        Map<String, java.util.Deque<Integer>> byProject = new java.util.LinkedHashMap<String, java.util.Deque<Integer>>();
        for (int i = 0; i < repos.size(); i++) {
            byProject.computeIfAbsent(String.valueOf(repos.get(i).projectKey), k -> new java.util.ArrayDeque<Integer>()).add(i);
        }
        List<Integer> order = new ArrayList<Integer>(repos.size());
        while (order.size() < repos.size()) {
            for (java.util.Deque<Integer> q : byProject.values()) {
                if (!q.isEmpty()) order.add(q.poll());
            }
        }
        return order;
    }

    private static int countProjects(List<RepoInfo> repos) {
        java.util.Set<String> keys = new java.util.HashSet<String>();
        for (RepoInfo r : repos) keys.add(String.valueOf(r.projectKey));
        return keys.size();
    }

    /** Sunucu modunda tarama loglarını işin log dosyasına da yazmak için (aynı anda tek tarama çalışır) */
    static volatile java.util.function.Consumer<String> logListener;

    static synchronized void log(String msg) {
        String line = "[" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "] " + msg;
        System.out.println(line);
        java.util.function.Consumer<String> l = logListener;
        if (l != null) l.accept(line);
    }
}
