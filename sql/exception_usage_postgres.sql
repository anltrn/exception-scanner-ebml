-- =====================================================================
--  Exception kullanım envanteri (PostgreSQL 9.5+)
--
--  Mevcut tablolar (değiştirilmez, sadece okunur ve FK ile referans verilir):
--    env.java_class  (id, fqcn, ...)
--    env.java_method (id, class_id, name, ...)
--
--  Sütun adları sizde farklıysa aşağıdaki REFERENCES satırlarını ve
--  scanner.properties içindeki db.* ayarlarını birlikte güncelleyin.
--  java_class.id / java_method.id integer ise de sorun yok: bigint sütun
--  integer sütuna FK ile bağlanabilir.
-- =====================================================================

-- ---------------------------------------------------------------------
--  Kullanım tipleri: tarayıcıdaki desenlerin karşılığı
--  (pattern.N.type / call.N.type). Tarayıcı her çalışmada bu tabloyu
--  ayar dosyasındaki desenlerle günceller; elle de kayıt eklenebilir.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS env.exception_usage_type (
    type_code     varchar(50)  PRIMARY KEY,           -- KOD0_SERBEST_METIN
    type_name     varchar(200) NOT NULL,              -- "Kod 0 + serbest metin"
    action        varchar(200),                       -- "Yeni yapıya çevrilmeli"
    args_spec     varchar(500),                       -- "0,STRING,*" veya "put(GENERALERRORCODE.ERROR_CODE,ANY)"
    source_kind   varchar(20)  NOT NULL DEFAULT 'EXCEPTION',  -- EXCEPTION | CALL
    is_legacy     boolean      NOT NULL DEFAULT true,  -- false: doğru kullanım (takip amaçlı sayılır)
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
--  Tarama çalıştırmaları: her çalıştırma bir kayıt. Kullanımlar bu
--  kayda bağlı tutulur; böylece aynı tarama iki kez çalışınca veri
--  çoğalmaz, haftadan haftaya ilerleme de karşılaştırılabilir.
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
    note               text,                           -- --db-note ile verilen açıklama
    run_by             varchar(100),
    host               varchar(200)
);

-- ---------------------------------------------------------------------
--  Exception kullanımları
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS env.exception_usage (
    id               bigserial    PRIMARY KEY,
    scan_run_id      bigint       NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    class_id         bigint       REFERENCES env.java_class (id),
    method_id        bigint       REFERENCES env.java_method (id),
    usage_type_code  varchar(50)  NOT NULL REFERENCES env.exception_usage_type (type_code),

    -- Eşleştirme sonucu:
    --   MATCHED           sınıf ve metot bulundu
    --   METHOD_AMBIGUOUS  aynı adda birden fazla metot, en küçük id seçildi
    --   METHOD_NOT_FOUND  sınıf bulundu, metot bulunamadı (method_id boş)
    --   NO_METHOD         kullanım bir alan tanımında, metot yok (method_id boş)
    --   CLASS_AMBIGUOUS   aynı FQCN birden fazla kayıtta, ayırt edilemedi
    --   CLASS_NOT_FOUND   sınıf bulunamadı (class_id ve method_id boş)
    match_status     varchar(30)  NOT NULL,
    match_note       varchar(1000),

    project_key      varchar(100),
    repo             varchar(200),
    module           varchar(300),
    file_path        varchar(1000) NOT NULL,
    line_no          integer      NOT NULL,
    class            varchar(1000) NOT NULL,           -- kodda bulunan sınıf (anonim sınıf dahil)
    method_signature varchar(1000),                    -- kodda bulunan metot: getRate(String, String)
    exception_class  varchar(300),                     -- CSException, alt sınıfı veya put(...)
    error_code       integer,                          -- new CSException(0, ...) -> 0
    message          text,                             -- koddaki mesaj / şablon
    usage_context    varchar(50),                      -- throw | return | değişkene atama | metot çağrısı
    is_test          boolean      NOT NULL DEFAULT false,
    code_snippet     text,
    link             varchar(2000),
    created_at       timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_exception_usage_method_needs_class CHECK (method_id IS NULL OR class_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS ix_exception_usage_run    ON env.exception_usage (scan_run_id);
CREATE INDEX IF NOT EXISTS ix_exception_usage_class  ON env.exception_usage (class_id);
CREATE INDEX IF NOT EXISTS ix_exception_usage_method ON env.exception_usage (method_id);
CREATE INDEX IF NOT EXISTS ix_exception_usage_type   ON env.exception_usage (usage_type_code);

-- ---------------------------------------------------------------------
--  Görünümler
-- ---------------------------------------------------------------------

-- Son taramanın kullanımları, sınıf ve metot bilgileriyle
CREATE OR REPLACE VIEW env.v_exception_usage_latest AS
SELECT u.*,
       t.type_name,
       t.action,
       t.is_legacy
  FROM env.exception_usage u
  JOIN env.exception_usage_type t ON t.type_code = u.usage_type_code
 WHERE u.scan_run_id = (SELECT max(id) FROM env.exception_scan_run WHERE finished_at IS NOT NULL);

-- Son tarama: proje ve kullanım tipine göre sayılar (ekiplere gönderilecek özet)
CREATE OR REPLACE VIEW env.v_exception_usage_summary AS
SELECT project_key,
       repo,
       usage_type_code,
       type_name,
       is_legacy,
       count(*)                                          AS usage_count,
       count(*) FILTER (WHERE NOT is_test)               AS non_test_count,
       count(*) FILTER (WHERE match_status <> 'MATCHED') AS unmatched_count
  FROM env.v_exception_usage_latest
 GROUP BY project_key, repo, usage_type_code, type_name, is_legacy;

-- Taramadan taramaya ilerleme: eski kullanım sayısı azalmalı
CREATE OR REPLACE VIEW env.v_exception_usage_trend AS
SELECT r.id          AS scan_run_id,
       r.started_at,
       u.project_key,
       u.usage_type_code,
       count(*)      AS usage_count
  FROM env.exception_scan_run r
  JOIN env.exception_usage u ON u.scan_run_id = r.id
 WHERE r.finished_at IS NOT NULL
 GROUP BY r.id, r.started_at, u.project_key, u.usage_type_code;

-- Örnek sorgu: bir metotta hangi eski kullanımlar var?
--   SELECT c.fqcn, m.name, u.usage_type_code, u.line_no, u.message
--     FROM env.v_exception_usage_latest u
--     JOIN env.java_class  c ON c.id = u.class_id
--     JOIN env.java_method m ON m.id = u.method_id
--    WHERE u.is_legacy
--    ORDER BY c.fqcn, u.line_no;

-- Eski taramaları temizlemek için (kullanımlar CASCADE ile silinir):
--   DELETE FROM env.exception_scan_run WHERE started_at < now() - interval '180 days';
