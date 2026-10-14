package com.scanner.exception.model;

import java.util.ArrayList;
import java.util.List;

/** Bir reponun tarama sonucu. */
public final class RepoResult {
    public final RepoInfo repo;
    public int javaFiles;
    /** Aranan sınıfın adı metin olarak geçen Java dosyası sayısı (tanı için) */
    public int filesMentioningTarget;
    /** Ayrıştırılamayan dosya sayısı */
    public int parseFailures;
    public final List<UsageFinding> usages = new ArrayList<UsageFinding>();
    public final List<CatchFinding> catches = new ArrayList<CatchFinding>();
    public final List<SubclassFinding> subclasses = new ArrayList<SubclassFinding>();
    public final List<ScanError> errors = new ArrayList<ScanError>();
    /** Ekran, region ve Jasper rapor dosyaları (ebml.enabled=true ise) */
    public final List<EbmlFile> ebmlFiles = new ArrayList<EbmlFile>();

    public RepoResult(RepoInfo repo) {
        this.repo = repo;
    }

    public long count(String pattern) {
        long n = 0;
        for (UsageFinding u : usages) if (u.pattern.equals(pattern)) n++;
        return n;
    }

    public long emptyCatches() {
        long n = 0;
        for (CatchFinding c : catches) if (c.emptyBody) n++;
        return n;
    }

    public long count(EbmlFile.Kind kind) {
        long n = 0;
        for (EbmlFile e : ebmlFiles) if (e.kind == kind) n++;
        return n;
    }
}
