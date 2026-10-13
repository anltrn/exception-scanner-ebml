package com.example.exscan.model;

/** Hedef exception'ı yakalayan bir catch bloğu. */
public final class CatchFinding extends Located {
    public String caughtTypes = "";
    public String variable = "";
    public boolean emptyBody;
    public boolean rethrows;

    public String note() {
        if (emptyBody) return "Exception yutuluyor, hata kayboluyor";
        if (!rethrows) return "Yakalanıp akış devam ediyor; yeni yapıya geçişte davranışı kontrol edin";
        return "Yeniden fırlatıyor";
    }
}
