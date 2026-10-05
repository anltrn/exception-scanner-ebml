package com.example.exscan;

/** Aranan desene uyan bir exception oluşturma yeri. */
final class UsageFinding extends Located {
    String targetClass = "";
    String pattern = "";
    /** Veritabanındaki kullanım tipi kodu (desenin type değeri) */
    String typeCode = "";
    String action = "";
    /** Argümanlardaki ilk sayı (id) */
    String idValue = "";
    /** Mesajı taşıyan argümanın türü; mesaj yoksa null */
    ArgKind messageKind;
    String message = "";
    String expression = "";
    String context = "";
}
