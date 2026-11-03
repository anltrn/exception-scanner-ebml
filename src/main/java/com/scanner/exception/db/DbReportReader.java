package com.scanner.exception.db;

import com.scanner.exception.config.Config;
import com.scanner.exception.model.CatchFinding;
import com.scanner.exception.model.Located;
import com.scanner.exception.model.RepoInfo;
import com.scanner.exception.model.RepoResult;
import com.scanner.exception.model.ScanError;
import com.scanner.exception.model.SubclassFinding;
import com.scanner.exception.model.UsageFinding;
import com.scanner.exception.scan.JavaSourceScanner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Bir taramanın proje veya repo bazlı sonuçlarını veritabanından okur; Excel raporu tarama bittikten sonra
 * istendiğinde bu sonuçlardan üretilir. Tabloda tutulmayan iki bilgi raporda boş kalır: kullanımların mesaj
 * tipi ("Mesaj Tipi" sütunu) ve hata yutan catch bloklarının modülü.
 * Repolar env.exception_scan_repo'dan, bulgular kullanım, hata yutan catch,
 * tarama hatası ve türeyen sınıf tablolarından okunur.
 */
public final class DbReportReader {

    /** Okunan tarama */
    public static final class Report {
        public long scanRunId;
        public Instant startedAt;
        public final List<RepoResult> results = new ArrayList<RepoResult>();
    }

    /** env.exception_scan_repo satırı (API'nin repo listesi için) */
    public static final class RepoRow {
        public long scanRunId;
        public String projectKey;
        public String projectName;
        public String repo;
        public String branch;
        public String commitId;
        public String status;
        public Integer javaFileCount;
        public Integer usageCount;
        public Integer swallowedErrorCount;
        public Integer customExceptionClassCount;
        public Integer errorCount;
        public Instant scannedAt;
    }

    private final Config.Db db;

    public DbReportReader(Config cfg) {
        this.db = cfg.db;
    }

    /**
     * @param scanRunId null ise projenin (repo verildiyse o reponun) başarıyla tarandığı en son tarama
     * @param repo      null veya boşsa projenin tüm repoları
     */
    public Report read(String project, String repo, Long scanRunId) throws SQLException {
        try (Connection con = PostgresExporter.connect(db)) {
            con.setReadOnly(true);
            long runId = scanRunId != null ? scanRunId : latestRun(con, project, repo);
            Report report = new Report();
            report.scanRunId = runId;
            report.startedAt = startedAt(con, runId);

            Map<String, RepoResult> byRepo = new LinkedHashMap<String, RepoResult>();
            for (RepoRow r : repos(con, project, repo, runId)) {
                RepoInfo info = new RepoInfo(Config.Source.SERVER, r.projectKey, nz(r.projectName), r.repo, r.repo, "", "");
                info.branch = nz(r.branch);
                info.commit = nz(r.commitId);
                RepoResult rr = new RepoResult(info);
                byRepo.put(key(r.projectKey, r.repo), rr);
            }
            if (byRepo.isEmpty()) {
                throw new NoSuchElementException(scanRunId == null && repo != null && !repo.isEmpty()
                        ? "Bu repo taranmış görünmüyor: " + project + "/" + repo
                        : "scan_run_id=" + runId + " taramasında bu proje / repo yok: " + project
                        + (repo == null || repo.isEmpty() ? "" : "/" + repo));
            }
            readCounts(con, project, repo, runId, byRepo);
            readUsages(con, project, repo, runId, byRepo);
            readSwallowed(con, project, repo, runId, byRepo);
            readErrors(con, project, repo, runId, byRepo);
            readCustomExceptions(con, project, repo, runId, byRepo);
            report.results.addAll(byRepo.values());
            return report;
        }
    }

    /** Projenin repoları; scanRunId null ise projenin en son taraması */
    public List<RepoRow> repos(String project, Long scanRunId) throws SQLException {
        try (Connection con = PostgresExporter.connect(db)) {
            con.setReadOnly(true);
            long runId = scanRunId != null ? scanRunId : latestRun(con, project, null);
            return repos(con, project, null, runId);
        }
    }

    private long latestRun(Connection con, String project, String repo) throws SQLException {
        requireRepoTable(con);
        boolean oneRepo = repo != null && !repo.isEmpty();
        String sql = "SELECT max(scan_run_id) FROM " + db.repoTable + " WHERE lower(project_key) = lower(?)"
                + (oneRepo ? " AND lower(repo) = lower(?) AND status = 'SCANNED'" : "");
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, project);
            if (oneRepo) ps.setString(2, repo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long id = rs.getLong(1);
                if (rs.wasNull()) {
                    throw new NoSuchElementException("Veritabanında bu proje / repo için tarama yok: " + project
                            + (oneRepo ? "/" + repo : ""));
                }
                return id;
            }
        }
    }

    private Instant startedAt(Connection con, long runId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT started_at FROM " + db.runTable + " WHERE id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("Tarama bulunamadı: scan_run_id=" + runId);
                Timestamp t = rs.getTimestamp(1);
                return t == null ? Instant.now() : t.toInstant();
            }
        }
    }

    private void requireRepoTable(Connection con) throws SQLException {
        if (!exists(con, db.repoTable)) {
            throw new IllegalStateException(db.repoTable + " tablosu yok; rapor bu tablodan okunur "
                    + "(sql/exception_scanner_postgres.sql ile oluşturun, sonraki taramalardan itibaren rapor alınabilir)");
        }
    }

    private List<RepoRow> repos(Connection con, String project, String repo, long runId) throws SQLException {
        requireRepoTable(con);
        List<RepoRow> out = new ArrayList<RepoRow>();
        String sql = "SELECT scan_run_id, project_key, project_name, repo, branch, commit_id, status, java_file_count,"
                + " usage_count, swallowed_error_count, custom_exception_class_count, error_count, created_at"
                + " FROM " + db.repoTable + where(repo) + " ORDER BY repo";
        try (PreparedStatement ps = prepare(con, sql, runId, project, repo); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                RepoRow r = new RepoRow();
                r.scanRunId = rs.getLong(1);
                r.projectKey = rs.getString(2);
                r.projectName = rs.getString(3);
                r.repo = rs.getString(4);
                r.branch = rs.getString(5);
                r.commitId = rs.getString(6);
                r.status = rs.getString(7);
                r.javaFileCount = integer(rs, 8);
                r.usageCount = integer(rs, 9);
                r.swallowedErrorCount = integer(rs, 10);
                r.customExceptionClassCount = integer(rs, 11);
                r.errorCount = integer(rs, 12);
                Timestamp t = rs.getTimestamp(13);
                r.scannedAt = t == null ? null : t.toInstant();
                out.add(r);
            }
        }
        return out;
    }

    private void readCounts(Connection con, String project, String repo, long runId, Map<String, RepoResult> byRepo)
            throws SQLException {
        String sql = "SELECT project_key, repo, java_file_count, target_file_count, parse_failure_count"
                + " FROM " + db.repoTable + where(repo);
        try (PreparedStatement ps = prepare(con, sql, runId, project, repo); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                RepoResult r = byRepo.get(key(rs.getString(1), rs.getString(2)));
                if (r == null) continue;
                r.javaFiles = rs.getInt(3);
                r.filesMentioningTarget = rs.getInt(4);
                r.parseFailures = rs.getInt(5);
            }
        }
    }

    private void readUsages(Connection con, String project, String repo, long runId, Map<String, RepoResult> byRepo)
            throws SQLException {
        String sql = "SELECT u.project_key, u.repo, u.module, u.file_path, u.line_no, u.class_fqcn, u.method_signature,"
                + " u.exception_class, u.usage_type_code, t.type_name, t.action, u.error_code, u.message,"
                + " u.usage_context, u.is_test, u.code_snippet, u.link, u.class_id, u.method_id, u.match_status,"
                + " u.match_note"
                + " FROM " + db.usageTable + " u LEFT JOIN " + db.typeTable + " t ON t.type_code = u.usage_type_code"
                + where("u.", repo)
                + " ORDER BY u.repo, u.file_path, u.line_no";
        try (PreparedStatement ps = prepare(con, sql, runId, project, repo); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                RepoResult r = byRepo.get(key(rs.getString(1), rs.getString(2)));
                if (r == null) continue;
                UsageFinding u = new UsageFinding();
                location(u, r, rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6), rs.getString(7));
                u.targetClass = nz(rs.getString(8));
                u.typeCode = nz(rs.getString(9));
                u.pattern = nz(rs.getString(10));
                // Desen tanımlanmadıysa (ALL_USAGES) taramadaki raporda da "Yapılacak" boştur
                u.action = com.scanner.exception.scan.UsagePattern.ALL_TYPE.equals(u.typeCode) ? "" : nz(rs.getString(11));
                Integer code = integer(rs, 12);
                u.idValue = code == null ? "" : String.valueOf(code);
                u.message = nz(rs.getString(13));
                u.context = context(rs.getString(14));
                u.testCode = rs.getBoolean(15);
                u.expression = nz(rs.getString(16));
                u.link = rs.getString(17);
                match(u, rs, 18);
                r.usages.add(u);
            }
        }
    }

    private void readSwallowed(Connection con, String project, String repo, long runId, Map<String, RepoResult> byRepo)
            throws SQLException {
        if (!exists(con, db.swallowedTable)) return;
        String sql = "SELECT project_key, repo, file_path, line_no, class_fqcn, method_signature, exception_class,"
                + " catch_kind, link, class_id, method_id, match_status, match_note"
                + " FROM " + db.swallowedTable + where(repo) + " ORDER BY repo, file_path, line_no";
        try (PreparedStatement ps = prepare(con, sql, runId, project, repo); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                RepoResult r = byRepo.get(key(rs.getString(1), rs.getString(2)));
                if (r == null) continue;
                CatchFinding c = new CatchFinding();
                location(c, r, "", rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6));
                c.testCode = JavaSourceScanner.isTestPath(c.file);
                c.caughtTypes = nz(rs.getString(7));
                c.swallowKind = CatchFinding.SwallowKind.valueOf(rs.getString(8));
                c.link = rs.getString(9);
                match(c, rs, 10);
                r.swallowed.add(c);
            }
        }
    }

    private void readErrors(Connection con, String project, String repo, long runId, Map<String, RepoResult> byRepo)
            throws SQLException {
        if (!exists(con, db.errorTable)) return;
        String sql = "SELECT project_key, repo, stage, file_path, message FROM " + db.errorTable + where(repo)
                + " ORDER BY repo, id";
        try (PreparedStatement ps = prepare(con, sql, runId, project, repo); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                RepoResult r = byRepo.get(key(rs.getString(1), rs.getString(2)));
                if (r == null) continue;
                ScanError.Stage stage;
                try {
                    stage = ScanError.Stage.valueOf(rs.getString(3));
                } catch (IllegalArgumentException e) {
                    stage = ScanError.Stage.SCAN;
                }
                r.errors.add(new ScanError(r.repo, stage, rs.getString(4), rs.getString(5)));
            }
        }
    }

    private void readCustomExceptions(Connection con, String project, String repo, long runId,
                                      Map<String, RepoResult> byRepo) throws SQLException {
        if (!exists(con, db.customExceptionTable)) return;
        String sql = "SELECT project_key, repo, module, file_path, line_no, class_fqcn, parent_fqcn, is_test, link"
                + " FROM " + db.customExceptionTable + where(repo) + " ORDER BY repo, file_path, line_no";
        try (PreparedStatement ps = prepare(con, sql, runId, project, repo); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                RepoResult r = byRepo.get(key(rs.getString(1), rs.getString(2)));
                if (r == null) continue;
                SubclassFinding s = new SubclassFinding();
                location(s, r, rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6), "");
                s.parent = nz(rs.getString(7));
                s.testCode = rs.getBoolean(8);
                s.link = rs.getString(9);
                r.subclasses.add(s);
            }
        }
    }

    // ------------------------------------------------------------------ yardımcılar

    private static String where(String repo) {
        return where("", repo);
    }

    /** alias: tablo takma adı ("u.") veya boş */
    private static String where(String alias, String repo) {
        return " WHERE " + alias + "scan_run_id = ? AND lower(" + alias + "project_key) = lower(?)"
                + (repo == null || repo.isEmpty() ? "" : " AND lower(" + alias + "repo) = lower(?)");
    }

    private static PreparedStatement prepare(Connection con, String sql, long runId, String project, String repo)
            throws SQLException {
        PreparedStatement ps = con.prepareStatement(sql);
        ps.setLong(1, runId);
        ps.setString(2, project);
        if (repo != null && !repo.isEmpty()) ps.setString(3, repo);
        return ps;
    }

    private static void location(Located l, RepoResult r, String module, String file, int line, String cls,
                                 String member) {
        l.projectKey = r.repo.projectKey;
        l.projectName = r.repo.projectName;
        l.repo = r.repo.slug;
        l.module = nz(module);
        l.file = nz(file);
        l.line = line;
        l.className = nz(cls);
        l.member = nz(member);
    }

    private static void match(Located l, ResultSet rs, int col) throws SQLException {
        long cid = rs.getLong(col);
        l.dbClassId = rs.wasNull() ? null : Long.valueOf(cid);
        long mid = rs.getLong(col + 1);
        l.dbMethodId = rs.wasNull() ? null : Long.valueOf(mid);
        l.matchStatus = nz(rs.getString(col + 2));
        l.matchNote = nz(rs.getString(col + 3));
    }

    private static UsageFinding.Context context(String s) {
        try {
            return s == null ? UsageFinding.Context.OTHER : UsageFinding.Context.valueOf(s);
        } catch (IllegalArgumentException e) {
            return UsageFinding.Context.OTHER;
        }
    }


    private static Integer integer(ResultSet rs, int col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : Integer.valueOf(v);
    }

    private static String key(String project, String repo) {
        return String.valueOf(project).toLowerCase(java.util.Locale.ROOT) + "/"
                + String.valueOf(repo).toLowerCase(java.util.Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean exists(Connection con, String table) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

}
