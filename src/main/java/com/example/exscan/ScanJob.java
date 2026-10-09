package com.example.exscan;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/** Bir taramanın durumu. Rapor klasöründe job.json olarak da saklanır; sunucu yeniden başlayınca okunur. */
@Schema(description = "Tarama işi")
public class ScanJob {

    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }

    public enum Trigger { API, SCHEDULE }

    @Schema(description = "Tarama numarası", example = "20261009-142233-a1b2")
    public String id;
    public Status status;
    @Schema(description = "API: Swagger / REST ile başlatıldı, SCHEDULE: haftalık otomatik tarama")
    public Trigger trigger;
    public Instant createdAt;
    public Instant startedAt;
    public Instant finishedAt;
    @Schema(description = "Gönderilen istek")
    public ScanRequest request;
    @Schema(description = "Bitince: repositories, excludedRepositories, javaFiles, usages, errors, reportDir")
    public Map<String, Object> summary;
    @Schema(description = "Başarısız olduysa hata mesajı")
    public String error;
}
