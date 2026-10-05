package com.example.exscan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aranan metot çağrısı. Ayar dosyasında şöyle tanımlanır:
 *   call.1.name=Mobil hata kodu
 *   call.1.method=put
 *   call.1.scope=outBag          (opsiyonel; boşsa hangi nesne üzerinden çağrıldığına bakılmaz)
 *   call.1.args=GENERALERRORCODE.ERROR_CODE,ANY
 */
final class CallPattern {

    private static final Pattern KEY = Pattern.compile("call\\.(\\d+)\\.method");

    final String name;
    final String action;
    final String scope;
    final String method;
    final UsagePattern args;

    CallPattern(String name, String action, String scope, String method, String argsText, String type) {
        this.name = name;
        this.action = action;
        this.scope = scope == null ? "" : scope.trim();
        this.method = method.trim();
        this.args = new UsagePattern(name, action, argsText, type);
    }

    /** Veritabanındaki kullanım tipi kodu */
    String type() {
        return args.type;
    }

    boolean scopeMatches(String scopeText) {
        if (scope.isEmpty() || "*".equals(scope)) return true;
        if (scopeText == null) return false;
        String s = scopeText.replaceAll("\\s+", "");
        return s.equals(scope) || s.endsWith("." + scope); // this.outBag da kabul edilir
    }

    /** Raporda Exception / Çağrı sütununda görünen ad */
    String display() {
        return (scope.isEmpty() || "*".equals(scope) ? "" : scope + ".") + method + "(...)";
    }

    String spec() {
        return (scope.isEmpty() || "*".equals(scope) ? "" : scope + ".") + method + "(" + args.argsText + ")";
    }

    /** call.N.method anahtarlarını N sırasına göre okur. */
    static List<CallPattern> load(Properties p) {
        Map<Integer, String> byIndex = new TreeMap<Integer, String>();
        for (String key : p.stringPropertyNames()) {
            Matcher m = KEY.matcher(key);
            if (m.matches() && !p.getProperty(key).trim().isEmpty()) {
                byIndex.put(Integer.valueOf(m.group(1)), p.getProperty(key).trim());
            }
        }
        List<CallPattern> out = new ArrayList<CallPattern>();
        for (Map.Entry<Integer, String> en : byIndex.entrySet()) {
            int i = en.getKey();
            String method = en.getValue();
            String scope = p.getProperty("call." + i + ".scope", "").trim();
            String args = p.getProperty("call." + i + ".args");
            args = args == null ? "*" : args.trim();
            String name = p.getProperty("call." + i + ".name", "").trim();
            if (name.isEmpty()) name = "Çağrı " + i + " (" + method + ": " + args + ")";
            String action = p.getProperty("call." + i + ".action", "").trim();
            if (action.isEmpty()) action = "Merkezi hata yapısına taşınmalı";
            CallPattern cp = new CallPattern(name, action, scope, method, args, p.getProperty("call." + i + ".type"));
            cp.args.legacy = !"false".equalsIgnoreCase(p.getProperty("call." + i + ".legacy", "true").trim());
            out.add(cp);
        }
        return Collections.unmodifiableList(out);
    }
}
