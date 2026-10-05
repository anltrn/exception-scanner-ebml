-- =====================================================================
--  Ekran, region ve Jasper rapor envanteri (PostgreSQL 9.6+)
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

-- ---------------------------------------------------------------------
--  Ortak sütunlar (dört tabloda aynı):
--    project_id    : env.project.id; proje bulunamazsa boş
--    project_name  : env.project tablosunda aranan ana proje adı
--    project_match : MATCHED | PROJECT_AMBIGUOUS (en küçük id seçildi) | PROJECT_NOT_FOUND
--    file_name     : dosya adı (ebml.file.name.with.extension=false ise uzantısız)
--    file_path     : repo içindeki yol
--    link          : Bitbucket'ta dosyayı açan bağlantı (taranan commit'e sabitlenmiş)
--    created_at    : kaydın eklendiği tarih
-- ---------------------------------------------------------------------

-- Ekranlar: ebml.page paketi (ve alt paketleri) altındaki .ebml dosyaları
CREATE TABLE IF NOT EXISTS env.screens (
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
CREATE TABLE IF NOT EXISTS env.popups (
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
CREATE TABLE IF NOT EXISTS env.regions (
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
CREATE TABLE IF NOT EXISTS env.jasper_reports (
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

CREATE INDEX IF NOT EXISTS ix_screens_run         ON env.screens (scan_run_id);
CREATE INDEX IF NOT EXISTS ix_screens_project     ON env.screens (project_id);
CREATE INDEX IF NOT EXISTS ix_popups_run          ON env.popups (scan_run_id);
CREATE INDEX IF NOT EXISTS ix_popups_project      ON env.popups (project_id);
CREATE INDEX IF NOT EXISTS ix_regions_run         ON env.regions (scan_run_id);
CREATE INDEX IF NOT EXISTS ix_regions_project     ON env.regions (project_id);
CREATE INDEX IF NOT EXISTS ix_jasper_reports_run     ON env.jasper_reports (scan_run_id);
CREATE INDEX IF NOT EXISTS ix_jasper_reports_project ON env.jasper_reports (project_id);

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
    SELECT 'SCREEN' AS kind, project_id, project_name, project_match FROM env.screens        WHERE scan_run_id = (SELECT id FROM last_run)
    UNION ALL
    SELECT 'POPUP',          project_id, project_name, project_match FROM env.popups         WHERE scan_run_id = (SELECT id FROM last_run)
    UNION ALL
    SELECT 'REGION',         project_id, project_name, project_match FROM env.regions        WHERE scan_run_id = (SELECT id FROM last_run)
    UNION ALL
    SELECT 'REPORT',         project_id, project_name, project_match FROM env.jasper_reports WHERE scan_run_id = (SELECT id FROM last_run)
)
SELECT project_id,
       project_name,
       project_match,
       count(*) FILTER (WHERE kind = 'SCREEN') AS screen_count,
       count(*) FILTER (WHERE kind = 'POPUP')  AS popup_count,
       count(*) FILTER (WHERE kind = 'REGION') AS region_count,
       count(*) FILTER (WHERE kind = 'REPORT') AS report_count
  FROM files
 GROUP BY project_id, project_name, project_match;

-- Örnek sorgu: bir projenin son taramadaki ekranları
--   SELECT s.file_name, s.package_name, s.link
--     FROM env.screens s
--    WHERE s.scan_run_id = (SELECT max(id) FROM env.exception_scan_run WHERE screen_count IS NOT NULL)
--      AND s.project_id = 42
--    ORDER BY s.package_name, s.file_name;
