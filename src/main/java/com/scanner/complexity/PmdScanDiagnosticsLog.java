package com.scanner.complexity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Türkçe adım logları — PMD-native tarama (stderr). */
final class PmdScanDiagnosticsLog {

    private PmdScanDiagnosticsLog() {
    }

    static void stepStart(String step, String aciklama) {
        System.err.println("[PMD-NATIVE] ▶ ADIM " + step + ": " + aciklama);
    }

    static void stepDone(String step, String ozet) {
        System.err.println("[PMD-NATIVE] ✓ ADIM " + step + " tamam" + (ozet.isEmpty() ? "" : " — " + ozet));
    }

    static void stepWarn(String step, String mesaj) {
        System.err.println("[PMD-NATIVE] ⚠ ADIM " + step + ": " + mesaj);
    }

    static void stepFail(String step, String aciklama, Throwable e) throws IOException {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        System.err.println("[PMD-NATIVE] ✗ ADIM " + step + " HATA (" + aciklama + "): " + detail);
        if (e.getCause() != null && e.getCause() != e) {
            System.err.println("[PMD-NATIVE]   neden: " + e.getCause().getMessage());
        }
        throw new IOException("PMD tarama ADIM " + step + " başarısız: " + aciklama + " — " + detail, e);
    }

    static void logIncrementalBootstrap(Path stateFile, boolean ignoreCache, boolean incremental,
                                      AnalyzerState loaded, String expectedIdentity, boolean hadPreviousScan) {
        if (!incremental) {
            System.err.println("[PMD-NATIVE]   Artımlı önbellek: KAPALI (--state verilmedi veya NO_STATE=1). "
                    + "Her .java dosyası baştan PMD ile ölçülür.");
            return;
        }
        Path abs = stateFile.toAbsolutePath().normalize();
        System.err.println("[PMD-NATIVE]   State dosyası: " + abs);
        if (ignoreCache) {
            System.err.println("[PMD-NATIVE]   --fresh: önceki state yok sayılıyor (soğuk koşu, yine de sonunda yazılır).");
            return;
        }
        boolean exists = Files.isRegularFile(stateFile);
        System.err.println("[PMD-NATIVE]   Dosya diskte " + (exists ? "var" : "yok (ilk koşu veya silinmiş)"));
        if (exists) {
            try {
                long bytes = Files.size(stateFile);
                System.err.printf("[PMD-NATIVE]   Boyut: %,d byte%n", bytes);
            } catch (IOException ignored) {
                // optional
            }
        }
        int fileEntries = loaded.files == null ? 0 : loaded.files.size();
        System.err.println("[PMD-NATIVE]   State içinde kayıtlı .java sayısı: " + fileEntries);
        if (fileEntries > 0 && loaded.cacheIdentity != null) {
            System.err.println("[PMD-NATIVE]   State kimliği: " + loaded.cacheIdentity);
        }
        System.err.println("[PMD-NATIVE]   Bu koşu kimliği: " + expectedIdentity);
        if (hadPreviousScan) {
            System.err.println("[PMD-NATIVE]   Önbellek KULLANILACAK: dosya byte hash aynıysa PMD metrik atlanır.");
        } else if (fileEntries > 0) {
            System.err.println("[PMD-NATIVE]   Önbellek KULLANILMAYACAK: profil/sürüm uyuşmuyor veya state boş — tam skor.");
        } else {
            System.err.println("[PMD-NATIVE]   Önbellek henüz yok — bu koşu sonunda state yazılır.");
        }
    }

    static void logCacheOzet(AnalysisReport.CacheStatistics s) {
        double seconds = s.scanTimeMillis() / 1000.0;
        System.err.println("[PMD-NATIVE] —— Önbellek özeti (bu koşu) ——");
        System.err.printf(Locale.ROOT,
                "[PMD-NATIVE]   Toplam .java: %d | dosya atlandı (byte hash): %d | dosya yeniden ölçüldü: %d%n",
                s.totalFiles(), s.filesSkippedViaFileHash(), s.filesReanalyzed());
        System.err.printf(Locale.ROOT,
                "[PMD-NATIVE]   Metod: yeniden skor %d | önbellekten %d | süre %.1f sn%n",
                s.methodsReanalyzed(), s.methodsSkippedViaHash(), seconds);
        if (s.filesSkippedViaFileHash() == 0 && s.totalFiles() > 0) {
            System.err.println("[PMD-NATIVE]   (Hiç dosya atlanmadı — ilk koşu, FRESH, state yolu değişti veya tüm dosyalar değişmiş olabilir.)");
        }
    }
}
