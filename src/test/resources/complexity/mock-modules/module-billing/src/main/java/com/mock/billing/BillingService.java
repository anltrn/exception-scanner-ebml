package com.mock.billing;

import java.math.BigDecimal;
import java.util.List;

/** Basit faturalama — düşük + orta karmaşıklık karışık. */
public class BillingService {

    public BigDecimal calculateLineTotal(int quantity, BigDecimal unitPrice) {
        if (quantity <= 0) {
            return BigDecimal.ZERO;
        }
        return unitPrice.multiply(new BigDecimal(quantity));
    }

    public boolean isOverdue(long dueDateMillis, long nowMillis) {
        return nowMillis > dueDateMillis;
    }

    public BigDecimal calculateWithTaxAndTier(List<String> lineSkus, List<Integer> qtys, String customerTier,
                                              boolean vatExempt, String countryCode) {
        BigDecimal total = BigDecimal.ZERO;
        if (lineSkus == null || qtys == null || lineSkus.size() != qtys.size()) {
            return total;
        }
        for (int i = 0; i < lineSkus.size(); i++) {
            Integer q = qtys.get(i);
            if (q == null || q <= 0) {
                continue;
            }
            BigDecimal line = new BigDecimal(q);
            if ("PLATINUM".equals(customerTier)) {
                line = line.multiply(new BigDecimal("0.88"));
            } else if ("GOLD".equals(customerTier)) {
                line = line.multiply(new BigDecimal("0.92"));
            } else if ("SILVER".equals(customerTier)) {
                line = line.multiply(new BigDecimal("0.97"));
            }
            if (!vatExempt) {
                if ("TR".equals(countryCode)) {
                    line = line.multiply(new BigDecimal("1.20"));
                } else if ("DE".equals(countryCode)) {
                    line = line.multiply(new BigDecimal("1.19"));
                } else if ("US".equals(countryCode)) {
                    for (int r = 0; r < 3; r++) {
                        if (r == 1) {
                            line = line.multiply(new BigDecimal("1.08"));
                        }
                    }
                }
            }
            total = total.add(line);
        }
        return total;
    }
}
