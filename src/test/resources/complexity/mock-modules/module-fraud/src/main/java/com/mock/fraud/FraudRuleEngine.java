package com.mock.fraud;

import java.util.List;
import java.util.Map;

/** Yüksek karmaşıklık — fraud kuralları. */
public class FraudRuleEngine {

    public int scoreTransaction(Map<String, Object> tx, List<String> history, String channel,
                                boolean velocityCheck, int customerTier) {
        if (tx == null) return 100;
        int score = 0;
        Object amt = tx.get("amount");
        if (amt instanceof Number) {
            double a = ((Number) amt).doubleValue();
            if (a > 10000) score += 30;
            else if (a > 5000) score += 15;
            else if (a > 1000) score += 5;
        }
        if ("WEB".equals(channel)) {
            if (velocityCheck && history != null) {
                for (int i = 0; i < history.size(); i++) {
                    if (history.get(i) != null && history.get(i).indexOf("DECLINE") >= 0) {
                        score += 10;
                    }
                }
            }
        } else if ("MOBILE".equals(channel)) {
            score += customerTier < 2 ? 20 : 5;
        }
        if (customerTier <= 0) score += 25;
        return score;
    }

    public boolean blockIfHighRisk(int score, String region, boolean whitelist) {
        if (whitelist) return false;
        if (score >= 80) return true;
        if (score >= 50 && "HIGH_RISK".equals(region)) return true;
        if (score >= 40 && "EU".equals(region)) {
            return score > 45;
        }
        return false;
    }
}
