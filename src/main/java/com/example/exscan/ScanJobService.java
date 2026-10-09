package com.example.exscan;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Taramaları sırayla (aynı anda tek tarama) arka planda çalıştırır. Klonlanan repolar work.dir'de
 * önbellek olarak kalır, her taramanın raporu output.dir/&lt;tarama numarası&gt; klasörüne yazılır.
 */
@Service
class ScanJobService {

    static final String JOB_FILE = "job.json";
    static final String LOG_FILE = "scan.log";
    static final String MASTER_REPORT = "exception_scan_report.xlsx";
    private static final Pattern ID = Pattern.compile("[0-9]{8}-[0-9]{6}-[0-9a-f]{4}");
    private static final Pattern SECRET = Pattern.compile("(?i).*(token|password|secret|key).*");
    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final ServerSettings settings;
    private final ObjectMapper json;
    private final Map<String, ScanJob> jobs = new ConcurrentHashMap<String, ScanJob>();
    private final Map<String, Future<?>> pending = new ConcurrentHashMap<String, Future<?>>();
    // Tarayıcının logları ve klon klasörü paylaşıldığı için taramalar sırayla çalışır
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scan-runner");
        t.setDaemon(true);
        return t;
    });

    ScanJobService(ServerSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    /** Önceki taramaları rapor klasöründen yükler; yarıda kalanları başarısız sayar. */
    @PostConstruct
    void loadPrevious() throws IOException {
        Path out = outputDir();
        Files.createDirectories(out);
        Files.createDirectories(Paths.get(settings.getWorkDir()));
        try (Stream<Path> dirs = Files.list(out)) {
            for (Path dir : dirs.filter(d -> Files.isRegularFile(d.resolve(JOB_FILE))).collect(Collectors.toList())) {
                try {
                    ScanJob job = json.readValue(dir.resolve(JOB_FILE).toFile(), ScanJob.class);
                    if (job.id == null || !job.id.equals(dir.getFileName().toString())) continue;
                    if (job.status == ScanJob.Status.QUEUED || job.status == ScanJob.Status.RUNNING) {
                        job.status = ScanJob.Status.FAILED;
                        job.error = "Sunucu tarama sırasında yeniden başladı";
                        job.finishedAt = Instant.now();
                        save(job);
                    }
                    jobs.put(job.id, job);
                } catch (IOException e) {
                    ScannerApp.log("Uyarı: " + dir.resolve(JOB_FILE) + " okunamadı: " + e.getMessage());
                }
            }
        }
        ScannerApp.log("Sunucu modu: ayar dosyası " + baseConfig().toAbsolutePath() + ", raporlar "
                + out.toAbsolutePath() + ", " + jobs.size() + " önceki tarama");
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }

    /** İsteği doğrular (hatalıysa IllegalArgumentException) ve sıraya koyar. */
    synchronized ScanJob submit(ScanRequest request, ScanJob.Trigger trigger) throws IOException {
        if (request == null) request = new ScanRequest(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        Properties overrides = overrides(request);
        Config cfg = Config.load(baseConfig(), overrides); // ayar hatası varsa hemen 400 dönsün

        ScanJob job = new ScanJob();
        job.createdAt = Instant.now();
        job.id = ID_TIME.format(job.createdAt) + "-" + UUID.randomUUID().toString().substring(0, 4);
        job.status = ScanJob.Status.QUEUED;
        job.trigger = trigger;
        job.request = request;
        Files.createDirectories(jobDir(job.id));
        save(job);
        jobs.put(job.id, job);
        pending.put(job.id, runner.submit(() -> execute(job, cfg)));
        return job;
    }

    private void execute(ScanJob job, Config cfg) {
        synchronized (this) { // submit / delete ile yarışmasın
            pending.remove(job.id);
            if (!jobs.containsKey(job.id)) return;
            job.status = ScanJob.Status.RUNNING;
        }
        Path dir = jobDir(job.id);
        try (BufferedWriter log = Files.newBufferedWriter(dir.resolve(LOG_FILE), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            ScannerApp.logListener = line -> {
                try {
                    log.write(line);
                    log.newLine();
                    log.flush();
                } catch (IOException ignored) {
                    // log dosyasına yazılamasa da tarama sürer; satır pod loglarında da var
                }
            };
            job.startedAt = Instant.now();
            save(job);
            ScannerApp.log("Tarama başladı: " + job.id);
            job.summary = ScannerApp.scan(cfg, dir);
            job.status = ScanJob.Status.SUCCEEDED;
        } catch (Throwable e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            ScannerApp.log("HATA: tarama " + job.id + " başarısız: " + e);
            job.status = ScanJob.Status.FAILED;
            job.error = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
        } finally {
            ScannerApp.logListener = null;
            job.finishedAt = Instant.now();
            try {
                save(job);
            } catch (IOException e) {
                ScannerApp.log("Uyarı: " + job.id + " durumu kaydedilemedi: " + e.getMessage());
            }
        }
    }

    /** Sırada bekleyen veya çalışan, verilen kaynaktan başlatılmış tarama var mı */
    boolean hasActive(ScanJob.Trigger trigger) {
        for (ScanJob j : jobs.values()) {
            if (j.trigger == trigger && (j.status == ScanJob.Status.QUEUED || j.status == ScanJob.Status.RUNNING)) {
                return true;
            }
        }
        return false;
    }

    List<ScanJob> list() {
        List<ScanJob> all = new ArrayList<ScanJob>(jobs.values());
        all.sort(Comparator.comparing((ScanJob j) -> j.createdAt).reversed());
        return all;
    }

    ScanJob get(String id) {
        ScanJob job = id == null ? null : jobs.get(id);
        if (job == null) throw new NoSuchElementException("Tarama bulunamadı: " + id);
        return job;
    }

    /** Sıradaki taramayı iptal eder veya biten taramayı raporlarıyla siler. Çalışan tarama silinemez. */
    synchronized void delete(String id) throws IOException {
        ScanJob job = get(id);
        if (job.status == ScanJob.Status.RUNNING) {
            throw new IllegalStateException("Çalışan tarama silinemez, bitmesini bekleyin: " + id);
        }
        Future<?> f = pending.remove(id);
        if (f != null) f.cancel(false);
        jobs.remove(id);
        deleteRecursively(jobDir(id));
    }

    /** Taramanın rapor klasöründeki dosyalar (klasöre göre göreli yol ve boyut) */
    List<ReportFile> files(String id) throws IOException {
        Path dir = jobDir(get(id).id);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().equals(JOB_FILE))
                    .map(f -> new ReportFile(dir.relativize(f).toString().replace('\\', '/'), size(f)))
                    .sorted(Comparator.comparing(ReportFile::path))
                    .collect(Collectors.toList());
        }
    }

    /** Rapor klasörü içindeki bir dosya; klasör dışına çıkan yollar reddedilir. */
    Path file(String id, String relative) {
        Path dir = jobDir(get(id).id).toAbsolutePath().normalize();
        Path f = dir.resolve(relative == null ? "" : relative).normalize();
        if (!f.startsWith(dir) || f.equals(dir) || f.getFileName().toString().equals(JOB_FILE)
                || !Files.isRegularFile(f)) {
            throw new NoSuchElementException("Dosya bulunamadı: " + relative);
        }
        return f;
    }

    /** Sunucudaki temel ayarlar; token / şifre gibi değerler maskelenir */
    Map<String, String> effectiveSettings() throws IOException {
        Path base = baseConfig();
        Properties p = Files.exists(base) ? Config.readProperties(base) : new Properties();
        p.setProperty("work.dir", settings.getWorkDir());
        p.setProperty("output.dir", settings.getOutputDir());
        Map<String, String> out = new java.util.TreeMap<String, String>();
        for (String k : p.stringPropertyNames()) {
            String v = p.getProperty(k);
            out.put(k, SECRET.matcher(k).matches() && !v.isEmpty() ? "******" : v);
        }
        if (System.getenv("BITBUCKET_TOKEN") != null) out.put("bitbucket.token", "****** (BITBUCKET_TOKEN)");
        if (System.getenv("DB_PASSWORD") != null) out.put("db.password", "****** (DB_PASSWORD)");
        return out;
    }

    private Properties overrides(ScanRequest request) {
        Properties p = request.toOverrides();
        p.setProperty("work.dir", settings.getWorkDir());
        p.setProperty("output.dir", settings.getOutputDir());
        p.setProperty("console.limit", "0"); // bulunanlar raporda; pod loguna dökülmesin
        return p;
    }

    private Path baseConfig() {
        return Paths.get(settings.getConfig());
    }

    private Path outputDir() {
        return Paths.get(settings.getOutputDir());
    }

    private Path jobDir(String id) {
        if (!ID.matcher(id).matches()) throw new NoSuchElementException("Tarama bulunamadı: " + id);
        return outputDir().resolve(id);
    }

    private void save(ScanJob job) throws IOException {
        Path dir = jobDir(job.id);
        Path tmp = dir.resolve(JOB_FILE + ".tmp");
        json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), job);
        Files.move(tmp, dir.resolve(JOB_FILE), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static long size(Path f) {
        try {
            return Files.size(f);
        } catch (IOException e) {
            return -1;
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(p);
        }
    }

    /** Rapor klasöründeki bir dosya */
    record ReportFile(String path, long size) { }
}
