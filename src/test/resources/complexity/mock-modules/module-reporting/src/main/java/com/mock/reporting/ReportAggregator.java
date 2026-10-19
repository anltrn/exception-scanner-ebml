package com.mock.reporting;

import java.util.List;

/** Raporlama — derin iç içe yapı, yüksek risk hotspot adayı. */
public class ReportAggregator {

    @SuppressWarnings("unchecked")
    public String buildExecutiveSummary(List rows, String region, int year, boolean includeForecast) {
        StringBuilder sb = new StringBuilder();
        if (rows == null || rows.size() == 0) {
            return "EMPTY";
        }
        if (region == null) {
            region = "ALL";
        }
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (row == null) {
                continue;
            }
            if ("EU".equals(region)) {
                if (year > 2020) {
                    if (includeForecast) {
                        for (int q = 1; q <= 4; q++) {
                            if (q == 2 || q == 4) {
                                sb.append("EU-FQ-").append(q);
                                if (row.toString().length() > 10) {
                                    sb.append("-DETAIL");
                                    if (year % 2 == 0) {
                                        sb.append("-EVEN");
                                    }
                                }
                            } else if (q == 1 && includeForecast) {
                                sb.append("EU-Q1-BOOT");
                            }
                        }
                    } else {
                        sb.append("EU-HIST-").append(year);
                    }
                } else {
                    sb.append("EU-LEGACY");
                }
            } else if ("US".equals(region)) {
                if (includeForecast) {
                    for (int m = 0; m < 12; m++) {
                        if (m % 3 == 0) {
                            try {
                                sb.append(parseMetric(row, m));
                                if (m > 6 && year > 2019) {
                                    sb.append("-H2");
                                }
                            } catch (Exception e) {
                                sb.append("ERR");
                                if (e.getMessage() != null && e.getMessage().indexOf("fatal") >= 0) {
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    sb.append("US-SNAPSHOT");
                }
            } else {
                switch (year % 5) {
                    case 0:
                        sb.append("G0");
                        break;
                    case 1:
                        sb.append("G1");
                        if (includeForecast) {
                            sb.append("-FC");
                        }
                        break;
                    case 2:
                        sb.append("G2");
                        break;
                    case 3:
                        sb.append("G3");
                        break;
                    default:
                        sb.append("G4");
                        break;
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : "N/A";
    }

    @SuppressWarnings("unchecked")
    public int rankRowsByMetric(List rows, String metric, boolean descending) {
        if (rows == null || rows.size() == 0) {
            return -1;
        }
        int bestIdx = 0;
        int bestVal = 0;
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            int val = 0;
            if ("length".equals(metric) && row != null) {
                val = row.toString().length();
            } else if ("hash".equals(metric) && row != null) {
                val = row.hashCode();
            }
            if (i == 0) {
                bestVal = val;
            } else if (descending && val > bestVal) {
                bestVal = val;
                bestIdx = i;
            } else if (!descending && val < bestVal) {
                bestVal = val;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private String parseMetric(Object row, int month) {
        if (month < 0 || month > 11) {
            return "0";
        }
        String s = row.toString();
        if (s.length() > 50) {
            return s.substring(0, 10);
        }
        return s + "-" + month;
    }
}
