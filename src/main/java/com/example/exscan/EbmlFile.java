package com.example.exscan;

/** Taramada bulunan bir ekran (.ebml), region (.ebml) veya Jasper rapor (.dsxml) dosyası. */
final class EbmlFile {

    enum Kind {
        SCREEN("Ekran"),
        REGION("Region"),
        POPUP("Popup"),
        REPORT("Jasper rapor"),
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

    /** env.project tablosunda aranan proje adı */
    String projectName = "";
    /** Sırayla denenecek adlar; ilki projectName */
    final java.util.List<String> projectNameCandidates = new java.util.ArrayList<String>();
    // ---- Veritabanı eşleştirme sonucu
    Long projectId;
    /** MATCHED, PROJECT_NOT_FOUND veya PROJECT_AMBIGUOUS */
    String projectMatch = "";
}
