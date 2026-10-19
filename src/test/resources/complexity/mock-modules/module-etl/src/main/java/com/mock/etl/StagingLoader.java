package com.mock.etl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

/** ETL — switch, try/catch, while; yüksek karmaşıklık. */
public class StagingLoader {

    public int loadRows(String csvPayload, String targetTable) throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(csvPayload));
        int loaded = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().length() == 0) {
                continue;
            }
            try {
                loaded += ingestLine(line, targetTable);
            } catch (IllegalArgumentException ex) {
                loaded += handleBadRow(line, ex.getMessage());
            } catch (RuntimeException ex) {
                if (isFatal(ex)) {
                    throw ex;
                }
                loaded += handleBadRow(line, ex.getMessage());
            }
        }
        reader.close();
        return loaded;
    }

    private int ingestLine(String line, String targetTable) {
        String[] parts = line.split(",");
        if (parts.length < 2) {
            throw new IllegalArgumentException("too few columns");
        }
        switch (targetTable.charAt(0)) {
            case 'D':
                return loadDim(parts);
            case 'F':
                return loadFact(parts);
            case 'S':
                return loadStage(parts);
            default:
                throw new IllegalArgumentException("unknown table: " + targetTable);
        }
    }

    private int loadDim(String[] parts) {
        int ok = 0;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] != null && parts[i].length() > 0) {
                ok++;
            }
        }
        return ok > 0 ? 1 : 0;
    }

    private int loadFact(String[] parts) {
        if (parts[0] == null || parts[1] == null) {
            return 0;
        }
        try {
            Double.parseDouble(parts[1]);
            return 1;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int loadStage(String[] parts) {
        return parts.length >= 3 ? 1 : 0;
    }

    private int handleBadRow(String line, String reason) {
        if (reason != null && reason.indexOf("fatal") >= 0) {
            return 0;
        }
        return line.length() > 100 ? 0 : 0;
    }

    private boolean isFatal(RuntimeException ex) {
        return ex.getMessage() != null && ex.getMessage().indexOf("FATAL") >= 0;
    }
}
