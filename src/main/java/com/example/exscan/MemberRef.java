package com.example.exscan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bir bulgunun içinde bulunduğu üyenin (metot, constructor, blok, alan) yapısal bilgisi.
 * Veritabanındaki metot kaydıyla eşleştirme bu bilgiyle yapılır; rapordaki "Metot" sütunu ise
 * okunabilir metin olarak ayrıca tutulur.
 */
final class MemberRef {

    enum Kind { METHOD, CONSTRUCTOR, STATIC_INIT, INSTANCE_INIT, FIELD, NONE }

    static final MemberRef NONE = new MemberRef(Kind.NONE, "", Collections.<String>emptyList());

    final Kind kind;
    /** Metot adı; constructor için sınıfın basit adı; alan için alan adları */
    final String name;
    /** Parametre tipleri, kodda yazıldığı gibi: ["String", "List<Long>", "int[]"] */
    final List<String> paramTypes;

    MemberRef(Kind kind, String name, List<String> paramTypes) {
        this.kind = kind;
        this.name = name == null ? "" : name;
        this.paramTypes = Collections.unmodifiableList(new ArrayList<String>(paramTypes));
    }
}
