package com.scanner.exception.complexity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parser metod tablosundan {@link MethodScanValues} (markdown risk sütunları hariç). */
final class ServicesMarkdownMetricsParser {

    record ParsedMethod(String label, MethodScanValues scan) {
    }

    private ServicesMarkdownMetricsParser() {
    }

    static List<ParsedMethod> parse(Path markdown) throws IOException {
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        int start = text.indexOf("| Risk | Seviye |");
        if (start < 0) {
            return List.of();
        }
        List<String> tableLines = new ArrayList<>();
        for (String line : text.substring(start).split("\n")) {
            if (line.startsWith("|")) {
                tableLines.add(line);
            } else if (!tableLines.isEmpty()) {
                break;
            }
        }
        if (tableLines.size() < 3) {
            return List.of();
        }
        List<String> headers = splitRow(tableLines.get(0));
        List<ParsedMethod> out = new ArrayList<>();
        for (int i = 2; i < tableLines.size(); i++) {
            List<String> cols = splitRow(tableLines.get(i));
            if (cols.size() != headers.size()) {
                continue;
            }
            MethodScanValues scan = toScanValues(headers, cols);
            String label = cols.get(idx(headers, "Metod"));
            out.add(new ParsedMethod(label, scan));
        }
        return out;
    }

    private static List<String> splitRow(String line) {
        String[] parts = line.split("\\|", -1);
        List<String> cols = new ArrayList<>();
        for (int i = 1; i < parts.length - 1; i++) {
            cols.add(parts[i].trim());
        }
        return cols;
    }

    private static int idx(List<String> headers, String name) {
        for (int i = 0; i < headers.size(); i++) {
            if (name.equals(headers.get(i))) {
                return i;
            }
        }
        throw new IllegalArgumentException("Missing column: " + name);
    }

    private static int intCol(List<String> headers, List<String> cols, String header) {
        return parseInt(cols.get(idx(headers, header)));
    }

    private static int parseInt(String v) {
        if (v == null || v.isEmpty()) {
            return 0;
        }
        return (int) Math.round(Double.parseDouble(v.replace(',', '.')));
    }

    private static MethodScanValues toScanValues(List<String> headers, List<String> cols) {
        return new MethodScanValues(
                intCol(headers, cols, "CC"),
                intCol(headers, cols, "Satır"),
                intCol(headers, cols, "İçi"),
                intCol(headers, cols, "Param"),
                intCol(headers, cols, "Cog"),
                intCol(headers, cols, "İfade"),
                intCol(headers, cols, "Çıkış"),
                intCol(headers, cols, "Catch"),
                intCol(headers, cols, "Switch"),
                intCol(headers, cols, "FOUT†"),
                intCol(headers, cols, "λ"),
                intCol(headers, cols, "Try"),
                intCol(headers, cols, "Yerel"),
                intCol(headers, cols, "Zincir"),
                intCol(headers, cols, "∅Catch"),
                intCol(headers, cols, "ExcCatch"),
                intCol(headers, cols, "PST"),
                intCol(headers, cols, "Prim"),
                intCol(headers, cols, "&&‖"),
                intCol(headers, cols, "H.Dif"),
                intCol(headers, cols, "H.Efor"),
                intCol(headers, cols, "Raw"),
                intCol(headers, cols, "Concat"),
                intCol(headers, cols, "Sabit"),
                intCol(headers, cols, "YutExc"),
                intCol(headers, cols, "GenExc"));
    }
}
