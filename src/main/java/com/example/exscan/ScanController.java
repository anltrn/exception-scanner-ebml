package com.example.exscan;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "Taramalar", description = "Tarama başlatma, durum izleme ve rapor indirme")
class ScanController {

    private final ScanJobService service;

    ScanController(ScanJobService service) {
        this.service = service;
    }

    @Hidden
    @GetMapping("/")
    RedirectView home() {
        return new RedirectView("/swagger-ui.html", true);
    }

    @PostMapping("/api/scans")
    @Operation(summary = "Tarama başlat",
            description = "Taramayı sıraya koyar ve hemen döner. Aynı anda tek tarama çalışır, diğerleri sırada bekler. "
                    + "Boş gövde ({}) sunucudaki scanner.properties ile tarar.")
    @ApiResponse(responseCode = "202", description = "Sıraya alındı")
    @ApiResponse(responseCode = "400", description = "Ayar veya parametre hatası")
    ResponseEntity<ScanJob> start(@RequestBody(required = false) ScanRequest request) throws IOException {
        ScanJob job = service.submit(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(java.net.URI.create("/api/scans/" + job.id))
                .body(job);
    }

    @GetMapping("/api/scans")
    @Operation(summary = "Taramaları listele", description = "En yeni tarama başta")
    List<ScanJob> list() {
        return service.list();
    }

    @GetMapping("/api/scans/{id}")
    @Operation(summary = "Tarama durumu", description = "status: QUEUED, RUNNING, SUCCEEDED, FAILED")
    ScanJob get(@PathVariable String id) {
        return service.get(id);
    }

    @DeleteMapping("/api/scans/{id}")
    @Operation(summary = "Taramayı sil", description = "Sıradaysa iptal eder, bittiyse raporlarıyla birlikte siler")
    @ApiResponse(responseCode = "204", description = "Silindi")
    @ApiResponse(responseCode = "409", description = "Tarama çalışıyor")
    ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/api/scans/{id}/log", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Tarama logu", description = "Tarama sürerken de okunabilir")
    String log(@PathVariable String id,
               @Parameter(description = "Sadece son n satır (0 = tamamı)") @RequestParam(defaultValue = "0") int tail)
            throws IOException {
        service.get(id);
        Path f;
        try {
            f = service.file(id, ScanJobService.LOG_FILE);
        } catch (java.util.NoSuchElementException e) {
            return ""; // henüz başlamadı
        }
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        if (tail > 0 && lines.size() > tail) lines = lines.subList(lines.size() - tail, lines.size());
        return String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }

    @GetMapping("/api/scans/{id}/files")
    @Operation(summary = "Rapor dosyaları", description = "Ana rapor, CSV, proje bazlı raporlar ve log")
    List<ScanJobService.ReportFile> files(@PathVariable String id) throws IOException {
        return service.files(id);
    }

    @GetMapping("/api/scans/{id}/files/download")
    @Operation(summary = "Rapor dosyası indir", description = "path: /files listesindeki yol, ör. projects/report_PRJ.xlsx")
    ResponseEntity<Resource> download(@PathVariable String id, @RequestParam String path) {
        return send(service.file(id, path));
    }

    @GetMapping("/api/scans/{id}/report")
    @Operation(summary = "Ana Excel raporunu indir", description = "exception_scan_report.xlsx")
    ResponseEntity<Resource> report(@PathVariable String id) {
        return send(service.file(id, ScanJobService.MASTER_REPORT));
    }

    @GetMapping("/api/settings")
    @Operation(summary = "Sunucudaki temel ayarlar",
            description = "İstekte verilmeyen değerler için kullanılan scanner.properties. Token ve şifreler maskelenir.")
    Map<String, String> settings() throws IOException {
        return service.effectiveSettings();
    }

    private static ResponseEntity<Resource> send(Path f) {
        String name = f.getFileName().toString();
        MediaType type = name.endsWith(".xlsx")
                ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : name.endsWith(".csv") ? MediaType.parseMediaType("text/csv;charset=UTF-8")
                : name.endsWith(".log") ? MediaType.parseMediaType("text/plain;charset=UTF-8")
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(f));
    }
}
