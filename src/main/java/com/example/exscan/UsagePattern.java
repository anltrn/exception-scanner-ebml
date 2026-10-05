package com.example.exscan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Argüman şekli deseni. Exception constructor'ları (pattern.N) ve metot çağrıları (call.N) için ortaktır.
 *   pattern.1.name=Id 0 + mesaj
 *   pattern.1.args=0,STRING
 */
final class UsagePattern {

    static final String ALL = "Tüm kullanımlar";
    /** Desen tanımlanmadığında tüm kullanımlar bu tip koduyla kaydedilir */
    static final String ALL_TYPE = "TUM_KULLANIMLAR";

    private static final Set<String> KEYWORDS = new HashSet<String>(Arrays.asList(
            "ANY", "STRING", "STRING_LITERAL", "INT", "CAUSE", "GETMESSAGE", "CONST", "VAR"));
    private static final Pattern KEY = Pattern.compile("pattern\\.(\\d+)\\.args");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+");
    private static final Pattern CALL = Pattern.compile("[A-Za-z_$][\\w$]*\\(\\)");
    private static final Pattern REF = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");

    private enum Kind { KEYWORD, NUMBER, CALL, REF, LITERAL }

    private static final class Token {
        final Kind kind;
        final String value;

        Token(Kind kind, String value) {
            this.kind = kind;
            this.value = value;
        }
    }

    final String name;
    final String action;
    final String argsText;
    /** Veritabanındaki kullanım tipi kodu, ör. KOD0_SERBEST_METIN */
    final String type;
    /** false: doğru kullanım, sadece takip için sayılır (pattern.N.legacy=false) */
    boolean legacy = true;
    /** Her argüman pozisyonu için alternatifler: GENERALERRORCODE.ERROR_CODE|'errorCode' */
    private final List<List<Token>> tokens;
    private final boolean openEnded;

    UsagePattern(String name, String action, String argsText) {
        this(name, action, argsText, null);
    }

    UsagePattern(String name, String action, String argsText, String type) {
        this.name = name;
        this.action = action;
        this.argsText = argsText;
        this.type = type == null || type.trim().isEmpty() ? typeCodeOf(name) : checkType(type.trim());
        List<List<Token>> list = new ArrayList<List<Token>>();
        boolean open = false;
        String[] parts = argsText.trim().isEmpty() ? new String[0] : argsText.split(",");
        for (int i = 0; i < parts.length; i++) {
            String raw = parts[i].trim();
            if (raw.equals("*")) {
                if (i != parts.length - 1) {
                    throw new IllegalArgumentException("'" + name + "' deseninde * sadece en sonda kullanılabilir");
                }
                open = true;
                continue;
            }
            List<Token> alternatives = new ArrayList<Token>();
            for (String alt : raw.split("\\|")) alternatives.add(parseToken(name, alt.trim()));
            list.add(alternatives);
        }
        this.tokens = Collections.unmodifiableList(list);
        this.openEnded = open;
    }

    private static Token parseToken(String patternName, String raw) {
        String upper = raw.toUpperCase(Locale.ROOT);
        if (raw.length() >= 2 && (raw.startsWith("'") && raw.endsWith("'")
                || raw.startsWith("\"") && raw.endsWith("\""))) {
            return new Token(Kind.LITERAL, raw.substring(1, raw.length() - 1));
        }
        if (KEYWORDS.contains(upper)) return new Token(Kind.KEYWORD, upper);
        if (NUMBER.matcher(raw).matches()) return new Token(Kind.NUMBER, raw);
        if (CALL.matcher(raw).matches()) return new Token(Kind.CALL, raw.substring(0, raw.length() - 2));
        if (REF.matcher(raw).matches()) return new Token(Kind.REF, raw);
        throw new IllegalArgumentException("'" + patternName + "' deseninde geçersiz değer: " + raw
                + ". Kullanılabilecekler: sayı (0, -1 ...), " + KEYWORDS
                + ", sabit adı (HATA.KODU), metot çağrısı (getErrorCode()), string ('metin'), | ile alternatifler, *");
    }

    boolean matches(List<ArgumentClassifier.Result> args) {
        if (openEnded ? args.size() < tokens.size() : args.size() != tokens.size()) return false;
        for (int i = 0; i < tokens.size(); i++) {
            boolean any = false;
            for (Token t : tokens.get(i)) {
                if (tokenMatches(t, args.get(i))) {
                    any = true;
                    break;
                }
            }
            if (!any) return false;
        }
        return true;
    }

    private static boolean tokenMatches(Token t, ArgumentClassifier.Result arg) {
        switch (t.kind) {
            case NUMBER: {
                Long v = arg.numericValue();
                return v != null && v == Long.parseLong(t.value);
            }
            case CALL:
                return t.value.equals(arg.callName);
            case REF: {
                // GENERALERRORCODE.ERROR_CODE; tam nitelikli yazım, static import (ERROR_CODE) ve
                // sabitin adıyla aynı string ("ERROR_CODE") da kabul edilir
                String src = arg.source;
                int dot = t.value.lastIndexOf('.');
                String last = dot > 0 ? t.value.substring(dot + 1) : t.value;
                return src.equals(t.value) || src.endsWith("." + t.value)
                        || (dot > 0 && src.equals(last))
                        || (arg.kind == ArgKind.STRING_SABIT && arg.text.equals(last));
            }
            case LITERAL:
                return arg.kind == ArgKind.STRING_SABIT && arg.text.equals(t.value);
            default:
                break;
        }
        switch (t.value) {
            case "ANY": return true;
            case "STRING": return arg.kind.hasMessage;
            case "STRING_LITERAL": return arg.kind == ArgKind.STRING_SABIT;
            case "INT": return arg.kind == ArgKind.SAYI;
            case "CAUSE": return arg.kind == ArgKind.SEBEP;
            case "GETMESSAGE": return arg.kind == ArgKind.EXCEPTION_MESAJI;
            case "CONST": return arg.kind == ArgKind.SABIT_REFERANS;
            case "VAR": return arg.kind == ArgKind.DEGISKEN;
            default: return false;
        }
    }

    /** pattern.N.args anahtarlarını N sırasına göre okur. */
    static List<UsagePattern> load(Properties p) {
        Map<Integer, String> byIndex = new TreeMap<Integer, String>();
        for (String key : p.stringPropertyNames()) {
            Matcher m = KEY.matcher(key);
            if (m.matches()) byIndex.put(Integer.valueOf(m.group(1)), p.getProperty(key).trim());
        }
        List<UsagePattern> out = new ArrayList<UsagePattern>();
        for (Map.Entry<Integer, String> en : byIndex.entrySet()) {
            int i = en.getKey();
            String args = en.getValue();
            String name = p.getProperty("pattern." + i + ".name", "").trim();
            if (name.isEmpty()) name = "Desen " + i + " (" + args + ")";
            String action = p.getProperty("pattern." + i + ".action", "").trim();
            if (action.isEmpty()) action = "Yeni yapıya çevrilmeli";
            UsagePattern up = new UsagePattern(name, action, args, p.getProperty("pattern." + i + ".type"));
            up.legacy = !"false".equalsIgnoreCase(p.getProperty("pattern." + i + ".legacy", "true").trim());
            out.add(up);
        }
        return Collections.unmodifiableList(out);
    }

    private static final Pattern TYPE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,49}");

    static String checkType(String type) {
        String t = type.toUpperCase(Locale.ENGLISH);
        if (!TYPE_CODE.matcher(t).matches()) {
            throw new IllegalArgumentException("Geçersiz kullanım tipi kodu: " + type
                    + ". Harfle başlamalı; büyük harf, rakam ve _ içerebilir (en fazla 50 karakter).");
        }
        return t;
    }

    /** Desen adından tip kodu üretir: "Kod 0 + serbest metin" -> KOD_0_SERBEST_METIN */
    static String typeCodeOf(String text) {
        String t = text.replace('ç', 'c').replace('Ç', 'C').replace('ğ', 'g').replace('Ğ', 'G')
                .replace('ı', 'i').replace('İ', 'I').replace('ö', 'o').replace('Ö', 'O')
                .replace('ş', 's').replace('Ş', 'S').replace('ü', 'u').replace('Ü', 'U')
                .replace("*", " DIGER ")
                .toUpperCase(Locale.ENGLISH).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (t.isEmpty() || !Character.isLetter(t.charAt(0))) t = "T_" + t;
        return t.length() > 50 ? t.substring(0, 50).replaceAll("_+$", "") : t;
    }
}
