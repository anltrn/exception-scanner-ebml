package com.example.exscan;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Kullanımları CSV olarak yazar. Excel'in Türkçe ayarlarında doğrudan açılabilmesi için
 * ayraç olarak noktalı virgül ve UTF-8 BOM kullanılır.
 */
final class CsvReportWriter {

    private static final String[] HEADERS = {"Proje", "Repo", "Modül", "Dosya", "Satır", "Sınıf", "Metot",
            "Exception / Çağrı", "Eşleşen Desen", "Tip Kodu", "Id", "Mesaj Tipi", "Mesaj", "Bağlam", "Test Kodu", "Kod", "Bağlantı", "DB Sınıf Id", "DB Metot Id", "Eşleşme", "Eşleşme Notu"};

    private CsvReportWriter() { }

    static void write(Path file, List<RepoResult> results) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write('\uFEFF');
            line(w, HEADERS);
            // Veritabanı sütunları her zaman yazılır; eşleştirme yapılmadıysa boş kalır
            for (RepoResult r : results) {
                for (UsageFinding u : r.usages) {
                    line(w, new String[]{u.projectKey, u.repo, u.module, u.file, String.valueOf(u.line),
                            u.className, u.member, u.targetClass, u.pattern, u.typeCode, u.idValue,
                            u.messageKind == null ? "" : u.messageKind.label, u.message, u.context,
                            u.testCode ? "Evet" : "Hayır", u.expression, u.link == null ? "" : u.link,
                            u.dbClassId == null ? "" : String.valueOf(u.dbClassId),
                            u.dbMethodId == null ? "" : String.valueOf(u.dbMethodId), u.matchStatus, u.matchNote});
                }
            }
        }
    }

    private static void line(Writer w, String[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) w.write(';');
            String v = values[i] == null ? "" : values[i].replace("\r", " ").replace("\n", " ");
            w.write('"');
            w.write(v.replace("\"", "\"\""));
            w.write('"');
        }
        w.write("\r\n");
    }
}
