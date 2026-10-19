package com.mock.catalog;

public class PriceRuleEngine {

    public double applyDiscount(double price, String customerTier, boolean seasonalSale) {
        double result = price;
        if (seasonalSale) {
            result = result * 0.9;
        }
        if ("GOLD".equals(customerTier)) {
            result = result * 0.85;
        } else if ("SILVER".equals(customerTier)) {
            result = result * 0.92;
        } else if ("BRONZE".equals(customerTier)) {
            result = result * 0.97;
        }
        if (result < 0) {
            result = 0;
        }
        return result;
    }
}
