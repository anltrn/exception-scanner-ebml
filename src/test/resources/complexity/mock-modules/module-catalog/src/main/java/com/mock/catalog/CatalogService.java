package com.mock.catalog;

import java.util.HashMap;
import java.util.Map;

/** Ürün katalogu — orta karmaşıklık (dal dallanması). */
public class CatalogService {

    private final Map<String, String> products = new HashMap<String, String>();

    public String resolveDisplayName(String sku, String locale) {
        String base = products.get(sku);
        if (base == null) {
            return "UNKNOWN";
        }
        if ("tr".equals(locale)) {
            return base + " (TR)";
        } else if ("en".equals(locale)) {
            return base + " (EN)";
        } else if ("de".equals(locale)) {
            return base + " (DE)";
        }
        return base;
    }

    public int categoryRank(String category) {
        if (category == null) {
            return 99;
        }
        if ("ELECTRONICS".equals(category)) {
            return 1;
        }
        if ("GROCERY".equals(category)) {
            return 2;
        }
        if ("FASHION".equals(category)) {
            return 3;
        }
        return 10;
    }

    public void register(String sku, String name) {
        products.put(sku, name);
    }
}
