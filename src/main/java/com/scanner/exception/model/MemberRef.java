package com.scanner.exception.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bir bulgunun içinde bulunduğu üyenin (metot, constructor, blok, alan) yapısal bilgisi.
 * Veritabanındaki metot kaydıyla eşleştirme bu bilgiyle yapılır; rapordaki "Metot" sütunu ise
 * okunabilir metin olarak ayrıca tutulur.
 */
public final class MemberRef {

    public enum Kind { METHOD, CONSTRUCTOR, STATIC_INIT, INSTANCE_INIT, FIELD, NONE }

    public static final MemberRef NONE = new MemberRef(Kind.NONE, "", Collections.<String>emptyList());

    public final Kind kind;
    /** Metot adı; constructor için sınıfın basit adı; alan için alan adları */
    public final String name;
    /** Parametre tipleri, kodda yazıldığı gibi: ["String", "List<Long>", "int[]"] */
    public final List<String> paramTypes;
    /** Metodun dönüş tipi, kodda yazıldığı gibi; metot dışındakiler için null */
    public final String returnType;

    public MemberRef(Kind kind, String name, List<String> paramTypes) {
        this(kind, name, paramTypes, null);
    }

    public MemberRef(Kind kind, String name, List<String> paramTypes, String returnType) {
        this.kind = kind;
        this.name = name == null ? "" : name;
        this.paramTypes = Collections.unmodifiableList(new ArrayList<String>(paramTypes));
        this.returnType = returnType;
    }
}
