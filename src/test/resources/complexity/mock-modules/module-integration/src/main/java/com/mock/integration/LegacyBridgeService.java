package com.mock.integration;

import java.util.HashMap;
import java.util.Map;

/** Entegrasyon — Map + çoklu dal, orta karmaşıklık. */
public class LegacyBridgeService {

    private final Map<String, Object> cache = new HashMap<String, Object>();

    public Object translatePayload(String system, Map<String, Object> payload) {
        if (system == null || payload == null) {
            return null;
        }
        if ("SAP".equals(system)) {
            return mapSap(payload);
        }
        if ("ORACLE".equals(system)) {
            return mapOracle(payload);
        }
        if ("MAINFRAME".equals(system)) {
            return mapMainframe(payload);
        }
        return payload;
    }

    private Object mapSap(Map<String, Object> payload) {
        Object id = payload.get("id");
        if (id == null) {
            id = payload.get("ID");
        }
        cache.put("last-sap", id);
        return id;
    }

    private Object mapOracle(Map<String, Object> payload) {
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (e.getKey() != null && e.getKey().startsWith("ORA_")) {
                return e.getValue();
            }
        }
        return null;
    }

    private Object mapMainframe(Map<String, Object> payload) {
        Object rec = payload.get("RECORD");
        if (rec instanceof String) {
            String s = (String) rec;
            if (s.length() > 80) {
                return s.substring(0, 80);
            }
            return s;
        }
        return rec;
    }
}
