package com.mock.v3fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Analyzer v3 markdown sütunlarını kasıtlı dolduran örnekler (mock-modules taraması).
 * Her public metod bir veya birkaç sütunu hedefler.
 */
public final class V3MetricFixtures {

    private V3MetricFixtures() {
    }

    /** λ — lambda ifadesi */
    public static Supplier<String> demoLambda() {
        return () -> {
            if (Boolean.TRUE) {
                return "ok";
            }
            return "no";
        };
    }

    /** Switch — label'lı switch kolu */
    public static int demoSwitch(int code) {
        switch (code) {
            case 1:
                return 10;
            case 2:
                return 20;
            case 3:
                return 30;
            default:
                return 0;
        }
    }

    /** Try — iç içe try */
    public static void demoNestedTry() {
        try {
            try {
                risky();
            } catch (IllegalStateException e) {
                risky();
            }
        } catch (RuntimeException e) {
            risky();
        }
    }

    /** ∅Catch + YutExc */
    public static void demoEmptyCatch() {
        try {
            risky();
        } catch (IllegalStateException ignored) {
        }
    }

    /** PST + YutExc */
    public static void demoPrintStackTraceOnlyCatch() {
        try {
            risky();
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    /** ExcCatch + GenExc (+ YutExc) */
    public static void demoBroadExceptionCatch() {
        try {
            risky();
        } catch (Exception e) {
            System.err.println(e);
        }
    }

    /** Catch — çoklu catch */
    public static void demoMultipleCatch() {
        try {
            risky();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (NullPointerException e) {
            System.err.println(e);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Raw — parametresiz koleksiyon */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List demoRawList() {
        List items = new ArrayList();
        items.add("x");
        return items;
    }

    /** Concat — döngüde string birleştirme */
    public static String demoConcatInLoop(String[] parts) {
        String out = "";
        for (int i = 0; i < parts.length; i++) {
            out += parts[i];
        }
        return out;
    }

    /** Sabit — IP / SQL / jdbc URL */
    public static String demoHardcodedLiterals() {
        String db = "jdbc:mysql://10.0.0.5:3306/ledger";
        String query = "SELECT id, name FROM customers WHERE status = 'A'";
        return db + ";" + query;
    }

    /** H.Dif / H.Efor — operatör/operand çeşitliliği */
    public static double demoHalsteadBusy(int seed) {
        int a = seed;
        int b = seed + 1;
        double x = a * 1.5 + b / 2.0;
        x += Math.sin(a) * Math.cos(b);
        if (a > b && b > 0 || a == 0) {
            x = x * 2 + (a << 1);
        }
        String tag = "v" + a + "-" + b;
        for (int i = 0; i < 3; i++) {
            x += i * a + tag.length();
        }
        return x;
    }

    /** &&‖ — tek koşulda çok && / || */
    public static boolean demoComplexCondition(int a, int b, int c, int d) {
        return a > 0 && b > 0 && c > 0 || d > 0;
    }

    /** Zincir — uzun çağrı zinciri */
    public static String demoCallChain(String input) {
        return input.trim().toLowerCase().substring(0, 1).replace('a', 'b');
    }

    private static void risky() {
        if (Math.random() < 0) {
            throw new IllegalStateException();
        }
    }
}
