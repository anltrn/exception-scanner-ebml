package com.example.exscan;

/** Hedef exception'ı yakalayan bir catch bloğu. */
final class CatchFinding extends Located {
    String caughtTypes = "";
    String variable = "";
    boolean emptyBody;
    boolean rethrows;

    String note() {
        if (emptyBody) return "Exception yutuluyor, hata kayboluyor";
        if (!rethrows) return "Yakalanıp akış devam ediyor; yeni yapıya geçişte davranışı kontrol edin";
        return "Yeniden fırlatıyor";
    }
}
