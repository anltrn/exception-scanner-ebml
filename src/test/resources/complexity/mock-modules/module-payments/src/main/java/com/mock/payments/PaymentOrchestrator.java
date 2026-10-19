package com.mock.payments;

import java.util.List;

public class PaymentOrchestrator {

    public String route(List<String> providers, double amount, String currency, boolean retry) {
        if (providers == null || providers.isEmpty()) return "NONE";
        for (int i = 0; i < providers.size(); i++) {
            String p = providers.get(i);
            if (p == null) continue;
            if ("STRIPE".equals(p) && amount < 5000) return p;
            if ("ADYEN".equals(p) && "EUR".equals(currency)) return p;
            if ("LOCAL".equals(p) && retry) return p;
        }
        return providers.get(0);
    }

    public int reconcile(List<Integer> auth, List<Integer> capture) {
        int mismatches = 0;
        if (auth == null || capture == null) return -1;
        int n = Math.min(auth.size(), capture.size());
        for (int i = 0; i < n; i++) {
            if (!auth.get(i).equals(capture.get(i))) mismatches++;
        }
        return mismatches;
    }
}
