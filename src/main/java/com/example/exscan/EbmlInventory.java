package com.example.exscan;

/** Ekran / region / rapor kayıtlarının project_match değerleri. */
final class EbmlInventory {
    /** Proje env.project tablosunda tek kayıtla bulundu */
    static final String MATCHED = "MATCHED";
    /** Aynı adda birden fazla proje var, en küçük id seçildi */
    static final String PROJECT_AMBIGUOUS = "PROJECT_AMBIGUOUS";
    /** Proje bulunamadı, project_id boş */
    static final String PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND";

    private EbmlInventory() { }
}
