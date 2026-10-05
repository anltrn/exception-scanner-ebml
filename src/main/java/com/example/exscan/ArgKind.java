package com.example.exscan;

/** Constructor'a geçilen tek bir argümanın türü. */
enum ArgKind {

    STRING_SABIT("String sabit", true),
    STRING_BIRLESTIRME("String birleştirme", true),
    STRING_FORMAT("String.format / MessageFormat", true),
    EXCEPTION_MESAJI("Başka exception'ın mesajı (getMessage)", false),
    SEBEP("Yakalanan exception (cause)", false),
    SAYI("Sayı", false),
    SABIT_REFERANS("Sabit referansı", false),
    DEGISKEN("Değişken / ifade", false);

    final String label;
    /** Mesaj metni kodun içinde yazılı mı (sabit, birleştirme veya format) */
    final boolean hasMessage;

    ArgKind(String label, boolean hasMessage) {
        this.label = label;
        this.hasMessage = hasMessage;
    }
}
