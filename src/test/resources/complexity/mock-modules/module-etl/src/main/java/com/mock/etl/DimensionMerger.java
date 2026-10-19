package com.mock.etl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SCD2 benzeri birleştirme — yüksek dallanma. */
public class DimensionMerger {

    public Map mergeRows(List incoming, List existing, String naturalKey, boolean strictMode) {
        Map<String, Object> result = new HashMap<String, Object>();
        if (incoming == null || existing == null) {
            result.put("status", "SKIP");
            return result;
        }
        int inserted = 0;
        int updated = 0;
        int rejected = 0;
        for (int i = 0; i < incoming.size(); i++) {
            Object row = incoming.get(i);
            if (row == null) {
                rejected++;
                continue;
            }
            String key = extractKey(row, naturalKey);
            if (key == null || key.length() == 0) {
                if (strictMode) {
                    rejected++;
                    continue;
                }
                key = "UNK-" + i;
            }
            boolean found = false;
            for (int j = 0; j < existing.size(); j++) {
                Object ex = existing.get(j);
                if (ex == null) {
                    continue;
                }
                if (key.equals(extractKey(ex, naturalKey))) {
                    found = true;
                    if (hasChanged(row, ex)) {
                        updated++;
                    }
                    break;
                }
            }
            if (!found) {
                inserted++;
            }
        }
        result.put("inserted", new Integer(inserted));
        result.put("updated", new Integer(updated));
        result.put("rejected", new Integer(rejected));
        return result;
    }

    private String extractKey(Object row, String naturalKey) {
        if (row instanceof Map) {
            Map m = (Map) row;
            Object v = m.get(naturalKey);
            return v != null ? v.toString() : null;
        }
        return row.toString();
    }

    private boolean hasChanged(Object a, Object b) {
        if (a == b) {
            return false;
        }
        if (a == null || b == null) {
            return true;
        }
        return !a.toString().equals(b.toString());
    }
}
