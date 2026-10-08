-- =====================================================================
--  Ekran, region, Jasper rapor ve process envanteri (PostgreSQL 9.6+)
--
--  Mevcut tablo (değiştirilmez, sadece okunur ve FK ile referans verilir):
--    env.project (id, project_name, ...)
--
--  Sütun adları sizde farklıysa aşağıdaki REFERENCES satırlarını ve
--  scanner.properties içindeki db.project.* ayarlarını birlikte güncelleyin.
--  Bu dosya tek başına da çalıştırılabilir; exception_usage_postgres.sql
--  daha önce çalıştırıldıysa ortak tarama tablosuna sadece sütun ekler.
-- =====================================================================

-- ---------------------------------------------------------------------
--  Tarama çalıştırmaları (exception kullanımlarıyla ortak tablo)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS env.exception_scan_run (
    id                 bigserial    PRIMARY KEY,
    started_at         timestamptz  NOT NULL DEFAULT now(),
    finished_at        timestamptz,
    source             varchar(20),                    -- SERVER | CLOUD | GIT | LOCAL
    repo_count         integer,
    java_file_count    integer,
    usage_count        integer,
    exception_classes  text,
    note               text,
    run_by             varchar(100),
    host               varchar(200)
);
ALTER TABLE env.exception_scan_run ADD COLUMN IF NOT EXISTS screen_count integer;
ALTER TABLE env.exception_scan_run ADD COLUMN IF NOT EXISTS popup_count integer;
ALTER TABLE env.exception_scan_run ADD COLUMN IF NOT EXISTS region_count integer;
ALTER TABLE env.exception_scan_run ADD COLUMN IF NOT EXISTS report_count integer;
ALTER TABLE env.exception_scan_run ADD COLUMN IF NOT EXISTS process_count integer;

-- ---------------------------------------------------------------------
--  Eski tablo adlarından geçiş (veriler korunur):
--    env.screens -> env.all_screens, env.popups -> env.all_popups,
--    env.regions -> env.all_regions, env.jasper_reports -> env.all_reports
-- ---------------------------------------------------------------------
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT * FROM (VALUES ('screens', 'all_screens'), ('popups', 'all_popups'),
                                   ('regions', 'all_regions'), ('jasper_reports', 'all_reports')) AS t (old_name, new_name)
    LOOP
        IF to_regclass('env.' || r.old_name) IS NOT NULL AND to_regclass('env.' || r.new_name) IS NULL THEN
            EXECUTE format('ALTER TABLE env.%I RENAME TO %I', r.old_name, r.new_name);
            EXECUTE format('ALTER SEQUENCE IF EXISTS env.%I RENAME TO %I', r.old_name || '_id_seq', r.new_name || '_id_seq');
            EXECUTE format('ALTER INDEX IF EXISTS env.%I RENAME TO %I', 'ix_' || r.old_name || '_run', 'ix_' || r.new_name || '_run');
            EXECUTE format('ALTER INDEX IF EXISTS env.%I RENAME TO %I', 'ix_' || r.old_name || '_project', 'ix_' || r.new_name || '_project');
        END IF;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
--  Ortak sütunlar (tüm envanter tablolarında aynı):
--    project_id    : env.project.id; proje bulunamazsa boş
--    project_name  : env.project tablosunda aranan ana proje adı
--    project_match : MATCHED | PROJECT_AMBIGUOUS (en küçük id seçildi) | PROJECT_NOT_FOUND
--    file_name     : dosya adı (ebml.file.name.with.extension=false ise uzantısız)
--    file_path     : repo içindeki yol
--    link          : Bitbucket'ta dosyayı açan bağlantı (taranan commit'e sabitlenmiş)
--    created_at    : kaydın eklendiği tarih
-- ---------------------------------------------------------------------

-- Ekranlar: ebml.page paketi (ve alt paketleri) altındaki .ebml dosyaları
CREATE TABLE IF NOT EXISTS env.all_screens (
    id             bigserial      PRIMARY KEY,
    scan_run_id    bigint         NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_id     bigint         REFERENCES env.project (id),
    project_name   varchar(300),
    project_match  varchar(30)    NOT NULL,
    repo           varchar(200),
    module         varchar(300),
    package_name   varchar(1000),
    file_name      varchar(500)   NOT NULL,
    file_path      varchar(1000)  NOT NULL,
    link           varchar(2000),
    created_at     timestamptz    NOT NULL DEFAULT now()
);

-- Popup'lar: ebml.popup paketi (ve alt paketleri) altındaki .ebml dosyaları
CREATE TABLE IF NOT EXISTS env.all_popups (
    id             bigserial      PRIMARY KEY,
    scan_run_id    bigint         NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_id     bigint         REFERENCES env.project (id),
    project_name   varchar(300),
    project_match  varchar(30)    NOT NULL,
    repo           varchar(200),
    module         varchar(300),
    package_name   varchar(1000),
    file_name      varchar(500)   NOT NULL,
    file_path      varchar(1000)  NOT NULL,
    link           varchar(2000),
    created_at     timestamptz    NOT NULL DEFAULT now()
);

-- Region'lar: adı RG_ ile başlayan veya ebml.region paketi altındaki .ebml dosyaları
CREATE TABLE IF NOT EXISTS env.all_regions (
    id             bigserial      PRIMARY KEY,
    scan_run_id    bigint         NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_id     bigint         REFERENCES env.project (id),
    project_name   varchar(300),
    project_match  varchar(30)    NOT NULL,
    repo           varchar(200),
    module         varchar(300),
    package_name   varchar(1000),
    file_name      varchar(500)   NOT NULL,
    file_path      varchar(1000)  NOT NULL,
    link           varchar(2000),
    match_rule     varchar(20)    NOT NULL,            -- PREFIX | PACKAGE | PREFIX+PACKAGE
    created_at     timestamptz    NOT NULL DEFAULT now()
);

-- Jasper raporları: ebml.report paketi (ve alt paketleri) altındaki .dsxml dosyaları
CREATE TABLE IF NOT EXISTS env.all_reports (
    id             bigserial      PRIMARY KEY,
    scan_run_id    bigint         NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_id     bigint         REFERENCES env.project (id),
    project_name   varchar(300),
    project_match  varchar(30)    NOT NULL,
    repo           varchar(200),
    module         varchar(300),
    package_name   varchar(1000),
    file_name      varchar(500)   NOT NULL,
    file_path      varchar(1000)  NOT NULL,
    link           varchar(2000),
    created_at     timestamptz    NOT NULL DEFAULT now()
);

-- Process'ler: process klasörü altındaki 250001-RISM.par gibi klasörlerdeki processdefinition.xml dosyaları
CREATE TABLE IF NOT EXISTS env.all_processes (
    id             bigserial      PRIMARY KEY,
    scan_run_id    bigint         NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_id     bigint         REFERENCES env.project (id),
    project_name   varchar(300),
    project_match  varchar(30)    NOT NULL,
    repo           varchar(200),
    module         varchar(300),
    process_id     bigint         NOT NULL,            -- klasör adındaki numara: 250001-RISM.par -> 250001
    process_name   varchar(500),                       -- processdefinition.xml içindeki label (Müşteri Değerlendirme)
    folder_name    varchar(500)   NOT NULL,            -- 250001-RISM.par
    file_path      varchar(1000)  NOT NULL,
    link           varchar(2000),
    created_at     timestamptz    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_all_screens_run         ON env.all_screens (scan_run_id);
CREATE INDEX IF NOT EXISTS ix_all_screens_project     ON env.all_screens (project_id);
CREATE INDEX IF NOT EXISTS ix_all_popups_run          ON env.all_popups (scan_run_id);
CREATE INDEX IF NOT EXISTS ix_all_popups_project      ON env.all_popups (project_id);
CREATE INDEX IF NOT EXISTS ix_all_regions_run         ON env.all_regions (scan_run_id);
CREATE INDEX IF NOT EXISTS ix_all_regions_project     ON env.all_regions (project_id);
CREATE INDEX IF NOT EXISTS ix_all_reports_run         ON env.all_reports (scan_run_id);
CREATE INDEX IF NOT EXISTS ix_all_reports_project     ON env.all_reports (project_id);
CREATE INDEX IF NOT EXISTS ix_all_processes_run       ON env.all_processes (scan_run_id);
CREATE INDEX IF NOT EXISTS ix_all_processes_project   ON env.all_processes (project_id);
CREATE INDEX IF NOT EXISTS ix_all_processes_process   ON env.all_processes (process_id);

-- ---------------------------------------------------------------------
--  Son taramada proje bazında sayılar
-- ---------------------------------------------------------------------
-- Sütun sırası sürümler arasında değişebildiği için görünüm silinip yeniden oluşturulur
DROP VIEW IF EXISTS env.v_ebml_inventory_latest;
CREATE VIEW env.v_ebml_inventory_latest AS
WITH last_run AS (
    SELECT max(id) AS id FROM env.exception_scan_run
     WHERE finished_at IS NOT NULL AND screen_count IS NOT NULL
), files AS (
    SELECT 'SCREEN' AS kind, project_id, project_name, project_match FROM env.all_screens   WHERE scan_run_id = (SELECT id FROM last_run)
    UNION ALL
    SELECT 'POPUP',          project_id, project_name, project_match FROM env.all_popups    WHERE scan_run_id = (SELECT id FROM last_run)
    UNION ALL
    SELECT 'REGION',         project_id, project_name, project_match FROM env.all_regions   WHERE scan_run_id = (SELECT id FROM last_run)
    UNION ALL
    SELECT 'REPORT',         project_id, project_name, project_match FROM env.all_reports   WHERE scan_run_id = (SELECT id FROM last_run)
    UNION ALL
    SELECT 'PROCESS',        project_id, project_name, project_match FROM env.all_processes WHERE scan_run_id = (SELECT id FROM last_run)
)
SELECT project_id,
       project_name,
       project_match,
       count(*) FILTER (WHERE kind = 'SCREEN') AS screen_count,
       count(*) FILTER (WHERE kind = 'POPUP')  AS popup_count,
       count(*) FILTER (WHERE kind = 'REGION') AS region_count,
       count(*) FILTER (WHERE kind = 'REPORT') AS report_count,
       count(*) FILTER (WHERE kind = 'PROCESS') AS process_count
  FROM files
 GROUP BY project_id, project_name, project_match;

-- Örnek sorgu: bir projenin son taramadaki ekranları
--   SELECT s.file_name, s.package_name, s.link
--     FROM env.all_screens s
--    WHERE s.scan_run_id = (SELECT max(id) FROM env.exception_scan_run WHERE screen_count IS NOT NULL)
--      AND s.project_id = 42
--    ORDER BY s.package_name, s.file_name;
--
-- Örnek sorgu: bir projenin son taramadaki process'leri
--   SELECT p.process_id, p.process_name, p.folder_name, p.link
--     FROM env.all_processes p
--    WHERE p.scan_run_id = (SELECT max(id) FROM env.exception_scan_run WHERE process_count IS NOT NULL)
--      AND p.project_id = 42
--    ORDER BY p.process_id;
