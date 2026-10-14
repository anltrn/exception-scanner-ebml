package com.scanner.exception.model;

import com.scanner.exception.scan.ArgumentClassifier.ArgKind;

/** Aranan desene uyan bir exception oluşturma yeri. */
public final class UsageFinding extends Located {
    public String targetClass = "";
    public String pattern = "";
    /** Veritabanındaki kullanım tipi kodu (desenin type değeri) */
    public String typeCode = "";
    public String action = "";
    /** Argümanlardaki ilk sayı (id) */
    public String idValue = "";
    /** Mesajı taşıyan argümanın türü; mesaj yoksa null */
    public ArgKind messageKind;
    public String message = "";
    public String expression = "";
    public String context = "";
}
