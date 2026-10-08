package com.example.exscan;

import java.net.InetAddress;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * PostgreSQL'deki sınıf/metot envanterini okur ve tarama sonuçlarını kullanım tablosuna yazar.
 * Tüm kayıt tek transaction'da yapılır: yazma yarıda hata verirse hiçbir şey kaydedilmez.
 */
final class PostgresExporter implements DbCatalog, AutoCloseable {

    private static final int CHUNK = 1000;
    private static final int BATCH = 500;

    private final Config cfg;
    private final Config.Db db;
    private final Connection con;

    PostgresExporter(Config cfg) throws SQLException {
        this.cfg = cfg;
        this.db = cfg.db;
        Properties props = new Properties();
        if (!db.user.isEmpty()) props.setProperty("user", db.user);
        if (!db.password.isEmpty()) props.setProperty("password", db.password);
        props.setProperty("ApplicationName", "exception-scanner");
        props.setProperty("connectTimeout", "20");
        this.con = DriverManager.getConnection(db.url, props);
        this.con.setAutoCommit(false);
    }

    // =====================================================================
    //  Okuma
    // =====================================================================

    public Map<String, List<ClassRow>> findClasses(Collection<String> fqcns) throws SQLException {
        String column = db.caseInsensitive ? "lower(" + db.classFqcnColumn + ")" : db.classFqcnColumn;
        String sql = "SELECT " + db.classIdColumn + ", " + db.classFqcnColumn
                + " FROM " + db.classTable
                + " WHERE " + column + " = ANY(?)"
                + (db.classFilter.isEmpty() ? "" : " AND (" + db.classFilter + ")");

        Map<String, List<ClassRow>> out = new HashMap<String, List<ClassRow>>();
        List<String> all = new ArrayList<String>(fqcns);
        for (int from = 0; from < all.size(); from += CHUNK) {
            List<String> chunk = all.subList(from, Math.min(all.size(), from + CHUNK));
            PreparedStatement ps = con.prepareStatement(sql);
            try {
                Array arr = con.createArrayOf("text", chunk.toArray());
                ps.setArray(1, arr);
                ResultSet rs = ps.executeQuery();
                try {
                    while (rs.next()) {
                        String fqcn = rs.getString(2);
                        String key = db.caseInsensitive ? fqcn.toLowerCase(Locale.ENGLISH) : fqcn;
                        List<ClassRow> list = out.get(key);
                        if (list == null) {
                            list = new ArrayList<ClassRow>();
                            out.put(key, list);
                        }
                        list.add(new ClassRow(rs.getLong(1), fqcn));
                    }
                } finally {
                    rs.close();
                }
                arr.free();
            } finally {
                ps.close();
            }
        }
        return out;
    }

    public Map<Long, List<MethodRow>> findMethods(Collection<Long> classIds) throws SQLException {
        String sql = "SELECT " + db.methodIdColumn + ", " + db.methodClassColumn + ", " + db.methodNameColumn
                + (db.methodSignatureColumn == null ? "" : ", " + db.methodSignatureColumn)
                + " FROM " + db.methodTable
                + " WHERE " + db.methodClassColumn + " = ANY(?)";

        Map<Long, List<MethodRow>> out = new HashMap<Long, List<MethodRow>>();
        List<Long> all = new ArrayList<Long>(classIds);
        for (int from = 0; from < all.size(); from += CHUNK) {
            List<Long> chunk = all.subList(from, Math.min(all.size(), from + CHUNK));
            PreparedStatement ps = con.prepareStatement(sql);
            try {
                Array arr = con.createArrayOf("int8", chunk.toArray());
                ps.setArray(1, arr);
                ResultSet rs = ps.executeQuery();
                try {
                    while (rs.next()) {
                        long classId = rs.getLong(2);
                        List<MethodRow> list = out.get(Long.valueOf(classId));
                        if (list == null) {
                            list = new ArrayList<MethodRow>();
                            out.put(Long.valueOf(classId), list);
                        }
                        String sig = db.methodSignatureColumn == null ? null : rs.getString(4);
                        list.add(new MethodRow(rs.getLong(1), classId, rs.getString(3), sig));
                    }
                } finally {
                    rs.close();
                }
                arr.free();
            } finally {
                ps.close();
            }
        }
        return out;
    }

    // =====================================================================
    //  Yazma
    // =====================================================================

    static final class InsertResult {
        long runId;
        int inserted;
        int skipped;
        int screens;
        int popups;
        int regions;
        int reports;
        int processes;
        int ebmlSkipped;
        /** Mevcut ekran / popup / rapor / process tablolarındaki project_id güncellemesinin özeti */
        final List<String> existing = new ArrayList<String>();
    }

    InsertResult insert(List<RepoResult> results) throws SQLException {
        try {
            upsertTypes();
            InsertResult ir = new InsertResult();
            ir.runId = insertRun(results);

            String sql = "INSERT INTO " + db.usageTable + " (scan_run_id, class_id, method_id, usage_type_code,"
                    + " match_status, match_note, project_key, repo, module, file_path, line_no, class_fqcn,"
                    + " method_signature, exception_class, error_code, message, usage_context, is_test,"
                    + " code_snippet, link) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            PreparedStatement ps = con.prepareStatement(sql);
            try {
                int pending = 0;
                for (RepoResult r : results) {
                    for (UsageFinding u : r.usages) {
                        if (!db.insertUnmatched && DbMatcher.CLASS_NOT_FOUND.equals(u.matchStatus)) {
                            ir.skipped++;
                            continue;
                        }
                        int i = 1;
                        ps.setLong(i++, ir.runId);
                        setLong(ps, i++, u.dbClassId);
                        setLong(ps, i++, u.dbMethodId);
                        ps.setString(i++, u.typeCode);
                        ps.setString(i++, u.matchStatus.isEmpty() ? DbMatcher.CLASS_NOT_FOUND : u.matchStatus);
                        ps.setString(i++, cut(u.matchNote, 1000));
                        ps.setString(i++, cut(u.projectKey, 100));
                        ps.setString(i++, cut(u.repo, 200));
                        ps.setString(i++, cut(u.module, 300));
                        ps.setString(i++, cut(u.file, 1000));
                        ps.setInt(i++, u.line);
                        ps.setString(i++, cut(u.className, 1000));
                        ps.setString(i++, cut(u.member, 1000));
                        ps.setString(i++, cut(u.targetClass, 300));
                        Integer code = toInt(u.idValue);
                        if (code == null) ps.setNull(i++, Types.INTEGER);
                        else ps.setInt(i++, code.intValue());
                        ps.setString(i++, u.message);
                        ps.setString(i++, cut(u.context, 50));
                        ps.setBoolean(i++, u.testCode);
                        ps.setString(i++, u.expression);
                        ps.setString(i++, cut(u.link, 2000));
                        ps.addBatch();
                        ir.inserted++;
                        if (++pending >= BATCH) {
                            ps.executeBatch();
                            pending = 0;
                        }
                    }
                }
                if (pending > 0) ps.executeBatch();
            } finally {
                ps.close();
            }

            if (cfg.ebml.enabled) insertEbml(results, ir);
            if (cfg.ebml.enabled && cfg.ebml.updateExisting) updateExisting(results, ir);

            PreparedStatement fin = con.prepareStatement("UPDATE " + db.runTable
                    + " SET finished_at = now(), usage_count = ?"
                    + (cfg.ebml.enabled ? ", screen_count = ?, popup_count = ?, region_count = ?, report_count = ?,"
                    + " process_count = ?" : "")
                    + " WHERE id = ?");
            try {
                int i = 1;
                fin.setInt(i++, ir.inserted);
                if (cfg.ebml.enabled) {
                    fin.setInt(i++, ir.screens);
                    fin.setInt(i++, ir.popups);
                    fin.setInt(i++, ir.regions);
                    fin.setInt(i++, ir.reports);
                    fin.setInt(i++, ir.processes);
                }
                fin.setLong(i, ir.runId);
                fin.executeUpdate();
            } finally {
                fin.close();
            }
            con.commit();
            return ir;
        } catch (SQLException e) {
            rollbackQuietly();
            throw unwrap(e);
        } catch (RuntimeException e) {
            rollbackQuietly();
            throw e;
        }
    }

    /** Ayar dosyasındaki desenleri kullanım tipi tablosuna yazar (varsa günceller). */
    private void upsertTypes() throws SQLException {
        Map<String, Object[]> types = new LinkedHashMap<String, Object[]>();
        if (cfg.patterns.isEmpty() && !cfg.exceptionClasses.isEmpty()) {
            types.put(UsagePattern.ALL_TYPE, new Object[]{UsagePattern.ALL, "İncelenmeli", "*", "EXCEPTION", Boolean.TRUE});
        }
        for (UsagePattern p : cfg.patterns) {
            types.put(p.type, new Object[]{p.name, p.action, p.argsText, "EXCEPTION", Boolean.valueOf(p.legacy)});
        }
        for (CallPattern c : cfg.callPatterns) {
            types.put(c.type(), new Object[]{c.name, c.action, c.spec(), "CALL", Boolean.valueOf(c.args.legacy)});
        }
        String sql = "INSERT INTO " + db.typeTable
                + " (type_code, type_name, action, args_spec, source_kind, is_legacy, updated_at)"
                + " VALUES (?,?,?,?,?,?,now())"
                + " ON CONFLICT (type_code) DO UPDATE SET type_name = EXCLUDED.type_name,"
                + " action = EXCLUDED.action, args_spec = EXCLUDED.args_spec,"
                + " source_kind = EXCLUDED.source_kind, is_legacy = EXCLUDED.is_legacy, updated_at = now()";
        if (types.isEmpty()) return; // sadece ekran/rapor envanteri çıkarılıyor
        PreparedStatement ps = con.prepareStatement(sql);
        try {
            for (Map.Entry<String, Object[]> en : types.entrySet()) {
                Object[] v = en.getValue();
                ps.setString(1, en.getKey());
                ps.setString(2, cut((String) v[0], 200));
                ps.setString(3, cut((String) v[1], 200));
                ps.setString(4, cut((String) v[2], 500));
                ps.setString(5, (String) v[3]);
                ps.setBoolean(6, ((Boolean) v[4]).booleanValue());
                ps.addBatch();
            }
            ps.executeBatch();
        } finally {
            ps.close();
        }
    }

    // =====================================================================
    //  Ekran, region, Jasper rapor ve process envanteri
    // =====================================================================

    /** Dosyaların ana proje adını env.project tablosunda arar ve project_id'yi doldurur. */
    void resolveProjects(List<EbmlFile> files) throws SQLException {
        Config.Ebml eb = cfg.ebml;
        java.util.Set<String> names = new java.util.LinkedHashSet<String>();
        for (EbmlFile f : files) {
            for (String n : f.projectNameCandidates) names.add(projectKey(n));
        }
        Map<String, List<Long>> found = new HashMap<String, List<Long>>();
        if (!names.isEmpty()) {
            String column = eb.projectCaseInsensitive ? "lower(" + eb.projectNameColumn + ")" : eb.projectNameColumn;
            String sql = "SELECT " + eb.projectIdColumn + ", " + eb.projectNameColumn + " FROM " + eb.projectTable
                    + " WHERE " + column + " = ANY(?) ORDER BY " + eb.projectIdColumn;
            PreparedStatement ps = con.prepareStatement(sql);
            try {
                Array arr = con.createArrayOf("text", names.toArray());
                ps.setArray(1, arr);
                ResultSet rs = ps.executeQuery();
                try {
                    while (rs.next()) {
                        String key = projectKey(rs.getString(2));
                        List<Long> ids = found.get(key);
                        if (ids == null) {
                            ids = new ArrayList<Long>();
                            found.put(key, ids);
                        }
                        ids.add(Long.valueOf(rs.getLong(1)));
                    }
                } finally {
                    rs.close();
                }
                arr.free();
            } finally {
                ps.close();
            }
        }
        for (EbmlFile f : files) {
            f.projectId = null;
            f.projectMatch = EbmlInventory.PROJECT_NOT_FOUND;
            for (String n : f.projectNameCandidates) {
                List<Long> ids = found.get(projectKey(n));
                if (ids == null || ids.isEmpty()) continue;
                f.projectId = ids.get(0); // sorgu id sırasıyla döner: birden fazlaysa en küçüğü
                f.projectName = n;
                f.projectMatch = ids.size() == 1 ? EbmlInventory.MATCHED : EbmlInventory.PROJECT_AMBIGUOUS;
                break;
            }
        }
    }

    private String projectKey(String name) {
        String n = name == null ? "" : name.trim();
        return cfg.ebml.projectCaseInsensitive ? n.toLowerCase(Locale.ENGLISH) : n;
    }

    private void insertEbml(List<RepoResult> results, InsertResult ir) throws SQLException {
        Config.Ebml eb = cfg.ebml;
        String common = " (scan_run_id, project_id, project_name, project_match, repo, module, package_name,"
                + " file_name, file_path, link";
        PreparedStatement screens = con.prepareStatement("INSERT INTO " + eb.screenTable + common
                + ") VALUES (?,?,?,?,?,?,?,?,?,?)");
        PreparedStatement popups = con.prepareStatement("INSERT INTO " + eb.popupTable + common
                + ") VALUES (?,?,?,?,?,?,?,?,?,?)");
        PreparedStatement regions = con.prepareStatement("INSERT INTO " + eb.regionTable + common
                + ", match_rule) VALUES (?,?,?,?,?,?,?,?,?,?,?)");
        PreparedStatement reports = con.prepareStatement("INSERT INTO " + eb.reportTable + common
                + ") VALUES (?,?,?,?,?,?,?,?,?,?)");
        PreparedStatement processes = con.prepareStatement("INSERT INTO " + eb.processTable
                + " (scan_run_id, project_id, project_name, project_match, repo, module, process_id, process_name,"
                + " folder_name, file_path, link) VALUES (?,?,?,?,?,?,?,?,?,?,?)");
        try {
            for (RepoResult r : results) {
                for (EbmlFile f : r.ebmlFiles) {
                    PreparedStatement ps;
                    switch (f.kind) {
                        case SCREEN: ps = screens; break;
                        case POPUP: ps = popups; break;
                        case REGION: ps = regions; break;
                        case REPORT: ps = reports; break;
                        case PROCESS: ps = processes; break;
                        default: continue; // sınıflandırılmayanlar sadece raporda
                    }
                    if (!eb.insertUnmatched && f.projectId == null) {
                        ir.ebmlSkipped++;
                        continue;
                    }
                    int i = 1;
                    ps.setLong(i++, ir.runId);
                    setLong(ps, i++, f.projectId);
                    ps.setString(i++, cut(f.projectName, 300));
                    ps.setString(i++, f.projectMatch.isEmpty() ? EbmlInventory.PROJECT_NOT_FOUND : f.projectMatch);
                    ps.setString(i++, cut(f.repo, 200));
                    ps.setString(i++, cut(f.module, 300));
                    if (f.kind == EbmlFile.Kind.PROCESS) {
                        ps.setLong(i++, f.processId.longValue());
                        ps.setString(i++, f.processName.isEmpty() ? null : cut(f.processName, 500));
                        ps.setString(i++, cut(f.fileName, 500));
                        ps.setString(i++, cut(f.file, 1000));
                        ps.setString(i, cut(f.link, 2000));
                        ps.addBatch();
                        ir.processes++;
                        continue;
                    }
                    ps.setString(i++, cut(f.packageName, 1000));
                    ps.setString(i++, cut(f.fileName, 500));
                    ps.setString(i++, cut(f.file, 1000));
                    ps.setString(i++, cut(f.link, 2000));
                    if (f.kind == EbmlFile.Kind.REGION) ps.setString(i, f.rule);
                    ps.addBatch();
                    if (f.kind == EbmlFile.Kind.SCREEN) ir.screens++;
                    else if (f.kind == EbmlFile.Kind.POPUP) ir.popups++;
                    else if (f.kind == EbmlFile.Kind.REGION) ir.regions++;
                    else ir.reports++;
                }
            }
            screens.executeBatch();
            popups.executeBatch();
            regions.executeBatch();
            reports.executeBatch();
            processes.executeBatch();
        } finally {
            screens.close();
            popups.close();
            regions.close();
            reports.close();
            processes.close();
        }
    }

    // =====================================================================
    //  Mevcut ekran / popup / rapor / process tablolarında project_id güncellemesi
    // =====================================================================

    /** Bir hedef tablonun (env.screen page, env.popup, ...) güncelleme sonucu */
    private static final class Target {
        final String label;
        int files;
        int found;
        int ambiguous;
        int updated;
        String error;

        Target(String label) {
            this.label = label;
        }

        public String toString() {
            if (error != null) return label + ": atlandı (" + error + ")";
            return label + ": " + files + " dosya, " + found + " tanesi tabloda bulundu, " + updated + " satır güncellendi"
                    + (files - found - ambiguous > 0 ? ", " + (files - found - ambiguous) + " tanesi tabloda yok" : "")
                    + (ambiguous > 0 ? ", aynı adı farklı projelerde olan " + ambiguous + " dosya güncellenmedi" : "");
        }
    }

    /** Anahtar sütunu ve sırayla denenecek anahtarlar; dosyanın tabloda bulunması için biri yeterli */
    private interface Keys {
        List<String> of(EbmlFile f);
    }

    /**
     * Taramada bulunan dosyaların project_id değerini mevcut env.screen, env.popup, env.report ve
     * env.process tablolarına yazar. Her tablo ayrı savepoint'te güncellenir: tablo veya sütun yoksa
     * sadece o tablo atlanır, tarama kaydı yine yapılır.
     */
    private void updateExisting(List<RepoResult> results, InsertResult ir) throws SQLException {
        Config.Ebml eb = cfg.ebml;
        List<EbmlFile> screens = new ArrayList<EbmlFile>(), regions = new ArrayList<EbmlFile>(),
                popups = new ArrayList<EbmlFile>(), reports = new ArrayList<EbmlFile>(), processes = new ArrayList<EbmlFile>();
        for (RepoResult r : results) {
            for (EbmlFile f : r.ebmlFiles) {
                switch (f.kind) {
                    case SCREEN: screens.add(f); break;
                    case REGION: regions.add(f); break;
                    case POPUP: popups.add(f); break;
                    case REPORT: reports.add(f); break;
                    case PROCESS: processes.add(f); break;
                    default: break;
                }
            }
        }
        Keys fileName = new Keys() {
            public List<String> of(EbmlFile f) {
                String n = f.file.substring(f.file.lastIndexOf('/') + 1);
                List<String> k = new ArrayList<String>();
                k.add(n);
                if (n.lastIndexOf('.') > 0) k.add(n.substring(0, n.lastIndexOf('.')));
                return k;
            }
        };
        Keys processNo = new Keys() {
            public List<String> of(EbmlFile f) {
                List<String> k = new ArrayList<String>();
                k.add(String.valueOf(f.processId));
                if (!f.processCode.isEmpty()) k.add(f.processCode);
                return k;
            }
        };
        Keys processName = new Keys() {
            public List<String> of(EbmlFile f) {
                List<String> k = new ArrayList<String>();
                if (!f.processShortName.isEmpty()) {
                    k.add(f.processShortName);
                    k.add(f.processShortName + ".par");
                }
                return k;
            }
        };

        String screenLabel = eb.existingScreenTable + "." + eb.existingScreenNameColumn;
        ir.existing.add(update(new Target(screenLabel + " (" + eb.existingScreenTypeColumn + "=" + eb.existingScreenTypePage + ")"),
                screens, eb.existingScreenTable, eb.existingScreenNameColumn, eb.existingScreenTypeColumn,
                eb.existingScreenTypePage, fileName, null, null).toString());
        ir.existing.add(update(new Target(screenLabel + " (" + eb.existingScreenTypeColumn + "=" + eb.existingScreenTypeRegion + ")"),
                regions, eb.existingScreenTable, eb.existingScreenNameColumn, eb.existingScreenTypeColumn,
                eb.existingScreenTypeRegion, fileName, null, null).toString());
        ir.existing.add(update(new Target(eb.existingPopupTable + "." + eb.existingPopupNameColumn),
                popups, eb.existingPopupTable, eb.existingPopupNameColumn, null, null, fileName, null, null).toString());
        ir.existing.add(update(new Target(eb.existingReportTable + "." + eb.existingReportNameColumn),
                reports, eb.existingReportTable, eb.existingReportNameColumn, null, null, fileName, null, null).toString());
        ir.existing.add(update(new Target(eb.existingProcessTable + "." + eb.existingProcessNoColumn + " / "
                        + eb.existingProcessNameColumn),
                processes, eb.existingProcessTable, eb.existingProcessNoColumn, null, null, processNo,
                eb.existingProcessNameColumn, processName).toString());
    }

    /**
     * Dosyaları önce keyColumn ile, orada bulunamayanları (varsa) fallbackColumn ile eşleştirip
     * project_id'yi günceller. Karşılaştırma büyük/küçük harf ve baştaki/sondaki boşluklara duyarsızdır.
     */
    private Target update(Target t, List<EbmlFile> all, String table, String keyColumn, String typeColumn, String typeValue,
                          Keys keys, String fallbackColumn, Keys fallbackKeys) throws SQLException {
        List<EbmlFile> files = new ArrayList<EbmlFile>();
        for (EbmlFile f : all) if (f.projectId != null) files.add(f);
        t.files = files.size();
        if (files.isEmpty()) return t;

        Savepoint sp = con.setSavepoint();
        try {
            if (!exists(table)) {
                con.releaseSavepoint(sp);
                t.error = "tablo yok";
                return t;
            }
            java.util.Set<EbmlFile> ambiguous = new java.util.HashSet<EbmlFile>();
            List<EbmlFile> notFound = match(t, files, table, keyColumn, typeColumn, typeValue, keys, ambiguous);
            if (fallbackColumn != null && !notFound.isEmpty()) {
                match(t, notFound, table, fallbackColumn, typeColumn, typeValue, fallbackKeys, ambiguous);
            }
            t.ambiguous = ambiguous.size();
            con.releaseSavepoint(sp);
        } catch (SQLException e) {
            con.rollback(sp);
            String msg = String.valueOf(unwrap(e).getMessage());
            t.error = msg.split("\\R", 2)[0].trim();
            t.found = 0;
            t.updated = 0;
            t.ambiguous = 0;
        }
        return t;
    }

    /** Eşleştirip günceller; tabloda bulunamayan (ve belirsiz olmayan) dosyaları döndürür. */
    private List<EbmlFile> match(Target t, List<EbmlFile> files, String table, String column, String typeColumn,
                                 String typeValue, Keys keys, java.util.Set<EbmlFile> ambiguous) throws SQLException {
        // Anahtar -> projeler; aynı ad farklı projelerde geçiyorsa hangisi olduğu bilinemez, o ad güncellenmez
        Map<String, java.util.Set<Long>> byKey = new LinkedHashMap<String, java.util.Set<Long>>();
        Map<String, String> original = new HashMap<String, String>();
        for (EbmlFile f : files) {
            for (String k : keys.of(f)) {
                String key = k.trim().toLowerCase(Locale.ROOT);
                if (key.isEmpty()) continue;
                java.util.Set<Long> p = byKey.get(key);
                if (p == null) {
                    p = new java.util.LinkedHashSet<Long>();
                    byKey.put(key, p);
                    original.put(key, k.trim());
                }
                p.add(f.projectId);
            }
        }
        List<String> ks = new ArrayList<String>();
        List<Long> pids = new ArrayList<Long>();
        for (Map.Entry<String, java.util.Set<Long>> en : byKey.entrySet()) {
            if (en.getValue().size() != 1) continue;
            ks.add(original.get(en.getKey()));
            pids.add(en.getValue().iterator().next());
        }
        // Her iki tarafta da PostgreSQL lower() kullanılır: Türkçe karakterlerde Java ile farklı sonuç vermesin
        String cond = " lower(trim(t." + column + "::text)) = lower(v.k)"
                + (typeColumn == null ? "" : " AND lower(trim(t." + typeColumn + "::text)) = lower(?)");
        java.util.Set<String> found = new java.util.HashSet<String>();
        if (!ks.isEmpty()) {
            PreparedStatement q = con.prepareStatement("SELECT DISTINCT v.k FROM unnest(?::text[]) AS v (k)"
                    + " WHERE EXISTS (SELECT 1 FROM " + table + " t WHERE" + cond + ")");
            try {
                Array arr = con.createArrayOf("text", ks.toArray());
                q.setArray(1, arr);
                if (typeColumn != null) q.setString(2, typeValue);
                ResultSet rs = q.executeQuery();
                try {
                    while (rs.next()) found.add(rs.getString(1).trim().toLowerCase(Locale.ROOT));
                } finally {
                    rs.close();
                }
                arr.free();
            } finally {
                q.close();
            }

            String pc = cfg.ebml.existingProjectColumn;
            PreparedStatement u = con.prepareStatement("UPDATE " + table + " t SET " + pc + " = v.pid"
                    + " FROM unnest(?::text[], ?::bigint[]) AS v (k, pid) WHERE" + cond
                    + (cfg.ebml.updateOnlyEmpty ? " AND t." + pc + " IS NULL" : " AND t." + pc + " IS DISTINCT FROM v.pid"));
            try {
                Array ka = con.createArrayOf("text", ks.toArray());
                Array pa = con.createArrayOf("int8", pids.toArray());
                u.setArray(1, ka);
                u.setArray(2, pa);
                if (typeColumn != null) u.setString(3, typeValue);
                t.updated += u.executeUpdate();
                ka.free();
                pa.free();
            } finally {
                u.close();
            }
        }

        List<EbmlFile> notFound = new ArrayList<EbmlFile>();
        for (EbmlFile f : files) {
            boolean hit = false, amb = false;
            for (String k : keys.of(f)) {
                String key = k.trim().toLowerCase(Locale.ROOT);
                if (found.contains(key)) hit = true;
                java.util.Set<Long> p = byKey.get(key);
                if (p != null && p.size() > 1) amb = true;
            }
            if (hit) {
                t.found++;
                ambiguous.remove(f);
            } else if (amb) {
                ambiguous.add(f);
                notFound.add(f);
            } else {
                notFound.add(f);
            }
        }
        return notFound;
    }

    private boolean exists(String table) throws SQLException {
        PreparedStatement ps = con.prepareStatement("SELECT to_regclass(?) IS NOT NULL");
        try {
            ps.setString(1, table);
            ResultSet rs = ps.executeQuery();
            try {
                return rs.next() && rs.getBoolean(1);
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    private long insertRun(List<RepoResult> results) throws SQLException {
        int javaFiles = 0;
        for (RepoResult r : results) javaFiles += r.javaFiles;
        String sql = "INSERT INTO " + db.runTable
                + " (source, repo_count, java_file_count, exception_classes, note, run_by, host)"
                + " VALUES (?,?,?,?,?,?,?) RETURNING id";
        PreparedStatement ps = con.prepareStatement(sql);
        try {
            ps.setString(1, cfg.source.name());
            ps.setInt(2, results.size());
            ps.setInt(3, javaFiles);
            ps.setString(4, String.join(",", cfg.exceptionClasses));
            ps.setString(5, db.note.isEmpty() ? null : db.note);
            ps.setString(6, cut(System.getProperty("user.name"), 100));
            ps.setString(7, cut(hostName(), 200));
            ResultSet rs = ps.executeQuery();
            try {
                rs.next();
                return rs.getLong(1);
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    public void close() {
        try {
            con.close();
        } catch (SQLException ignored) {
            // kapanırken oluşan hata önemsiz
        }
    }

    // =====================================================================
    //  Yardımcılar
    // =====================================================================

    private void rollbackQuietly() {
        try {
            con.rollback();
        } catch (SQLException ignored) {
            // asıl hata raporlanır
        }
    }

    /** Batch hatalarında PostgreSQL asıl sebebi getNextException içinde verir */
    private static SQLException unwrap(SQLException e) {
        SQLException next = e.getNextException();
        return next != null ? new SQLException(e.getMessage() + " -> " + next.getMessage(), next) : e;
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) ps.setNull(i, Types.BIGINT);
        else ps.setLong(i, v.longValue());
    }

    private static Integer toInt(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            long v = Long.decode(s.replace("_", "").replaceAll("[lL]$", ""));
            return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? Integer.valueOf((int) v) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "";
        }
    }
}
