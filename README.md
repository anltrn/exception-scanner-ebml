# Exception Kullanım Tarayıcı

Bitbucket'taki tüm repoları, bir GitHub/GitLab adresini veya bilgisayarınızdaki bir projeyi tarayıp belirttiğiniz exception sınıfının, tanımladığınız argüman şekillerine uyan kullanımlarını (örneğin `throw new CustomException(0, "deneme")`) bulur ve ekiplere gönderilebilecek Excel raporları üretir.

Tarama metin araması değil, Java kaynak kodunu gerçekten ayrıştırarak (JavaParser) yapılır. Bu sayede her kullanımın sınıfı, metodu ve satırı doğru bulunur; yorum satırları ve string içindeki geçişler yanlış sonuç üretmez; exception'a hangi tür argümanla mesaj verildiği de sınıflandırılır.

## Gereksinimler

Tarayıcıyı çalıştıracak makinede JDK 17 veya üstü ve derleme için Maven olmalıdır. Bitbucket veya git adresinden tarama yapılacaksa PATH'te Git 2.31 veya üstü de gerekir; yerel klasör taramasında git gerekmez. Taranan kodun Java 6 olması sorun değildir. Tarayıcı Bitbucket'ta hiçbir şeyi değiştirmez; sadece okuma yetkisi yeterlidir.

## Derleme

```bash
mvn -q package
```

Çıktı: `target/exception-scanner-1.0.0.jar` (tüm bağımlılıklar içinde). Aynı jar komut satırı aracı olarak veya `--server` ile REST API olarak çalışır (bkz. [REST API ve Swagger](#rest-api-ve-swagger)).

## Ayarlar

`scanner.properties` dosyasındaki açıklamalar her ayarı anlatıyor. En önemlileri:

| Ayar | Açıklama |
|---|---|
| `source` | `server` (Bitbucket Server / Data Center), `cloud` (bitbucket.org), `git` (GitHub vb.) veya `local` |
| `bitbucket.url` | Server için Bitbucket adresi |
| `bitbucket.workspace` | Cloud için workspace adı |
| `git.urls` | `source=git` için virgülle ayrılmış git adresleri |
| `local.dir` | `source=local` için taranacak klasör |
| `bitbucket.projects` | Sadece bu projeleri tara (boşsa erişilebilen tüm projeler) |
| `exception.classes` | Aranacak exception'ın tam adı, örn. `com.firma.framework.CustomException` |
| `exception.ignore.classes` | Eski sınıftan türeyen ama yeni yapıya ait olan sınıflar (örn. yeni `AppException`) |
| `pattern.N.args` | Aranacak constructor şekli, örn. `0,STRING` (aşağıya bakın) |
| `scan.throw.only` | `true` ise sadece doğrudan `throw new ...` şeklindekiler |
| `git.branch` | Boşsa varsayılan dal; `develop` gibi bir dal da verilebilir |

Token'ı dosyaya yazmak yerine ortam değişkeniyle verin:

```bash
export BITBUCKET_TOKEN=xxxxxxxx          # Windows: set BITBUCKET_TOKEN=xxxxxxxx
export BITBUCKET_USERNAME=kullanici      # gerekiyorsa
```

Kullanıcı adı boş bırakılırsa `Authorization: Bearer <token>` (Bitbucket Server HTTP access token), doluysa Basic auth kullanılır. Bitbucket Server'da proje ve repo okuma yetkisi olan kişisel bir HTTP access token yeterlidir. Bitbucket Cloud'da depo okuma yetkisi olan bir API token kullanın; git klonlamada kimlik doğrulama sorunu yaşarsanız `git.protocol=ssh` ile makinedeki SSH anahtarını kullanmak en sorunsuz yoldur.

Şirket içi Bitbucket'ın sertifikası JDK tarafından tanınmıyorsa kalıcı çözüm, şirket CA sertifikasını JDK truststore'una eklemektir. Geçici olarak `ssl.insecure=true` da kullanılabilir.

## Çalıştırma

### Hızlı deneme: kendi bilgisayarınızdaki bir proje

Ayar dosyası gerekmez, her şey komut satırından verilebilir:

```bash
java -jar target/exception-scanner-1.0.0.jar \
     --local C:/projeler/musteri-servisi \
     --class com.firma.framework.exception.CustomException \
     --pattern 0,STRING,*
```

`--local` tek bir proje klasörü (git reposu olması gerekmez, zip'ten açılmış bir klasör de olur) veya içinde birden fazla proje bulunan bir klasör olabilir. İçinde `pom.xml`, `build.xml`, `build.gradle`, `.project` ya da `src` bulunan klasör tek proje olarak taranır; bunlar yoksa her alt klasör ayrı bir proje sayılır.

### GitHub, GitLab veya herhangi bir git adresi

```bash
java -jar target/exception-scanner-1.0.0.jar \
     --git https://github.com/sahip/repo \
     --class com.firma.framework.exception.CustomException \
     --pattern 0,STRING,*
```

`--git` birden fazla kez verilebilir. `https://github.com/sahip/repo/tree/develop` şeklindeki adreslerde dal adresten alınır; `--branch` ile de verilebilir. Özel (private) repolarda makinenizdeki git kimlik bilgileri (Git Credential Manager) veya SSH adresi (`git@github.com:sahip/repo.git`) kullanılır. GitHub ve GitLab adreslerinde rapordaki bağlantılar ilgili satırı açar.

### Bitbucket

```bash
java -jar target/exception-scanner-1.0.0.jar scanner.properties
```

Repolar `work.dir` (varsayılan `./scannedRepos`) altına sığ (`--depth 1`) olarak klonlanır; tekrar çalıştırıldığında sadece güncellenir. İlk denemede `bitbucket.projects` ile tek bir projeyle başlamanızı öneririm.

### Komut satırı parametreleri

Parametreler ayar dosyasındaki değerlerin üzerine yazar; ayar dosyası yoksa varsayılanlarla çalışılır. `--pattern` verilirse dosyadaki desenler yok sayılır.

| Parametre | Açıklama |
|---|---|
| `--local <klasör>` | Bilgisayardaki bir projeyi veya proje klasörlerini tara |
| `--git <adres>` | Git adresini klonlayıp tara (birden fazla verilebilir) |
| `--branch <dal>` | Taranacak dal |
| `--class <sınıf>` | Aranacak exception'ın tam adı (birden fazla verilebilir) |
| `--pattern <desen>` | Aranacak argüman şekli, örn. `0,STRING` (birden fazla verilebilir) |
| `--call <çağrı>` | Aranacak metot çağrısı, `[nesne.]metot:argümanlar` (birden fazla verilebilir) |
| `--throw-only` | Sadece doğrudan `throw new ...` şeklindekiler |
| `--lenient` | Import ve paket kontrolü yapmadan sadece sınıf adına göre eşleştir |
| `--threads <n>` | Aynı anda ayrıştırılacak repo sayısı (varsayılan 4) |
| `--git-threads <n>` | Aynı anda klonlanacak/güncellenecek repo sayısı (varsayılan 8) |
| `--out <klasör>` | Raporların yazılacağı klasör (varsayılan `./executeReports`) |
| `--console-limit <n>` | Konsola yazılacak en fazla kullanım (varsayılan 200, `0` = yazma) |
| `--help` | Yardım |

Windows komut satırında satır devamı için `\` yerine `^` kullanın ya da komutu tek satırda yazın. `*` içeren desenleri Linux/macOS'ta tırnak içinde verin: `--pattern "0,STRING,*"`.

## Raporlar

Bulunan kullanımlar (varsayılan olarak ilk 200 tanesi) dosya, satır, sınıf, metot ve kodla birlikte konsola da yazılır; tam liste Excel raporundadır.

`output.dir/yyyyMMdd_HHmm/` altında:

- `exception_scan_report.xlsx`: tüm projeleri içeren ana rapor (açılışta Kullanımlar sayfası görünür)
- `usages.csv`: aynı kullanım listesi, her programda açılabilen CSV olarak (noktalı virgül ayraçlı, UTF-8)
- `projects/report_<PROJE>.xlsx`: her Bitbucket projesi için ayrı rapor, doğrudan ilgili ekibe gönderilebilir

Her raporda şu sayfalar bulunur (Excel'de sekmeler pencerenin altında, Numbers'da üstündedir):

**Özet:** toplam sayılar ve her desen için repo bazında dağılım, en çok eşleşmesi olan repo en üstte.

**Kullanımlar:** desene uyan her kullanım. Proje, repo, modül, dosya, sınıf, metot, satır, eşleşen desen, yapılacak iş, id değeri, mesaj tipi ve metni, kodun kendisi ve Bitbucket'ta ilgili satırı açan bağlantı. Bağlantılar taranan commit'e sabitlendiği için kod değişse de doğru satırı gösterir.

**Mesajlar:** koddaki string mesajların tekilleştirilmiş listesi, kaç yerde kullanıldığıyla birlikte. Sarı sütunlar (önerilen hata kodu, TR ve EN kullanıcı mesajı) doldurulduğunda hata kataloğunun ilk taslağı çıkmış olur. Birleştirilen değişkenler `{0}`, `{1}` şeklinde gösterilir: `"Kayıt bulunamadı: " + id` → `Kayıt bulunamadı: {0}`.

**Catch Blokları** (`report.catch.blocks=true` ise): exception'ı yakalayan bloklar. Boş olanlar (exception'ı yutan) ve yakalayıp akışa devam edenler ayrıca işaretlenir; yeni yapıya geçişte davranışı değişebilecek yerler bunlardır.

**Alt Sınıflar:** projelerde bu exception'dan türetilmiş sınıflar. Aynı repo içindeki kullanımları otomatik olarak taranır. Başka repolarda da kullanılıyorsa sınıfın tam adını `exception.classes`'a ekleyip taramayı tekrarlayın.

**Tarama Hataları:** klonlanamayan repolar ve ayrıştırılamayan dosyalar.

## Desenler

Desenler, constructor'a geçilen argümanların şeklini tarif eder. Sırayla denenir; bir kullanım ilk uyduğu desenle raporlanır, hiçbirine uymayan kullanımlar rapora girmez. Hiç desen tanımlanmazsa sınıfın tüm kullanımları raporlanır.

| Değer | Eşleştiği argüman |
|---|---|
| `0`, `-1`, `99` | Tam olarak bu sayı (`0`, `0L`, `0x0` aynı kabul edilir) |
| `INT` | Herhangi bir sayı |
| `STRING` | Koda yazılmış mesaj: sabit string, `"a" + x` birleştirme veya `String.format(...)` |
| `STRING_LITERAL` | Sadece sabit string |
| `GETMESSAGE` | `e.getMessage()` |
| `CAUSE` | Catch'te yakalanan exception değişkeni |
| `CONST` | Büyük harfli sabit, örn. `HataKodlari.KAYIT_YOK` |
| `VAR` | Diğer değişken veya ifade |
| `ANY` | Herhangi bir argüman |
| `*` | Sadece sonda: kalan argümanlar ne olursa olsun |

Örnekler:

| Kod | Uyan desen |
|---|---|
| `throw new CustomException(0, "deneme");` | `0,STRING` |
| `throw new CustomException(0, "Kayıt yok: " + id);` | `0,STRING` |
| `throw new CustomException(0, "deneme", e);` | `0,STRING,*` veya `0,STRING,CAUSE` |
| `throw new CustomException(0, mesaj);` | `0,VAR` veya `0,ANY` |
| `throw new CustomException(1042, params);` | Hiçbiri (id 0 değil) |

Argüman olarak belirli bir sabit veya metot çağrısı da aranabilir:

| Değer | Eşleştiği argüman |
|---|---|
| `GENERALERRORCODE.ERROR_CODE` | Bu sabitin kendisi. Tam nitelikli yazım, static import ile `ERROR_CODE` ve sabitin adıyla aynı string (`"ERROR_CODE"`) da kabul edilir |
| `getErrorCode()` | Bu metodun herhangi bir nesne üzerinden çağrısı, örn. `e.getErrorCode()` |
| `'errorCode'` | Tam olarak bu string (tek veya çift tırnakla) |
| `A\|B` | Alternatifler: A veya B, örn. `GENERALERRORCODE.ERROR_CODE\|'errorCode'` |

Sabitin değeri adından farklıysa (örneğin `ERROR_CODE = "errorCode"`) string hâlini alternatif olarak ekleyin. Sabit kapalı bir jar içindeyse değerini JDK ile gelen `javap` aracıyla görebilirsiniz: `javap -constants -cp framework.jar com.firma.GENERALERRORCODE`

## Metot çağrısı desenleri

Hata bazen exception fırlatmak yerine başka yollarla bildirilir, örneğin mobile dönen cevaba hata kodu yazılarak. Bunları da aynı raporda görmek için metot çağrısı desenleri tanımlanabilir:

```bash
java -jar target/exception-scanner-1.0.0.jar \
     --local ~/projeler/servis \
     --class com.firma.framework.CustomException \
     --pattern "0,STRING,*" \
     --call "put:GENERALERRORCODE.ERROR_CODE,ANY"
```

`--call` değeri `metot:argümanlar` veya `nesne.metot:argümanlar` şeklindedir. Nesne verilmezse metodun hangi nesne üzerinden çağrıldığına bakılmaz; `outBag.put:...` yazılırsa sadece `outBag` (veya `this.outBag`) üzerinden yapılan çağrılar eşleşir. Argüman kısmı exception desenleriyle aynı değerleri kullanır. Parantez ve `*` içerdiği için zsh ve PowerShell'de değeri tırnak içinde verin.

| Kod | Uyan çağrı deseni |
|---|---|
| `outBag.put(GENERALERRORCODE.ERROR_CODE, e.getErrorCode());` | `put:GENERALERRORCODE.ERROR_CODE,getErrorCode()` veya `...,ANY` |
| `outBag.put(GENERALERRORCODE.ERROR_CODE, 0);` | `put:GENERALERRORCODE.ERROR_CODE,0` veya `...,ANY` |

Çağrılar Kullanımlar sayfasında exception kullanımlarıyla birlikte listelenir; "Exception / Çağrı" sütununda `outBag.put(...)` gibi görünür, "Bağlam" sütununda "metot çağrısı" yazar. Ayar dosyasında `call.N.method`, `call.N.scope`, `call.N.args` ve `call.N.name` ile tanımlanabilir. Sadece çağrı aranacaksa `--class` verilmesi gerekmez.

Bir kullanımın neden rapora girmediğini anlamak için desenleri geçici olarak kaldırıp (tüm kullanımlar raporlanır) Kullanımlar sayfasındaki koda bakabilirsiniz.

## PostgreSQL envanterine kayıt

Tarayıcı bulduğu kullanımları, sınıf ve metot envanterini tutan PostgreSQL tablolarıyla eşleştirip yeni bir kullanım tablosuna yazabilir. Böylece geçiş ilerlemesi SQL ile takip edilebilir.

### Kurulum

`sql/exception_usage_postgres.sql` dosyasını veritabanında bir kez çalıştırın. Üç tablo ve üç görünüm oluşturur:

| Nesne | İçerik |
|---|---|
| `env.exception_usage_type` | Kullanım tipleri (desenlerin karşılığı). Tarayıcı her çalışmada ayar dosyasındaki desenlerle günceller |
| `env.exception_scan_run` | Her tarama çalıştırması bir kayıt |
| `env.exception_usage` | Kullanımlar; `class_id` → `env.java_class`, `method_id` → `env.java_method`, `usage_type_code` → kullanım tipi |
| `env.v_exception_usage_latest` | Son taramanın kullanımları |
| `env.v_exception_usage_summary` | Son tarama, proje ve tipe göre sayılar |
| `env.v_exception_usage_trend` | Taramadan taramaya sayıların değişimi |

Mevcut tablolarınızın sütun adları farklıysa hem SQL dosyasındaki `REFERENCES` satırlarını hem de `db.*` ayarlarını güncelleyin.

### Çalıştırma

```bash
export DB_USER=kullanici DB_PASSWORD=sifre
java -jar target/exception-scanner-1.0.0.jar tarama.properties --db --db-note "Ekim takibi"
```

İlk seferde `--db-dry-run` ile deneyin: eşleştirme yapılır ve sonucu Excel'deki "DB Sınıf Id", "DB Metot Id", "Eşleşme" sütunlarında görünür, ama tabloya bir şey yazılmaz.

Her çalıştırma yeni bir `scan_run` kaydı açar ve kullanımları ona bağlar; aynı tarama iki kez çalışınca veri karışmaz. Kayıt tek transaction'da yapılır: yazma yarıda hata verirse hiçbir şey kaydedilmez. Veritabanına bağlanılamazsa raporlar yine eşleştirmesiz yazılır.

### Eşleştirme

**Sınıf:** Kullanımın bulunduğu sınıfın FQCN'i `fqcn` sütununda aranır. İç içe sınıflar için hem `a.b.Dis$Ic` hem `a.b.Dis.Ic` denenir; bulunamazsa dıştaki sınıfa kadar kısaltılır. Anonim sınıf içindeki kullanımlar, onu içeren sınıfa ve o sınıftaki metoda eşlenir.

**Metot:** Tablodaki ad `(` karakterine kadar kesilerek karşılaştırılır; niteleyici ve dönüş tipi de atılır. Bu yüzden `getRate`, `getRate(String a, String b)`, yarım kalmış `getRate(String a,` ve `public static CSBag getRate(CSBag inBag)` aynı metot sayılır. Aynı adda birden fazla metot (overload) varsa, addaki veya `db.method.signature.column` sütunundaki parametrelerle ayırt edilir; JVM biçimli imzalar (`(Ljava/lang/String;I)V`) da okunur. Constructor'lar `<init>` veya sınıf adıyla, static bloklar `<clinit>` ile aranır.

**Sonuç** `match_status` sütununa yazılır:

| Değer | Anlamı |
|---|---|
| `MATCHED` | Sınıf ve metot bulundu |
| `METHOD_AMBIGUOUS` | Aynı adda birden fazla metot ayırt edilemedi, en küçük id seçildi |
| `METHOD_NOT_FOUND` | Sınıf bulundu, metot bulunamadı |
| `NO_METHOD` | Kullanım bir alan tanımında, metot yok |
| `CLASS_AMBIGUOUS` | Aynı FQCN birden fazla kayıtta; `db.class.filter` ile daraltın |
| `CLASS_NOT_FOUND` | Sınıf envanterde yok |

Nedeni `match_note` sütununda açıklanır.

### Kullanım tipleri

Her desenin `pattern.N.type` (çağrılarda `call.N.type`) değeri `usage_type_code` olarak yazılır. Verilmezse desen adından üretilir. Komut satırında `--pattern "KOD0_SERBEST_METIN=0,STRING,*"` biçimiyle de verilebilir. Doğru kullanımları saymak için bir desen tanımlayıp `pattern.N.legacy=false` yaparsanız, `is_legacy` sütunu sayesinde raporlarda eski kullanımlardan ayrılır.

## Ekran, region, Jasper rapor ve process envanteri

`--ebml` ile tarayıcı, repolardaki ekran ve rapor tanım dosyalarını da bulup proje bazında tablolara yazar:

| Tablo | Dosyalar |
|---|---|
| `env.all_screens` | `ebml.page` paketi (ve alt paketleri) altındaki `.ebml` dosyaları |
| `env.all_popups` | `ebml.popup` paketi (ve alt paketleri) altındaki `.ebml` dosyaları |
| `env.all_regions` | Adı `RG_` ile başlayan **veya** `ebml.region` paketi altındaki `.ebml` dosyaları |
| `env.all_reports` | `ebml.report` paketi (ve alt paketleri) altındaki `.dsxml` dosyaları |
| `env.all_processes` | `process` klasörü altındaki `250001-RISM.par` gibi klasörlerde bulunan `processdefinition.xml` dosyaları |

Region kuralı önce uygulanır: `ebml.page` veya `ebml.popup` altında olup adı `RG_` ile başlayan bir dosya region sayılır. `all_regions.match_rule` sütunu dosyanın hangi kuralla bulunduğunu (`PREFIX`, `PACKAGE`, `PREFIX+PACKAGE`) gösterir. Kurallara uymayan `.ebml` / `.dsxml` dosyaları tablolara yazılmaz, Excel'deki "EBML Dosyaları" sayfasında "Sınıflandırılmadı" olarak listelenir. `target`, `bin`, `classes` gibi derleme klasörlerindeki kopyalar sayılmaz (`scan.exclude.dirs`).

Process'lerde `process_id` klasör adının başındaki numaradan (`250001-RISM.par` → `250001`), `process_name` ise `processdefinition.xml` içindeki `label` özelliğinden (`label="Müşteri Değerlendirme"`) alınır. Önce kök elemanın `label` değerine bakılır, yoksa adında `process` geçen ilk elemanınkine. Klasör adı `folder_name` sütununa yazılır. `label` bulunamazsa `process_name` boş kalır ve dosya "Tarama Hataları" sayfasında listelenir. Klasör adı `ebml.process.dir` ile değiştirilebilir (varsayılan `process`); bu klasörün altında olmayan veya `<numara>-<ad>.par` biçimine uymayan klasörlerdeki `processdefinition.xml` dosyaları "Sınıflandırılmadı" olarak sadece rapora yazılır.

### Kurulum ve çalıştırma

`sql/ebml_inventory_postgres.sql` dosyasını bir kez çalıştırın. Tek başına da çalışır; exception tablolarıyla aynı tarama kaydı tablosunu (`exception_scan_run`) paylaşır ve ona ekran, popup, region, rapor ve process sayısı sütunlarını ekler. Daha önce çalıştırdıysanız `env.all_processes` tablosu ve `process_count` sütunu için dosyayı yeniden çalıştırın (mevcut tablolara dokunmaz). Eski adlarla oluşturulmuş tablolar (`env.screens`, `env.popups`, `env.regions`, `env.jasper_reports`) varsa bu dosya onları verileriyle birlikte yeni adlarına (`env.all_screens`, `env.all_popups`, `env.all_regions`, `env.all_reports`) taşır.

```bash
# Sadece ekran/rapor envanteri
java -jar target/exception-scanner-1.0.0.jar scanner.properties --ebml --db-dry-run
java -jar target/exception-scanner-1.0.0.jar scanner.properties --ebml --db

# Exception taramasıyla birlikte (tek scan_run_id altında)
java -jar target/exception-scanner-1.0.0.jar scanner.properties --class tr.com.cs.aurora.auroracore.utility.CSException --ebml --db
```

### Proje eşleştirmesi

Her dosyanın ana proje adı `env.project.project_name` sütununda aranır ve bulunan `id` değeri `project_id` sütununa (FK) yazılır. Arama varsayılan olarak büyük/küçük harf duyarsızdır. Hangi adın aranacağını `ebml.project.name.from` belirler:

| Değer | Aranan ad |
|---|---|
| `REPO` (varsayılan) | Bitbucket repo adı; bulunamazsa repo slug'ı |
| `REPO_SLUG` | Bitbucket repo slug'ı |
| `MODULE` | Dosyanın bulunduğu modül klasörünün adı (`pom.xml`, `build.xml` veya `.project` olan klasör) |
| `BITBUCKET_PROJECT` | Bitbucket proje adı; bulunamazsa proje anahtarı |

Sonuç `project_match` sütununa yazılır: `MATCHED`, `PROJECT_AMBIGUOUS` (aynı adda birden fazla proje, en küçük id seçildi) veya `PROJECT_NOT_FOUND` (`project_id` boş). Aranan ad her durumda `project_name` sütununda durur. İlk denemede `--db-dry-run` ile çalıştırıp Excel'deki "Proje Eşleşmesi" sütununa bakarak doğru ayarı bulabilirsiniz.

### Excel raporundaki sayılar

`--ebml` ile üretilen Excel raporunda:

- **Özet** sayfasında ekran, popup, region, Jasper rapor ve process sayılarının toplamı ile tür bazında dağılımı bulunur. Altındaki repo tablosunda her repo, bağlı olduğu proje ile birlikte ve bu beş sayı ayrı sütunlarda olacak şekilde listelenir.
- **EBML Dosyaları** sayfasında hangi dosyanın nerede bulunduğu dosya bazında yer alır.

### Mevcut tablolarda project_id güncellemesi

`--ebml --db` ile çalıştırıldığında, taramada projesi bulunan dosyaların `project_id` değeri mevcut tablolara da yazılır:

| Tablo | Eşleştirme |
|---|---|
| `env.screen` (`page_type = 'page'`) | Ekranlar: `name` = `EKRAN001.ebml` |
| `env.screen` (`page_type = 'region'`) | Region'lar: `name` = `RG_Adres.ebml` |
| `env.popup` | `popup_name` = `pp_deneme` |
| `env.report` | `report_name` = rapor adı (`.dsxml` uzantılı ve uzantısız denenir) |
| `env.process` | `no` = klasördeki numara (`250001`) veya `processdefinition.xml` içindeki `name`; bulunamazsa `name` = klasör adında `-` işaretinden sonraki kısım (`RISM`) |

Karşılaştırmalar büyük/küçük harfe ve baştaki/sondaki boşluklara duyarsızdır. Aynı ad farklı projelerde bulunduysa hangi projeye ait olduğu bilinemeyeceği için o kayıt güncellenmez ve konsolda sayısı yazılır. Varsayılan olarak `project_id` değeri farklı olan satırlar güncellenir; `ebml.update.only.empty=true` ile sadece boş olanlar doldurulur. Tablo veya sütun bulunamazsa sadece o tablo atlanır, tarama kaydı yine yapılır. Güncelleme envanter kayıtlarıyla aynı transaction'da yapılır; `--db-dry-run` modunda yapılmaz. Kapatmak için `ebml.update.existing=false`; tablo ve sütun adları `db.existing.*` ayarlarıyla değiştirilebilir.

Her kayıtta dosya adı, paket, repo içindeki yol, Bitbucket'ta dosyayı açan bağlantı (taranan commit'e sabitlenmiş) ve kayıt tarihi (`created_at`) bulunur. Son taramanın proje bazlı sayıları için `env.v_ebml_inventory_latest` görünümü kullanılabilir.

## REST API ve Swagger

Tarayıcı komut satırı yerine bir web servisi olarak da çalışabilir. Taramalar tarayıcıdan, Swagger arayüzüyle başlatılır ve raporlar oradan indirilir:

```bash
java -jar target/exception-scanner-1.0.0.jar --server
```

| Adres | Açıklama |
|---|---|
| `/swagger-ui.html` (veya `/`) | Swagger arayüzü |
| `/v3/api-docs` | OpenAPI dokümanı |
| `POST /api/scans` | Tarama başlatır, hemen tarama numarasıyla döner (202) |
| `GET /api/scans`, `GET /api/scans/{id}` | Taramalar ve durumu: `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED` |
| `GET /api/scans/{id}/log` | Tarama logu (sürerken de okunabilir, `?tail=50`) |
| `GET /api/scans/{id}/report` | Ana Excel raporu |
| `GET /api/scans/{id}/files`, `.../files/download?path=...` | CSV ve proje bazlı raporlar |
| `DELETE /api/scans/{id}` | Sıradaki taramayı iptal eder veya biten taramayı raporlarıyla siler |
| `GET /api/settings` | Sunucudaki temel ayarlar (token ve şifreler maskeli) |
| `/actuator/health/liveness`, `/actuator/health/readiness` | Sağlık kontrolleri |

İstek gövdesi komut satırı parametrelerinin karşılığıdır. Boş bırakılan alanlarda sunucudaki ayar dosyası geçerlidir; `{}` ile gönderilirse doğrudan ayar dosyasındaki değerlerle taranır:

```json
{
  "projects": ["PRJ"],
  "excludeRepos": ["z_atil_*", "nova-*"],
  "branch": "develop",
  "classes": ["com.firma.framework.CustomException"],
  "patterns": ["0,STRING", "0,STRING,*"],
  "properties": { "git.timeout.minutes": "30" }
}
```

Taramalar sırayla çalışır (aynı anda tek tarama isteği); sonradan gelenler sırada bekler. Bir taramanın içindeki projeler ve repolar ise paralel işlenir (bkz. [Paralel işleme](#paralel-işleme)). Klonlanan repolar taramalar arasında saklanır, sonraki taramada sadece değişiklikler çekilir. Her taramanın raporları, logu ve durumu `<rapor klasörü>/<tarama numarası>/` altına yazılır; sunucu yeniden başladığında önceki taramalar listede kalır.

### Haftalık otomatik tarama

Sunucu ayakta olduğu sürece haftada bir, sunucudaki `scanner.properties` ile kendiliğinden tarar (Swagger'dan `{}` gönderilmiş gibi). Varsayılan zaman her Pazar 02:00'dir (İstanbul saati). Swagger'da `GET /api/schedule` bir sonraki çalışma zamanını gösterir. Otomatik taramalar listede `"trigger": "SCHEDULE"`, Swagger/REST ile başlatılanlar `"API"` olarak görünür. Bunların dışında istediğiniz zaman `POST /api/scans` ile tarama başlatabilirsiniz.

Pod planlanan saatte kapalıysa (yeniden dağıtım, node bakımı vb.) o haftanın taraması kaçmaz: açılışta, son planlanan zamandan sonra otomatik tarama yapılmadığı görülürse hemen başlatılır. Önceki otomatik tarama hâlâ sürüyorsa yenisi başlatılmaz.

Zamanı değiştirmek için `SCANNER_SCHEDULE_CRON` kullanılır. Format Spring cron'dur: `saniye dakika saat gün ay haftanın-günü`. Örneğin `0 30 1 * * MON` her Pazartesi 01:30 demektir. `-` verilirse otomatik tarama kapanır.

Sunucu ayarları ortam değişkenleriyle verilir:

| Değişken | Varsayılan | Açıklama |
|---|---|---|
| `PORT` | `8080` | |
| `SCANNER_CONFIG` | `scanner.properties` | Temel ayar dosyası |
| `SCANNER_WORK_DIR` | `./scannedRepos` | Klonların tutulduğu klasör |
| `SCANNER_OUTPUT_DIR` | `./executeReports` | Raporların yazıldığı klasör |
| `SCANNER_SCHEDULE_CRON` | `0 0 2 * * SUN` | Haftalık otomatik tarama zamanı; `-` kapatır |
| `SCANNER_SCHEDULE_ZONE` | `Europe/Istanbul` | Cron'un saat dilimi |
| `SCANNER_API_KEY` | boş | Doluysa `/api` istekleri `X-API-Key` başlığını ister (Swagger'da **Authorize**) |
| `EXTRA_CA_BUNDLE` | boş | Şirket CA sertifikaları (PEM); Java'ya ve git'e eklenir |
| `BITBUCKET_USERNAME`, `BITBUCKET_TOKEN`, `DB_USER`, `DB_PASSWORD` | | Komut satırındaki gibi |

## OpenShift

`Dockerfile` ve `openshift/` klasörü OpenShift'te çalıştırmak için hazırdır. İmaj Red Hat UBI 9 OpenJDK 17 üzerine kurulur, içinde git vardır ve OpenShift'in rastgele kullanıcı ID'siyle çalışır.

```bash
oc project <namespace>

# 1. Gizli değerler (depoya eklemeyin; örnek: openshift/secret.example.yaml)
oc create secret generic exception-scanner-secrets \
  --from-literal=BITBUCKET_TOKEN=<token> \
  --from-literal=SCANNER_API_KEY=<rastgele-uzun-değer>

# 2. Ayarlar: openshift/scanner.properties dosyasında bitbucket.url vb. düzenleyin, sonra
oc apply -k openshift/

# 3. İmajı OpenShift içinde derleyin (Docker stratejisi; yeni imaj gelince pod kendiliğinden yenilenir)
oc start-build exception-scanner --from-dir=. --follow

# 4. Swagger adresi
echo "https://$(oc get route exception-scanner -o jsonpath='{.spec.host}')/swagger-ui.html"
```

Oluşan kaynaklar:

| Dosya | İçerik |
|---|---|
| `build.yaml` | ImageStream ve BuildConfig |
| `deployment.yaml` | Tek pod, sağlık kontrolleri, haftalık tarama zamanı, 2–4 GiB bellek, root olmayan güvenlik ayarları |
| `pvc.yaml` | 20 GiB disk: klonlar (`/data/work`) ve raporlar (`/data/reports`) |
| `service.yaml`, `route.yaml` | HTTPS (edge) Route, büyük rapor indirmeleri için 5 dakika zaman aşımı |
| `scanner.properties` | `exception-scanner-config` ConfigMap'i; değişince pod yeniden başlar |

Notlar:

- **Şirket içi registry / Maven mirror:** İmajlar `BUILD_IMAGE` ve `RUNTIME_IMAGE` build argümanlarıyla değiştirilebilir (`build.yaml` içinde örnek var).
- **Şirket CA sertifikası:** Bitbucket'ın sertifikası tanınmıyorsa boş bir ConfigMap oluşturup `oc label configmap trusted-ca config.openshift.io/inject-trusted-cabundle=true` ile etiketleyin. Ardından `deployment.yaml` içindeki `ca-bundle` satırlarını ve `EXTRA_CA_BUNDLE` değişkenini açın. Kendi PEM dosyanızı da ConfigMap olarak bağlayabilirsiniz.
- **Bellek:** Heap, konteyner limitinin %60'ı olarak ayarlanır (`MAX_RAM_PERCENTAGE`); kalan bellek paralel çalışan git işlemlerine kalır. Çok büyük repolarda limiti artırın veya `threads` değerini düşürün.
- **Sürekli çalışma:** Pod sürekli ayakta kalır, haftalık tarama pod içindeki zamanlayıcıyla yapılır; ayrıca CronJob gerekmez. Pod çökerse OpenShift yeniden başlatır.
- **Tek pod:** Tarama geçmişi pod'da tutulduğu ve disk ReadWriteOnce olduğu için `replicas: 1` ve `Recreate` stratejisi kullanılır.
- **Erişim:** Route dışarı açıksa `SCANNER_API_KEY` mutlaka tanımlanmalı; Swagger ve sağlık kontrolleri anahtarsız açılır, `/api` istekleri anahtar ister.

## Paralel işleme

Çok sayıda proje ve repo tarandığında süre büyük ölçüde repoların çekilmesine (klonlama / güncelleme) gider. Bu yüzden:

- `bitbucket.projects=A,B,C,D,E` gibi birden fazla proje verildiğinde projelerin repo listeleri Bitbucket'tan paralel alınır.
- Repolar projeler arasında sırayla dağıtılır (A1, B1, C1, D1, E1, A2, ...). Böylece repo sayısı çok olan bir proje diğerlerini bekletmez, beş proje aynı anda ilerler.
- Klonlama ve ayrıştırma ayrı işçi havuzlarında çalışır. `git.threads` (varsayılan 8) aynı anda klonlanan/güncellenen repo sayısıdır; ağ işi olduğu için yüksek tutulabilir. `threads` (varsayılan 4) aynı anda ayrıştırılan repo sayısıdır; işlemci ve bellek kullandığı için düşük tutulur. Bir repo klonlanır klonlanmaz ayrıştırmaya geçer, bu sırada diğer repolar çekilmeye devam eder.

Repolar `work.dir` altında saklandığı için ilk taramadan sonraki taramalarda sadece değişiklikler çekilir ve tarama çok daha hızlı biter. `git.threads` değerini Bitbucket sunucusunu zorlamayacak kadar tutun.

## Bellek kullanımı

Tarayıcı her dosyayı ayrıştırıp işini bitirince bellekten bırakır. Kullanım içermesi mümkün olmayan dosyaları, metin olarak aranan sınıf veya metot adını içermiyorsa hiç ayrıştırmaz. Excel raporu da satır satır diske yazılır. Bu sayede bellek kullanımı repo boyutundan büyük ölçüde bağımsızdır.

Yine de `OutOfMemoryError` alırsanız:

```bash
java -Xmx4g -jar target/exception-scanner-1.0.0.jar scanner.properties --threads 2
```

`-Xmx4g` Java'ya 4 GB bellek kullanma izni verir; `--threads` aynı anda taranan repo sayısını azaltır. Tek bir repo belleği aşarsa tarama durmaz: o repo raporda "BELLEK" hatasıyla işaretlenir, diğerleri taranmaya devam eder. `scan.max.file.kb` (varsayılan 2048 KB) üzerindeki dosyalar, genelde üretilmiş kod oldukları için atlanır ve Tarama Hataları sayfasında "ATLANDI" olarak listelenir.

## Sınırlamalar

Tarama derleme classpath'i olmadan yapıldığı için tip çözümlemesi import'lara ve paket adına bakılarak yapılır. Aynı isimli başka bir sınıfın `*` ile import edildiği nadir durumlarda kullanım kaçabilir; şüphe varsa `match.lenient=true` ile sadece sınıf adına göre eşleştirme yapılabilir (bu durumda aynı isimli farklı sınıflar da raporlanabilir).

Sadece `.java` dosyaları taranır; Kotlin (`.kt`) dosyaları ve JSP scriptlet'leri içindeki kullanımlar rapora girmez. Java 6'dan en yeni sürümlere kadar Java kodu okunabilir.

Hiç sonuç çıkmazsa tarayıcı, konsolun sonunda nedenini açıklar: hiç Java dosyası bulunamaması, sınıf adının hiçbir dosyada geçmemesi, paket adının uyuşmaması veya desenin kullanımların argüman sayısına uymaması gibi.

Mesajı bir değişkenden gelen kullanımlarda (`VAR`) mesajın ne olduğu çalışma zamanında belli olur, bunlar kod okunarak incelenmelidir. Aynı şekilde id bir değişken veya sabitten geliyorsa (`new CustomException(SIFIR, "...")`) `0` deseni bunu yakalamaz; gerekirse `CONST,STRING` gibi ek bir desen tanımlayın.

Dosyalar önce UTF-8 olarak okunur; geçersiz karakter varsa `scan.fallback.charset` (varsayılan windows-1254) kullanılır. Eski projelerdeki Türkçe karakterler bozuk görünürse bu ayarı ISO-8859-9 olarak deneyin.
