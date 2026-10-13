package com.example.exscan.model;

/** Klonlama, okuma veya ayrıştırma sırasında oluşan hata. */
public final class ScanError {
    public final String projectKey;
    public final String repo;
    public final String stage;
    public final String file;
    public final String message;

    public ScanError(RepoInfo repo, String stage, String file, String message) {
        this.projectKey = repo.projectKey;
        this.repo = repo.slug;
        this.stage = stage;
        this.file = file == null ? "" : file;
        this.message = message == null ? "" : message;
    }
}
