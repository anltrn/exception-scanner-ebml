package com.scanner.exception.model;

/** Hedef exception'ı yakalayan bir catch bloğu. */
public final class CatchFinding extends Located {
    public String caughtTypes = "";
    public String variable = "";
    public boolean emptyBody;
    public boolean rethrows;
    /** Hatayı yutuyorsa nasıl yuttuğu (env.swallowed_exceptions.catch_kind); yutmuyorsa null */
    public SwallowKind swallowKind;

    public enum SwallowKind {
        /** Gövde boş veya sadece yorum */
        EMPTY,
        /** Gövdede sadece log çağrıları var */
        LOG_ONLY,
        /** Log çağrıları ve ardından hatayı taşımayan return (ör. return null) */
        LOG_AND_RETURN,
        /** Log olmadan sadece hatayı taşımayan return: return null, return false, return "", return EMPTY, return; */
        RETURN_ONLY
    }

    public String note() {
        if (emptyBody) return "Exception yutuluyor, hata kayboluyor";
        if (swallowKind == SwallowKind.LOG_ONLY) return "Sadece loglanıp yutuluyor, hata çağırana ulaşmıyor";
        if (swallowKind == SwallowKind.LOG_AND_RETURN) return "Loglanıp return ediliyor, hata çağırana ulaşmıyor";
        if (swallowKind == SwallowKind.RETURN_ONLY) return "Loglanmadan değer dönülüyor, hata kayboluyor";
        if (!rethrows) return "Yakalanıp akış devam ediyor; yeni yapıya geçişte davranışı kontrol edin";
        return "Yeniden fırlatıyor";
    }
}
