package com.scanner.exception.complexity;

import com.scanner.exception.complexity.AnalysisReport.DuplicateMember;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DuplicateMemberConsolidationTest {

    @Test
    void mergesSlidingWindowsOnSameMethod() {
        String file = "src/Foo.java";
        String cls = "Foo";
        String sig = "bar()";
        List<DuplicateMember> raw = List.of(
                new DuplicateMember(file, cls, sig, 221, 234),
                new DuplicateMember(file, cls, sig, 222, 235),
                new DuplicateMember(file, cls, sig, 225, 238),
                new DuplicateMember(file, cls, "other()", 10, 20));
        List<DuplicateMember> out = DuplicateDetectionEngine.consolidateMembers(raw);
        assertEquals(2, out.size());
        assertEquals(221, out.stream().filter(m -> sig.equals(m.method())).findFirst().orElseThrow().startLine());
        assertEquals(238, out.stream().filter(m -> sig.equals(m.method())).findFirst().orElseThrow().endLine());
    }
}
