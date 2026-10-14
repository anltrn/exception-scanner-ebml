-- =====================================================================
--  Exception kullanım envanteri (PostgreSQL 9.5+)
--
--  Mevcut tablolar (değiştirilmez, sadece okunur; FK ile bağlanmaz):
--    env.java_class  (id, fqcn, ...)
--    env.java_method (id, class_id, name, ...)
--
--  Sütun adları sizde farklıysa scanner.properties içindeki db.* ayarlarını güncelleyin.
--
--  Bu tabloları önceki sürümle kurduysanız java_class / java_method FK'lerini kaldırın:
--    ALTER TABLE env.exception_usage DROP CONSTRAINT IF EXISTS exception_usage_class_id_fkey;
--    ALTER TABLE env.exception_usage DROP CONSTRAINT IF EXISTS exception_usage_method_id_fkey;
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

COMMENT ON TABLE  env.exception_usage_type IS 'Kullanım tipleri: ayar dosyasındaki her desenin (pattern.N.* / call.N.*) karşılığı. Tarayıcı her çalıştırmada desenleri bu tabloya ekler veya günceller (silmez); elle de kayıt eklenebilir.';
COMMENT ON COLUMN env.exception_usage_type.type_code IS 'Kullanım tipi kodu: pattern.N.type / call.N.type, verilmediyse desen adından üretilir (ör. KOD0_SERBEST_METIN); büyük harf, rakam ve _ (en fazla 50). pattern.N tanımlanmadan exception sınıfı arandığında tüm kullanımlar TUM_KULLANIMLAR koduyla yazılır';
COMMENT ON COLUMN env.exception_usage_type.type_name IS 'Desenin adı (pattern.N.name / call.N.name), ör. "Kod 0 + serbest metin"; ad verilmediyse "Desen N (...)" / "Çağrı N (...)", TUM_KULLANIMLAR için "Tüm kullanımlar"';
COMMENT ON COLUMN env.exception_usage_type.action IS 'Bu tipteki kullanımlar için yapılacak iş (pattern.N.action / call.N.action); verilmediyse desenlerde "Yeni yapıya çevrilmeli", çağrılarda "Merkezi hata yapısına taşınmalı", TUM_KULLANIMLAR için "İncelenmeli". Sadece elle eklenen kayıtlarda boş olabilir';
COMMENT ON COLUMN env.exception_usage_type.args_spec IS 'Desenin argüman tanımı: exception desenlerinde pattern.N.args (ör. 0,STRING,*), çağrılarda kapsam.metot(argümanlar) (ör. outBag.put(GENERALERRORCODE.ERROR_CODE,ANY)); TUM_KULLANIMLAR için *';
COMMENT ON COLUMN env.exception_usage_type.source_kind IS 'Desenin türü: EXCEPTION (new Sinif(...) ile exception oluşturma, pattern.N) veya CALL (metot çağrısı, call.N)';
COMMENT ON COLUMN env.exception_usage_type.is_legacy IS 'true (varsayılan): eski, değiştirilmesi gereken kullanım; false: doğru kullanım, sadece takip için sayılır (pattern.N.legacy=false / call.N.legacy=false)';
COMMENT ON COLUMN env.exception_usage_type.updated_at IS 'Kaydın tarayıcı tarafından son eklendiği veya güncellendiği an (desen ayar dosyasında oldukça her çalıştırmada yenilenir)';

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

-- screen_count, popup_count, region_count, report_count ve process_count sütunları
-- (açıklamalarıyla birlikte) sql/ebml_inventory_postgres.sql ile eklenir.
COMMENT ON TABLE  env.exception_scan_run IS 'Tarayıcının her çalıştırması için bir kayıt. Kullanımlar, tarama hataları ve ekran/popup/region/rapor/process envanteri bu kayda bağlıdır.';
COMMENT ON COLUMN env.exception_scan_run.id IS 'Tarama numarası; diğer tablolardaki scan_run_id bu değeri gösterir';
COMMENT ON COLUMN env.exception_scan_run.started_at IS 'Taramanın başladığı an (repoların listelenmeye başlandığı an)';
COMMENT ON COLUMN env.exception_scan_run.finished_at IS 'Taramanın sonuçları veritabanına yazılıp bittiği an; görünümler sadece dolu olan (bitmiş) taramaları kullanır';
COMMENT ON COLUMN env.exception_scan_run.source IS 'Repoların alındığı kaynak: SERVER (Bitbucket Server/Data Center), CLOUD (Bitbucket Cloud), GIT (git adresi) veya LOCAL (yerel klasör)';
COMMENT ON COLUMN env.exception_scan_run.repo_count IS 'Taramaya alınan repo sayısı: bitbucket.exclude.repos ile hariç tutulanlar sayılmaz, klonlanamayanlar (CLONE hatası) sayılır';
COMMENT ON COLUMN env.exception_scan_run.java_file_count IS 'Repolarda bulunan .java dosyası sayısı (scan.max.file.kb sınırı nedeniyle atlananlar dahil); sınıf veya çağrı aranmadıysa (sadece envanter) 0';
COMMENT ON COLUMN env.exception_scan_run.usage_count IS 'Bu taramada env.exception_usage tablosuna yazılan kullanım sayısı';
COMMENT ON COLUMN env.exception_scan_run.exception_classes IS 'Aranan exception sınıflarının tam adları (exception.classes), virgülle ayrılmış; sınıf aranmadıysa (sadece call.N çağrı desenleri veya sadece envanter) boş metin';
COMMENT ON COLUMN env.exception_scan_run.note IS '--db-note parametresi veya db.note ayarıyla verilen açıklama; verilmediyse boş';
COMMENT ON COLUMN env.exception_scan_run.run_by IS 'Taramayı çalıştıran işletim sistemi kullanıcısı';
COMMENT ON COLUMN env.exception_scan_run.host IS 'Taramanın çalıştığı makinenin adı';

-- ---------------------------------------------------------------------
--  Exception kullanımları
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS env.exception_usage (
    id               bigserial    PRIMARY KEY,
    scan_run_id      bigint       NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    -- Tarama anındaki env.java_class / env.java_method id'leri. FK yok: o tablolar her
    -- yüklemede TRUNCATE + INSERT ile yenilendiği için FK yüklemeyi engeller ve id'ler
    -- sonraki yüklemede değişebilir. Kalıcı eşleştirme için class_fqcn ve method_signature
    -- sütunlarındaki metin kullanılmalı (bkz. aşağıdaki örnek sorgu).
    class_id         bigint,
    method_id        bigint,
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
    class_fqcn       varchar(1000) NOT NULL,           -- kodda bulunan sınıf (anonim sınıf dahil)
    method_signature varchar(1000),                    -- kodda bulunan metot: getRate(String, String)
    exception_class  varchar(300),                     -- CSException, alt sınıfı veya put(...)
    error_code       integer,                          -- new CSException(0, ...) -> 0
    message          text,                             -- koddaki mesaj / şablon
    usage_context    varchar(50),                      -- throw | return | değişkene atama | parametre olarak | diğer | metot çağrısı
    is_test          boolean      NOT NULL DEFAULT false,
    code_snippet     text,
    link             varchar(2000),
    created_at       timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_exception_usage_method_needs_class CHECK (method_id IS NULL OR class_id IS NOT NULL)
);

COMMENT ON TABLE  env.exception_usage IS 'Exception kullanımları: desenlere uyan her exception oluşturma (new ...) veya metot çağrısı (call.N) yeri için bir kayıt, bulunduğu sınıf ve metodun envanterdeki id''leriyle. Her kayıt bir taramaya bağlıdır; son tarama için env.v_exception_usage_latest kullanılır.';
COMMENT ON COLUMN env.exception_usage.id IS 'Kayıt numarası (otomatik artan)';
COMMENT ON COLUMN env.exception_usage.scan_run_id IS 'Kaydın ait olduğu tarama (env.exception_scan_run.id); tarama kaydı silinince bu kayıtlar da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.exception_usage.class_id IS 'Tarama anında kullanımın bulunduğu sınıfın env.java_class id''si (db.class.*); CLASS_NOT_FOUND ise boş. FK yok, envanter yeniden yüklenince değişebilir: kalıcı eşleştirme için class_fqcn kullanılmalı';
COMMENT ON COLUMN env.exception_usage.method_id IS 'Tarama anında kullanımın bulunduğu metodun env.java_method id''si (db.method.*); METHOD_NOT_FOUND, NO_METHOD ve CLASS_NOT_FOUND ise boş. FK yok; class_id boşken dolu olamaz';
COMMENT ON COLUMN env.exception_usage.usage_type_code IS 'Kullanımın uyduğu desenin tip kodu, env.exception_usage_type.type_code değerine bağlı (FK); ör. KOD0_SERBEST_METIN, pattern.N tanımlanmadıysa TUM_KULLANIMLAR';
COMMENT ON COLUMN env.exception_usage.match_status IS 'Envanter eşleştirmesinin sonucu: MATCHED (sınıf ve metot bulundu), METHOD_AMBIGUOUS (aynı adda birden fazla metot, en küçük id seçildi), METHOD_NOT_FOUND (sınıf bulundu, metot bulunamadı), NO_METHOD (kullanım alan tanımında veya metot dışında), CLASS_AMBIGUOUS (aynı FQCN birden fazla kayıtta, en küçük id seçildi), CLASS_NOT_FOUND (sınıf envanterde yok; db.insert.unmatched=false ise bu kullanımlar yazılmaz)';
COMMENT ON COLUMN env.exception_usage.match_note IS 'Eşleştirme sonucunun açıklaması, ör. CLASS_NOT_FOUND için denenen adlar ("Aranan: a.b.Dis$Ic, a.b.Dis.Ic, a.b.Dis"), overload seçimi, dış sınıfa veya anonim sınıfı içeren sınıfa düşülmesi; açıklama yoksa boş';
COMMENT ON COLUMN env.exception_usage.project_key IS 'Reponun projesi: Bitbucket proje anahtarı; source=GIT için adresteki sahip (owner, bulunamazsa GIT), source=LOCAL için klasör yapısından çıkarılan proje adı';
COMMENT ON COLUMN env.exception_usage.repo IS 'Repo slug''ı (Bitbucket/git adresindeki repo adı; source=LOCAL için repo klasörünün adı)';
COMMENT ON COLUMN env.exception_usage.module IS 'Dosyanın modülü: pom.xml, build.gradle, build.xml veya .project bulunan en yakın üst klasörün repo içindeki yolu; repo kökündeyse (kök)';
COMMENT ON COLUMN env.exception_usage.file_path IS 'Kullanımın bulunduğu .java dosyasının repo içindeki yolu, / ayraçlı (ör. src/main/java/a/b/Dis.java)';
COMMENT ON COLUMN env.exception_usage.line_no IS 'Kullanımın (new ... ifadesi veya metot çağrısı) başladığı satır; bilinmiyorsa 0';
COMMENT ON COLUMN env.exception_usage.class_fqcn IS 'Kullanımın kodda bulunduğu sınıfın paketle birlikte tam adı; iç sınıflar nokta ile, anonim sınıflar <anonim Tip> olarak yazılır (ör. a.b.Dis.Ic.<anonim Runnable>). Envanterle kalıcı eşleştirme bu sütunla yapılmalı';
COMMENT ON COLUMN env.exception_usage.method_signature IS 'Kullanımın kodda bulunduğu üye: metot imzası (ör. getRate(String, String)), constructor için "Ad(String) [constructor]", bloklarda <static blok> / <instance blok>, alan tanımında <alan: ad>; üye yoksa boş';
COMMENT ON COLUMN env.exception_usage.exception_class IS 'Oluşturulan exception sınıfının basit adı (exception.classes''taki sınıf veya repoda ondan türeyen alt sınıf, ör. CSException); çağrı desenlerinde çağrının kısa hali (ör. outBag.put(...))';
COMMENT ON COLUMN env.exception_usage.error_code IS 'Argümanlardaki ilk tam sayı sabiti (new CSException(0, ...) -> 0, put(KOD, -1) -> -1); sayı argümanı yoksa veya int sınırını aşıyorsa boş';
COMMENT ON COLUMN env.exception_usage.message IS 'Koddaki mesaj: ilk string argüman; birleştirmelerde değişkenler {0}, {1} ile gösterilir ("Kayıt bulunamadı: " + id -> Kayıt bulunamadı: {0}), String.format / MessageFormat.format çağrılarında biçim metni. Mesaj argümanı yoksa boş metin';
COMMENT ON COLUMN env.exception_usage.usage_context IS 'Kullanımın bağlamı: exception oluşturmada throw, return, değişkene atama, parametre olarak veya diğer; call.N desenlerinde metot çağrısı';
COMMENT ON COLUMN env.exception_usage.is_test IS 'Dosya test kodunda mı: yolunda src/test, test veya tests klasörü varsa true';
COMMENT ON COLUMN env.exception_usage.code_snippet IS 'Kullanımın kaynak kodu (new ... ifadesi veya metot çağrısı), boşluklar teke indirilmiş; 500 karakterden uzunsa kesilip sonuna ... eklenir';
COMMENT ON COLUMN env.exception_usage.link IS 'Dosyayı ilgili satırda açan Bitbucket (source=GIT ise GitHub/GitLab) bağlantısı, taranan commit''e sabitlenmiş; source=LOCAL veya bağlantı üretilemeyen git adreslerinde boş';
COMMENT ON COLUMN env.exception_usage.created_at IS 'Kaydın eklendiği an (now()); tarama tek transaction''da yazıldığı için transaction''ın başladığı an olur ve aynı taramanın tüm kayıtlarında aynıdır';

CREATE INDEX IF NOT EXISTS ix_exception_usage_run    ON env.exception_usage (scan_run_id);
CREATE INDEX IF NOT EXISTS ix_exception_usage_class  ON env.exception_usage (class_id);
CREATE INDEX IF NOT EXISTS ix_exception_usage_method ON env.exception_usage (method_id);
CREATE INDEX IF NOT EXISTS ix_exception_usage_type   ON env.exception_usage (usage_type_code);

-- ---------------------------------------------------------------------
--  Tarama sırasında karşılaşılan hatalar (Excel'deki "Tarama Hataları"
--  sayfasının karşılığı). Kullanımlarla aynı transaction'da yazılır.
--  stage:
--    CLONE          repo klonlanamadı / güncellenemedi (repo hiç taranmadı)
--    SCAN           repo taranırken beklenmeyen hata
--    OUT_OF_MEMORY  repo için bellek yetmedi
--    FILE_LISTING   repodaki dosyalar listelenemedi
--    EBML_LISTING   EBML / rapor / process dosyaları listelenemedi
--    PARSE          Java dosyası ayrıştırılamadı (kısmen taranmış olabilir)
--    READ           dosya okunamadı
--    SKIPPED        dosya scan.max.file.kb sınırından büyük, taranmadı
--    PROCESS_LABEL  processdefinition.xml okunamadı veya label özelliği yok
--  Önceki sürümün Türkçe değerleri (KLONLAMA, TARAMA, BELLEK, DOSYA_LISTELEME,
--  EBML_LISTELEME, OKUMA, ATLANDI) aşağıdaki UPDATE ile bu kodlara çevrilir.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS env.exception_scan_error (
    id           bigserial     PRIMARY KEY,
    scan_run_id  bigint        NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_key  varchar(100),
    repo         varchar(200),
    stage        varchar(30)   NOT NULL,
    file_path    varchar(1000),                        -- dosya bazlı hatalarda repo içindeki yol
    message      text,
    created_at   timestamptz   NOT NULL DEFAULT now()
);

COMMENT ON TABLE  env.exception_scan_error IS 'Tarama sırasında karşılaşılan hatalar (Excel''deki "Tarama Hataları" sayfasının karşılığı): klonlanamayan repo, ayrıştırılamayan dosya vb. Kullanımlarla aynı transaction''da yazılır; son tarama için env.v_exception_scan_error_latest kullanılır.';
COMMENT ON COLUMN env.exception_scan_error.id IS 'Kayıt numarası (otomatik artan)';
COMMENT ON COLUMN env.exception_scan_error.scan_run_id IS 'Hatanın oluştuğu tarama (env.exception_scan_run.id); tarama kaydı silinince bu kayıtlar da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.exception_scan_error.project_key IS 'Hatanın oluştuğu reponun projesi: Bitbucket proje anahtarı; source=GIT için adresteki sahip (owner, bulunamazsa GIT), source=LOCAL için klasör yapısından çıkarılan proje adı';
COMMENT ON COLUMN env.exception_scan_error.repo IS 'Hatanın oluştuğu reponun slug''ı (source=LOCAL için repo klasörünün adı)';
COMMENT ON COLUMN env.exception_scan_error.stage IS 'Hatanın türü: CLONE (repo klonlanamadı / güncellenemedi, repo hiç taranmadı), SCAN (repo taranırken beklenmeyen hata), OUT_OF_MEMORY (repo için bellek yetmedi), FILE_LISTING (repodaki dosyalar listelenemedi), EBML_LISTING (EBML / rapor / process dosyaları listelenemedi), PARSE (Java dosyası ayrıştırılamadı, kısmen taranmış olabilir), READ (dosya okunamadı), SKIPPED (dosya scan.max.file.kb sınırından büyük, taranmadı), PROCESS_LABEL (processdefinition.xml okunamadı veya label özelliği yok)';
COMMENT ON COLUMN env.exception_scan_error.file_path IS 'Dosya bazlı hatalarda (PARSE, READ, SKIPPED, PROCESS_LABEL) dosyanın repo içindeki yolu; repo bazlı hatalarda (CLONE, SCAN, OUT_OF_MEMORY, FILE_LISTING, EBML_LISTING) boş';
COMMENT ON COLUMN env.exception_scan_error.message IS 'Hata mesajı: Java hatasının mesajı, ayrıştırma hatasının açıklaması (en fazla 400 karakter) veya tarayıcının açıklaması (ör. "Dosya çok büyük (3000 KB)..."); mesaj yoksa boş metin';
COMMENT ON COLUMN env.exception_scan_error.created_at IS 'Kaydın eklendiği an (now()); tarama tek transaction''da yazıldığı için transaction''ın başladığı an olur ve aynı taramanın tüm kayıtlarında aynıdır';

CREATE INDEX IF NOT EXISTS ix_exception_scan_error_run ON env.exception_scan_error (scan_run_id, stage);

-- Önceki sürümün yazdığı Türkçe stage değerleri İngilizce kodlara çevrilir. Sadece eski değerli
-- satırlara dokunur, tekrar çalıştırıldığında değişecek satır kalmaz (PARSE ve PROCESS_LABEL aynı).
UPDATE env.exception_scan_error
   SET stage = CASE stage
                   WHEN 'KLONLAMA'        THEN 'CLONE'
                   WHEN 'TARAMA'          THEN 'SCAN'
                   WHEN 'BELLEK'          THEN 'OUT_OF_MEMORY'
                   WHEN 'DOSYA_LISTELEME' THEN 'FILE_LISTING'
                   WHEN 'EBML_LISTELEME'  THEN 'EBML_LISTING'
                   WHEN 'OKUMA'           THEN 'READ'
                   WHEN 'ATLANDI'         THEN 'SKIPPED'
               END
 WHERE stage IN ('KLONLAMA', 'TARAMA', 'BELLEK', 'DOSYA_LISTELEME', 'EBML_LISTELEME', 'OKUMA', 'ATLANDI');

-- ---------------------------------------------------------------------
--  Görünümler
-- ---------------------------------------------------------------------

-- Görünümler sütun listesini oluşturuldukları anda sabitler (u.* dahil). Tablo değiştiğinde
-- eski sütun adlarıyla kalmasınlar diye her çalıştırmada silinip yeniden oluşturulur.
DROP VIEW IF EXISTS env.v_exception_usage_summary;
DROP VIEW IF EXISTS env.v_exception_usage_trend;
DROP VIEW IF EXISTS env.v_exception_usage_latest;
DROP VIEW IF EXISTS env.v_exception_scan_error_latest;

-- Son taramanın kullanımları, sınıf ve metot bilgileriyle
CREATE VIEW env.v_exception_usage_latest AS
SELECT u.*,
       t.type_name,
       t.action,
       t.is_legacy
  FROM env.exception_usage u
  JOIN env.exception_usage_type t ON t.type_code = u.usage_type_code
 WHERE u.scan_run_id = (SELECT max(id) FROM env.exception_scan_run WHERE finished_at IS NOT NULL);

-- Son tarama: proje ve kullanım tipine göre sayılar (ekiplere gönderilecek özet)
CREATE VIEW env.v_exception_usage_summary AS
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
CREATE VIEW env.v_exception_usage_trend AS
SELECT r.id          AS scan_run_id,
       r.started_at,
       u.project_key,
       u.usage_type_code,
       count(*)      AS usage_count
  FROM env.exception_scan_run r
  JOIN env.exception_usage u ON u.scan_run_id = r.id
 WHERE r.finished_at IS NOT NULL
 GROUP BY r.id, r.started_at, u.project_key, u.usage_type_code;

-- Son taramanın hataları
CREATE VIEW env.v_exception_scan_error_latest AS
SELECT e.*
  FROM env.exception_scan_error e
 WHERE e.scan_run_id = (SELECT max(id) FROM env.exception_scan_run WHERE finished_at IS NOT NULL);

-- Örnek: son taramada repo ve hata türüne göre hata sayıları
--   SELECT project_key, repo, stage, count(*) FROM env.v_exception_scan_error_latest
--    GROUP BY project_key, repo, stage ORDER BY count(*) DESC;

-- Örnek sorgu: son taramadaki eski kullanımlar, sınıf ve metot envanteriyle.
-- id'ler envanter yeniden yüklenince değişebileceği için sınıf adıyla ve metot adıyla
-- (imzanın "(" öncesi) eşleştirilir.
--   SELECT c.fqcn, m.name, u.usage_type_code, u.line_no, u.message
--     FROM env.v_exception_usage_latest u
--     JOIN env.java_class  c ON c.fqcn = u.class_fqcn
--     LEFT JOIN env.java_method m ON m.class_id = c.id
--                               AND split_part(m.name, '(', 1) = split_part(u.method_signature, '(', 1)
--    WHERE u.is_legacy
--    ORDER BY c.fqcn, u.line_no;

-- Eski taramaları temizlemek için (kullanımlar ve hatalar CASCADE ile silinir):
--   DELETE FROM env.exception_scan_run WHERE started_at < now() - interval '180 days';
