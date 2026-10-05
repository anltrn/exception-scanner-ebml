package com.example.exscan;

import java.util.ArrayList;
import java.util.List;

/** Bir reponun tarama sonucu. */
final class RepoResult {
    final RepoInfo repo;
    int javaFiles;
    /** Taranmayan Kotlin dosyası sayısı (uyarı için) */
    int kotlinFiles;
    /** Aranan sınıfın adı metin olarak geçen Java dosyası sayısı (tanı için) */
    int filesMentioningTarget;
    /** Ayrıştırılamayan dosya sayısı */
    int parseFailures;
    final List<UsageFinding> usages = new ArrayList<UsageFinding>();
    final List<CatchFinding> catches = new ArrayList<CatchFinding>();
    final List<SubclassFinding> subclasses = new ArrayList<SubclassFinding>();
    final List<ScanError> errors = new ArrayList<ScanError>();
    /** Ekran, region ve Jasper rapor dosyaları (ebml.enabled=true ise) */
    final List<EbmlFile> ebmlFiles = new ArrayList<EbmlFile>();

    RepoResult(RepoInfo repo) {
        this.repo = repo;
    }

    long count(String pattern) {
        long n = 0;
        for (UsageFinding u : usages) if (u.pattern.equals(pattern)) n++;
        return n;
    }

    long emptyCatches() {
        long n = 0;
        for (CatchFinding c : catches) if (c.emptyBody) n++;
        return n;
    }

    long count(EbmlFile.Kind kind) {
        long n = 0;
        for (EbmlFile e : ebmlFiles) if (e.kind == kind) n++;
        return n;
    }
}
