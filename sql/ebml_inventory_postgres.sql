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
COMMENT ON COLUMN env.exception_scan_run.screen_count IS 'Bu taramada env.all_screens tablosuna yazılan ekran sayısı; tarama ebml.enabled=true (--ebml) olmadan çalıştıysa boş. env.v_ebml_inventory_latest son envanter taramasını bu sütunun dolu olmasından bulur';
COMMENT ON COLUMN env.exception_scan_run.popup_count IS 'Bu taramada env.all_popups tablosuna yazılan popup sayısı; tarama ebml.enabled=true (--ebml) olmadan çalıştıysa boş';
COMMENT ON COLUMN env.exception_scan_run.region_count IS 'Bu taramada env.all_regions tablosuna yazılan region sayısı; tarama ebml.enabled=true (--ebml) olmadan çalıştıysa boş';
COMMENT ON COLUMN env.exception_scan_run.report_count IS 'Bu taramada env.all_reports tablosuna yazılan Jasper rapor sayısı; tarama ebml.enabled=true (--ebml) olmadan çalıştıysa boş';
COMMENT ON COLUMN env.exception_scan_run.process_count IS 'Bu taramada env.all_processes tablosuna yazılan process sayısı; tarama ebml.enabled=true (--ebml) olmadan çalıştıysa boş';

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
--    file_name     : dosya adı (ebml.file.name.with.extension=false ise uzantısız; env.all_processes'te yok,
--                    orada klasör adı folder_name sütunundadır)
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
COMMENT ON TABLE  env.all_screens               IS 'Ekran envanteri: ebml.page paketi (ebml.page.package) ve alt paketleri altındaki .ebml dosyaları; ebml.enabled=true (--ebml) ile yapılan her taramada dosya başına bir kayıt. Adı RG_ ile başlayan dosyalar ekran değil region sayılır (env.all_regions).';
COMMENT ON COLUMN env.all_screens.id            IS 'Kayıt numarası; otomatik artar';
COMMENT ON COLUMN env.all_screens.scan_run_id   IS 'Kaydın ait olduğu tarama: env.exception_scan_run.id. Tarama kaydı silinince bu kayıt da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.all_screens.project_id    IS 'Ekranın ana projesinin env.project.id değeri; project_name, env.project.project_name sütununda aranarak bulunur. Proje bulunamazsa (PROJECT_NOT_FOUND) boş; ebml.insert.unmatched=false ise projesi bulunamayanlar tabloya hiç yazılmaz';
COMMENT ON COLUMN env.all_screens.project_name  IS 'env.project.project_name sütununda aranan ana proje adı: ebml.project.name.from ayarına göre repo adı (REPO, varsayılan), repo slug''ı (REPO_SLUG), modül klasörünün adı (MODULE) veya Bitbucket proje adı (BITBUCKET_PROJECT). İlk ad bulunamayıp ikinci aday (repo slug''ı / proje anahtarı) bulunduysa o yazılır; proje bulunamasa da doludur';
COMMENT ON COLUMN env.all_screens.project_match IS 'Proje eşleştirme sonucu: MATCHED (env.project''te tek kayıt bulundu), PROJECT_AMBIGUOUS (aynı adda birden fazla proje var, en küçük id seçildi) veya PROJECT_NOT_FOUND (bulunamadı, project_id boş). Ad karşılaştırması varsayılan olarak büyük/küçük harf duyarsızdır (ebml.project.case.insensitive)';
COMMENT ON COLUMN env.all_screens.repo          IS 'Dosyanın bulunduğu reponun slug''ı (Bitbucket repo slug''ı; git kaynağında adresteki repo adı, yerel kaynakta klasör adı)';
COMMENT ON COLUMN env.all_screens.module        IS 'Dosyanın bulunduğu modül: pom.xml, build.gradle(.kts), build.xml veya .project bulunan en yakın üst klasörün repo içindeki yolu, ör. musteri-web; bu klasör repo köküyse veya hiç yoksa ''(kök)'' yazılır';
COMMENT ON COLUMN env.all_screens.package_name  IS 'Dosyanın klasöründen üretilen paket adı: modül klasörü ve kaynak kökü (ebml.source.roots, ör. src/main/java, src/main/resources) atılıp / yerine . konur, ör. tr.com.x.ebml.page; dosya doğrudan kaynak kökündeyse boş';
COMMENT ON COLUMN env.all_screens.file_name     IS 'Dosya adı, ör. EKRAN001.ebml; ebml.file.name.with.extension=false ise uzantısız (EKRAN001)';
COMMENT ON COLUMN env.all_screens.file_path     IS 'Dosyanın repo içindeki yolu, ör. musteri-web/src/main/java/tr/com/x/ebml/page/EKRAN001.ebml';
COMMENT ON COLUMN env.all_screens.link          IS 'Dosyayı Bitbucket''ta (git kaynağında GitHub/GitLab''da) açan bağlantı, taranan commit''e sabitlenmiş; web adresi bilinmeyen kaynaklarda (yerel klasör, diğer git sunucuları) boş';
COMMENT ON COLUMN env.all_screens.created_at    IS 'Kaydın eklendiği an (DEFAULT now()); kayıtlar tek transaction''da yazıldığı için aynı taramanın tüm kayıtlarında aynıdır';

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
COMMENT ON TABLE  env.all_popups               IS 'Popup envanteri: ebml.popup paketi (ebml.popup.package) ve alt paketleri altındaki .ebml dosyaları; ebml.enabled=true (--ebml) ile yapılan her taramada dosya başına bir kayıt. Adı RG_ ile başlayan dosyalar popup değil region sayılır (env.all_regions).';
COMMENT ON COLUMN env.all_popups.id            IS 'Kayıt numarası; otomatik artar';
COMMENT ON COLUMN env.all_popups.scan_run_id   IS 'Kaydın ait olduğu tarama: env.exception_scan_run.id. Tarama kaydı silinince bu kayıt da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.all_popups.project_id    IS 'Popup''ın ana projesinin env.project.id değeri; project_name, env.project.project_name sütununda aranarak bulunur. Proje bulunamazsa (PROJECT_NOT_FOUND) boş; ebml.insert.unmatched=false ise projesi bulunamayanlar tabloya hiç yazılmaz';
COMMENT ON COLUMN env.all_popups.project_name  IS 'env.project.project_name sütununda aranan ana proje adı: ebml.project.name.from ayarına göre repo adı (REPO, varsayılan), repo slug''ı (REPO_SLUG), modül klasörünün adı (MODULE) veya Bitbucket proje adı (BITBUCKET_PROJECT). İlk ad bulunamayıp ikinci aday (repo slug''ı / proje anahtarı) bulunduysa o yazılır; proje bulunamasa da doludur';
COMMENT ON COLUMN env.all_popups.project_match IS 'Proje eşleştirme sonucu: MATCHED (env.project''te tek kayıt bulundu), PROJECT_AMBIGUOUS (aynı adda birden fazla proje var, en küçük id seçildi) veya PROJECT_NOT_FOUND (bulunamadı, project_id boş). Ad karşılaştırması varsayılan olarak büyük/küçük harf duyarsızdır (ebml.project.case.insensitive)';
COMMENT ON COLUMN env.all_popups.repo          IS 'Dosyanın bulunduğu reponun slug''ı (Bitbucket repo slug''ı; git kaynağında adresteki repo adı, yerel kaynakta klasör adı)';
COMMENT ON COLUMN env.all_popups.module        IS 'Dosyanın bulunduğu modül: pom.xml, build.gradle(.kts), build.xml veya .project bulunan en yakın üst klasörün repo içindeki yolu, ör. musteri-web; bu klasör repo köküyse veya hiç yoksa ''(kök)'' yazılır';
COMMENT ON COLUMN env.all_popups.package_name  IS 'Dosyanın klasöründen üretilen paket adı: modül klasörü ve kaynak kökü (ebml.source.roots, ör. src/main/java, src/main/resources) atılıp / yerine . konur, ör. tr.com.x.ebml.popup; dosya doğrudan kaynak kökündeyse boş';
COMMENT ON COLUMN env.all_popups.file_name     IS 'Dosya adı, ör. pp_deneme.ebml; ebml.file.name.with.extension=false ise uzantısız (pp_deneme)';
COMMENT ON COLUMN env.all_popups.file_path     IS 'Dosyanın repo içindeki yolu, ör. musteri-web/src/main/java/tr/com/x/ebml/popup/pp_deneme.ebml';
COMMENT ON COLUMN env.all_popups.link          IS 'Dosyayı Bitbucket''ta (git kaynağında GitHub/GitLab''da) açan bağlantı, taranan commit''e sabitlenmiş; web adresi bilinmeyen kaynaklarda (yerel klasör, diğer git sunucuları) boş';
COMMENT ON COLUMN env.all_popups.created_at    IS 'Kaydın eklendiği an (DEFAULT now()); kayıtlar tek transaction''da yazıldığı için aynı taramanın tüm kayıtlarında aynıdır';

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
COMMENT ON TABLE  env.all_regions               IS 'Region envanteri: adı RG_ (ebml.region.prefix) ile başlayan veya ebml.region paketi (ebml.region.package) ve alt paketleri altındaki .ebml dosyaları; ebml.enabled=true (--ebml) ile yapılan her taramada dosya başına bir kayıt. Region kuralı ekran/popup kuralından önce uygulanır.';
COMMENT ON COLUMN env.all_regions.id            IS 'Kayıt numarası; otomatik artar';
COMMENT ON COLUMN env.all_regions.scan_run_id   IS 'Kaydın ait olduğu tarama: env.exception_scan_run.id. Tarama kaydı silinince bu kayıt da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.all_regions.project_id    IS 'Region''ın ana projesinin env.project.id değeri; project_name, env.project.project_name sütununda aranarak bulunur. Proje bulunamazsa (PROJECT_NOT_FOUND) boş; ebml.insert.unmatched=false ise projesi bulunamayanlar tabloya hiç yazılmaz';
COMMENT ON COLUMN env.all_regions.project_name  IS 'env.project.project_name sütununda aranan ana proje adı: ebml.project.name.from ayarına göre repo adı (REPO, varsayılan), repo slug''ı (REPO_SLUG), modül klasörünün adı (MODULE) veya Bitbucket proje adı (BITBUCKET_PROJECT). İlk ad bulunamayıp ikinci aday (repo slug''ı / proje anahtarı) bulunduysa o yazılır; proje bulunamasa da doludur';
COMMENT ON COLUMN env.all_regions.project_match IS 'Proje eşleştirme sonucu: MATCHED (env.project''te tek kayıt bulundu), PROJECT_AMBIGUOUS (aynı adda birden fazla proje var, en küçük id seçildi) veya PROJECT_NOT_FOUND (bulunamadı, project_id boş). Ad karşılaştırması varsayılan olarak büyük/küçük harf duyarsızdır (ebml.project.case.insensitive)';
COMMENT ON COLUMN env.all_regions.repo          IS 'Dosyanın bulunduğu reponun slug''ı (Bitbucket repo slug''ı; git kaynağında adresteki repo adı, yerel kaynakta klasör adı)';
COMMENT ON COLUMN env.all_regions.module        IS 'Dosyanın bulunduğu modül: pom.xml, build.gradle(.kts), build.xml veya .project bulunan en yakın üst klasörün repo içindeki yolu, ör. musteri-web; bu klasör repo köküyse veya hiç yoksa ''(kök)'' yazılır';
COMMENT ON COLUMN env.all_regions.package_name  IS 'Dosyanın klasöründen üretilen paket adı: modül klasörü ve kaynak kökü (ebml.source.roots, ör. src/main/java, src/main/resources) atılıp / yerine . konur, ör. tr.com.x.ebml.region; dosya doğrudan kaynak kökündeyse boş';
COMMENT ON COLUMN env.all_regions.file_name     IS 'Dosya adı, ör. RG_Adres.ebml; ebml.file.name.with.extension=false ise uzantısız (RG_Adres)';
COMMENT ON COLUMN env.all_regions.file_path     IS 'Dosyanın repo içindeki yolu, ör. musteri-web/src/main/java/tr/com/x/ebml/region/RG_Adres.ebml';
COMMENT ON COLUMN env.all_regions.link          IS 'Dosyayı Bitbucket''ta (git kaynağında GitHub/GitLab''da) açan bağlantı, taranan commit''e sabitlenmiş; web adresi bilinmeyen kaynaklarda (yerel klasör, diğer git sunucuları) boş';
COMMENT ON COLUMN env.all_regions.match_rule    IS 'Dosyanın region sayılma kuralı: PREFIX (adı ebml.region.prefix ile, varsayılan RG_, başlıyor; harf duyarsız), PACKAGE (ebml.region paketi veya alt paketleri altında) veya PREFIX+PACKAGE (ikisi birden)';
COMMENT ON COLUMN env.all_regions.created_at    IS 'Kaydın eklendiği an (DEFAULT now()); kayıtlar tek transaction''da yazıldığı için aynı taramanın tüm kayıtlarında aynıdır';

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
COMMENT ON TABLE  env.all_reports               IS 'Jasper rapor envanteri: ebml.report paketi (ebml.report.package) ve alt paketleri altındaki .dsxml dosyaları; ebml.enabled=true (--ebml) ile yapılan her taramada dosya başına bir kayıt.';
COMMENT ON COLUMN env.all_reports.id            IS 'Kayıt numarası; otomatik artar';
COMMENT ON COLUMN env.all_reports.scan_run_id   IS 'Kaydın ait olduğu tarama: env.exception_scan_run.id. Tarama kaydı silinince bu kayıt da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.all_reports.project_id    IS 'Raporun ana projesinin env.project.id değeri; project_name, env.project.project_name sütununda aranarak bulunur. Proje bulunamazsa (PROJECT_NOT_FOUND) boş; ebml.insert.unmatched=false ise projesi bulunamayanlar tabloya hiç yazılmaz';
COMMENT ON COLUMN env.all_reports.project_name  IS 'env.project.project_name sütununda aranan ana proje adı: ebml.project.name.from ayarına göre repo adı (REPO, varsayılan), repo slug''ı (REPO_SLUG), modül klasörünün adı (MODULE) veya Bitbucket proje adı (BITBUCKET_PROJECT). İlk ad bulunamayıp ikinci aday (repo slug''ı / proje anahtarı) bulunduysa o yazılır; proje bulunamasa da doludur';
COMMENT ON COLUMN env.all_reports.project_match IS 'Proje eşleştirme sonucu: MATCHED (env.project''te tek kayıt bulundu), PROJECT_AMBIGUOUS (aynı adda birden fazla proje var, en küçük id seçildi) veya PROJECT_NOT_FOUND (bulunamadı, project_id boş). Ad karşılaştırması varsayılan olarak büyük/küçük harf duyarsızdır (ebml.project.case.insensitive)';
COMMENT ON COLUMN env.all_reports.repo          IS 'Dosyanın bulunduğu reponun slug''ı (Bitbucket repo slug''ı; git kaynağında adresteki repo adı, yerel kaynakta klasör adı)';
COMMENT ON COLUMN env.all_reports.module        IS 'Dosyanın bulunduğu modül: pom.xml, build.gradle(.kts), build.xml veya .project bulunan en yakın üst klasörün repo içindeki yolu, ör. musteri-web; bu klasör repo köküyse veya hiç yoksa ''(kök)'' yazılır';
COMMENT ON COLUMN env.all_reports.package_name  IS 'Dosyanın klasöründen üretilen paket adı: modül klasörü ve kaynak kökü (ebml.source.roots, ör. src/main/java, src/main/resources) atılıp / yerine . konur, ör. tr.com.x.ebml.report; dosya doğrudan kaynak kökündeyse boş';
COMMENT ON COLUMN env.all_reports.file_name     IS 'Dosya adı, ör. MUSTERI_LISTESI.dsxml; ebml.file.name.with.extension=false ise uzantısız (MUSTERI_LISTESI)';
COMMENT ON COLUMN env.all_reports.file_path     IS 'Dosyanın repo içindeki yolu, ör. musteri-web/src/main/resources/tr/com/x/ebml/report/MUSTERI_LISTESI.dsxml';
COMMENT ON COLUMN env.all_reports.link          IS 'Dosyayı Bitbucket''ta (git kaynağında GitHub/GitLab''da) açan bağlantı, taranan commit''e sabitlenmiş; web adresi bilinmeyen kaynaklarda (yerel klasör, diğer git sunucuları) boş';
COMMENT ON COLUMN env.all_reports.created_at    IS 'Kaydın eklendiği an (DEFAULT now()); kayıtlar tek transaction''da yazıldığı için aynı taramanın tüm kayıtlarında aynıdır';

-- Process'ler: process klasörünün (ebml.process.dir) hemen altındaki <numara>[-<ad> veya _<ad>][.par] klasörlerindeki
-- processdefinition.xml dosyaları: process/250001-RISM.par ve process/250001-RISM ikisi de process (.par zorunlu değil)
CREATE TABLE IF NOT EXISTS env.all_processes (
    id             bigserial      PRIMARY KEY,
    scan_run_id    bigint         NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_id     bigint         REFERENCES env.project (id),
    project_name   varchar(300),
    project_match  varchar(30)    NOT NULL,
    repo           varchar(200),
    module         varchar(300),
    process_id     bigint         NOT NULL,            -- klasör adının başındaki numara: 250001-RISM.par / 250001-RISM -> 250001
    process_name   varchar(500),                       -- processdefinition.xml içindeki label (Müşteri Değerlendirme)
    folder_name    varchar(500)   NOT NULL,            -- klasör adı olduğu gibi: 250001-RISM.par veya 250001-RISM
    file_path      varchar(1000)  NOT NULL,
    link           varchar(2000),
    created_at     timestamptz    NOT NULL DEFAULT now()
);
COMMENT ON TABLE  env.all_processes               IS 'Process envanteri: process klasörünün (ebml.process.dir, varsayılan process) hemen altındaki <numara>[-<ad> veya _<ad>][.par] adlı klasörlerde bulunan processdefinition.xml dosyaları, ör. process/250001-RISM.par veya process/250001-RISM (.par uzantısı zorunlu değil); ebml.enabled=true (--ebml) ile yapılan her taramada process başına bir kayıt.';
COMMENT ON COLUMN env.all_processes.id            IS 'Kayıt numarası; otomatik artar';
COMMENT ON COLUMN env.all_processes.scan_run_id   IS 'Kaydın ait olduğu tarama: env.exception_scan_run.id. Tarama kaydı silinince bu kayıt da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.all_processes.project_id    IS 'Process''in ana projesinin env.project.id değeri; project_name, env.project.project_name sütununda aranarak bulunur. Proje bulunamazsa (PROJECT_NOT_FOUND) boş; ebml.insert.unmatched=false ise projesi bulunamayanlar tabloya hiç yazılmaz';
COMMENT ON COLUMN env.all_processes.project_name  IS 'env.project.project_name sütununda aranan ana proje adı: ebml.project.name.from ayarına göre repo adı (REPO, varsayılan), repo slug''ı (REPO_SLUG), modül klasörünün adı (MODULE) veya Bitbucket proje adı (BITBUCKET_PROJECT). İlk ad bulunamayıp ikinci aday (repo slug''ı / proje anahtarı) bulunduysa o yazılır; proje bulunamasa da doludur';
COMMENT ON COLUMN env.all_processes.project_match IS 'Proje eşleştirme sonucu: MATCHED (env.project''te tek kayıt bulundu), PROJECT_AMBIGUOUS (aynı adda birden fazla proje var, en küçük id seçildi) veya PROJECT_NOT_FOUND (bulunamadı, project_id boş). Ad karşılaştırması varsayılan olarak büyük/küçük harf duyarsızdır (ebml.project.case.insensitive)';
COMMENT ON COLUMN env.all_processes.repo          IS 'Dosyanın bulunduğu reponun slug''ı (Bitbucket repo slug''ı; git kaynağında adresteki repo adı, yerel kaynakta klasör adı)';
COMMENT ON COLUMN env.all_processes.module        IS 'Dosyanın bulunduğu modül: pom.xml, build.gradle(.kts), build.xml veya .project bulunan en yakın üst klasörün repo içindeki yolu, ör. musteri-web; bu klasör repo köküyse veya hiç yoksa ''(kök)'' yazılır';
COMMENT ON COLUMN env.all_processes.process_id    IS 'Process numarası: klasör adının başındaki sayı (250001-RISM.par veya 250001-RISM -> 250001). Adı <numara>[-<ad> veya _<ad>][.par] biçimine uymayan klasörlerdeki processdefinition.xml process sayılmaz, bu tabloya yazılmaz';
COMMENT ON COLUMN env.all_processes.process_name  IS 'Process adı: processdefinition.xml içindeki label özelliği (ör. Müşteri Değerlendirme); önce kök elemana, yoksa adında process geçen ilk elemana bakılır. Bulunamazsa veya dosya okunamazsa boş ve dosya tarama hatalarına PROCESS_LABEL koduyla yazılır';
COMMENT ON COLUMN env.all_processes.folder_name   IS 'Process klasörünün adı olduğu gibi, ör. 250001-RISM.par veya 250001-RISM (.par uzantısı zorunlu değil)';
COMMENT ON COLUMN env.all_processes.file_path     IS 'processdefinition.xml dosyasının repo içindeki yolu, ör. musteri-web/process/250001-RISM.par/processdefinition.xml';
COMMENT ON COLUMN env.all_processes.link          IS 'Dosyayı Bitbucket''ta (git kaynağında GitHub/GitLab''da) açan bağlantı, taranan commit''e sabitlenmiş; web adresi bilinmeyen kaynaklarda (yerel klasör, diğer git sunucuları) boş';
COMMENT ON COLUMN env.all_processes.created_at    IS 'Kaydın eklendiği an (DEFAULT now()); kayıtlar tek transaction''da yazıldığı için aynı taramanın tüm kayıtlarında aynıdır';

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
