package com.mock.orders;

import java.util.Date;

public class CancellationPolicyService {

    public double computeRefundPercent(String channel, Date orderDate, Date cancelDate, String customerSegment,
                                       boolean loyaltyMember, int itemCount) {
        if (channel == null || orderDate == null || cancelDate == null) {
            return 0.0;
        }
        long diff = cancelDate.getTime() - orderDate.getTime();
        if (diff < 0) {
            return 0.0;
        }
        double hours = diff / (1000.0 * 60.0 * 60.0);
        double pct = 0.0;
        if ("WEB".equals(channel)) {
            if (hours < 24) {
                pct = 1.0;
            } else if (hours < 72) {
                pct = 0.5;
            } else {
                pct = 0.1;
            }
        } else if ("STORE".equals(channel)) {
            if (hours < 48) {
                pct = 0.8;
            } else {
                pct = 0.0;
            }
        } else if ("MARKETPLACE".equals(channel)) {
            for (int tier = 0; tier < 4; tier++) {
                if (itemCount > tier * 5 && hours < (tier + 1) * 24.0) {
                    pct = 0.9 - tier * 0.15;
                }
            }
        }
        if (loyaltyMember && pct > 0) {
            pct = Math.min(1.0, pct + 0.05);
        }
        if ("VIP".equals(customerSegment)) {
            pct = Math.min(1.0, pct + 0.10);
        } else if ("RISK".equals(customerSegment)) {
            pct = pct * 0.5;
        }
        return pct;
    }
}
