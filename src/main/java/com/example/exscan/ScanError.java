package com.example.exscan;

/** Klonlama, okuma veya ayrıştırma sırasında oluşan hata. */
final class ScanError {
    final String projectKey;
    final String repo;
    final String stage;
    final String file;
    final String message;

    ScanError(RepoInfo repo, String stage, String file, String message) {
        this.projectKey = repo.projectKey;
        this.repo = repo.slug;
        this.stage = stage;
        this.file = file == null ? "" : file;
        this.message = message == null ? "" : message;
    }
}
