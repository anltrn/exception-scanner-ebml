package com.mock.billing;

import java.util.ArrayList;
import java.util.List;

public class InvoiceRepository {

    private final List<String> store = new ArrayList<String>();

    public void save(String invoiceId) {
        if (invoiceId != null && invoiceId.length() > 0) {
            store.add(invoiceId);
        }
    }

    public String findById(String invoiceId) {
        for (int i = 0; i < store.size(); i++) {
            if (store.get(i).equals(invoiceId)) {
                return store.get(i);
            }
        }
        return null;
    }

    public int reconcileDuplicates(String prefix, boolean dryRun) {
        int removed = 0;
        for (int i = 0; i < store.size(); i++) {
            String id = store.get(i);
            if (id == null) {
                continue;
            }
            if (prefix != null && id.startsWith(prefix)) {
                for (int j = i + 1; j < store.size(); j++) {
                    if (id.equals(store.get(j))) {
                        if (!dryRun) {
                            store.remove(j);
                            j--;
                        }
                        removed++;
                    }
                }
            }
        }
        return removed;
    }
}
