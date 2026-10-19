package com.mock.orders;

import java.util.ArrayList;
import java.util.List;

/** Sipariş — iç içe döngüler, orta-yüksek karmaşıklık. */
public class OrderFulfillmentService {

    public int countShippableLines(List<String> skus, List<Integer> quantities, List<Boolean> inStock) {
        int count = 0;
        for (int i = 0; i < skus.size(); i++) {
            String sku = skus.get(i);
            if (sku == null || sku.length() == 0) {
                continue;
            }
            for (int j = 0; j < inStock.size(); j++) {
                if (i == j && Boolean.TRUE.equals(inStock.get(j))) {
                    Integer qty = quantities.get(j);
                    if (qty != null && qty > 0) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    public String pickWarehouse(String region, int priority) {
        if (region == null) {
            return "WH-DEFAULT";
        }
        if ("EU".equals(region)) {
            if (priority > 5) {
                return "WH-EU-EXPRESS";
            }
            return "WH-EU-STANDARD";
        }
        if ("US".equals(region)) {
            if (priority > 5) {
                return "WH-US-EXPRESS";
            }
            return "WH-US-STANDARD";
        }
        return "WH-GLOBAL";
    }

    public List<String> splitByWeight(List<String> lines, int maxWeight) {
        List<String> batches = new ArrayList<String>();
        int acc = 0;
        for (int k = 0; k < lines.size(); k++) {
            String line = lines.get(k);
            int w = line != null ? line.length() : 0;
            if (acc + w > maxWeight && acc > 0) {
                batches.add("batch-" + batches.size());
                acc = 0;
            }
            acc += w;
        }
        if (acc > 0) {
            batches.add("batch-" + batches.size());
        }
        return batches;
    }
}
