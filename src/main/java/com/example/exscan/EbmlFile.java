package com.example.exscan;

/**
 * Taramada bulunan bir ekran (.ebml), region (.ebml), Jasper rapor (.dsxml) dosyası
 * veya process tanımı (process/250001-XXX.par/processdefinition.xml).
 */
final class EbmlFile {

    enum Kind {
        SCREEN("Ekran"),
        REGION("Region"),
        POPUP("Popup"),
        REPORT("Jasper rapor"),
        PROCESS("Process"),
        /** Uzantı doğru ama beklenen paketlerin hiçbirinde değil; sadece raporda gösterilir */
        UNCLASSIFIED("Sınıflandırılmadı");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    Kind kind;
    /** Region için hangi kuralla bulunduğu: PREFIX (RG_ ile başlıyor), PACKAGE (ebml.region altında) veya ikisi */
    String rule = "";
    String projectKey = "";
    String repo = "";
    String module = "";
    String fileName = "";
    String packageName = "";
    String file = "";
    String link;

    /** Process için klasör adındaki numara: 250001-RISM.par -> 250001 */
    Long processId;
    /** Process için processdefinition.xml içindeki label değeri */
    String processName = "";
    /** Process için processdefinition.xml içindeki name değeri (env.process.no ile eşleştirilir) */
    String processCode = "";
    /** Process için klasör adında '-' işaretinden sonraki kısım: 250001-RISM.par -> RISM (env.process.name) */
    String processShortName = "";

    /** env.project tablosunda aranan proje adı */
    String projectName = "";
    /** Sırayla denenecek adlar; ilki projectName */
    final java.util.List<String> projectNameCandidates = new java.util.ArrayList<String>();
    // ---- Veritabanı eşleştirme sonucu
    Long projectId;
    /** MATCHED, PROJECT_NOT_FOUND veya PROJECT_AMBIGUOUS */
    String projectMatch = "";
}
