package com.example.exscan;

/** Rapordaki her bulgunun ortak konum bilgileri. */
abstract class Located {
    String projectKey = "";
    String projectName = "";
    String repo = "";
    String module = "";
    String file = "";
    String className = "";
    /** Dosyanın paketi; className'in paketten sonraki kısmı iç içe sınıf yolunu verir */
    String packageName = "";
    String member = "";
    /**
     * Veritabanı eşleştirmesi için üye bilgisi. Anonim sınıf içindeki kullanımlarda
     * anonim sınıfın metodu yerine, onu içeren adlandırılmış sınıftaki metot tutulur.
     */
    MemberRef memberRef = MemberRef.NONE;

    // ---- Veritabanı eşleştirme sonucu (db.enabled=true ise dolar)
    Long dbClassId;
    Long dbMethodId;
    String matchStatus = "";
    String matchNote = "";
    int line;
    boolean testCode;
    String link;
}
