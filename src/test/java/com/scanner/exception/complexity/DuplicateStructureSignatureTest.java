package com.scanner.exception.complexity;

import com.scanner.exception.complexity.AnalysisReport.ClassMetric;
import com.scanner.exception.complexity.AnalysisReport.DuplicateGroup;
import com.scanner.exception.complexity.AnalysisReport.FileMetric;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateStructureSignatureTest {

    private static final String HASH = "same-structural-hash";

    @Test
    void differentReturnTypesDoNotShareExactStructureGroup() {
        ClassMetric type = classWithMethods(
                trivial("getX", "String", "String getX()"),
                trivial("getX", "int", "int getX()"));
        DuplicateDetectionEngine engine = new DuplicateDetectionEngine();
        List<DuplicateGroup> groups = engine.detectExactStructureDuplicates(List.of(file(type)));
        assertTrue(groups.isEmpty(), () -> "Different return types must not form EXACT_STRUCTURE group");
    }

    @Test
    void sameReturnTypeAndStructureGroups() {
        MethodMetric leftMethod = trivial("copy", "Foo", "Foo copy()", HASH, "body-a");
        MethodMetric rightMethod = trivial("copy", "Foo", "Foo copy()", HASH, "body-b");
        FileMetric leftFile = file("Left.java", classWithMethods("Left", leftMethod));
        FileMetric rightFile = file("Right.java", classWithMethods("Right", rightMethod));
        DuplicateDetectionEngine engine = new DuplicateDetectionEngine();
        List<DuplicateGroup> groups = engine.detectExactStructureDuplicates(List.of(leftFile, rightFile));
        assertEquals(1, groups.size());
        assertEquals("EXACT_STRUCTURE", groups.get(0).similarityType());
        assertEquals(2, groups.get(0).members().size());
    }

    private static FileMetric file(String path, ClassMetric type) {
        return new FileMetric(path, "p", 100, 20, 1, type.methods().size(), 8, 4, 0, RiskLevel.LOW,
                List.of(), List.of(type), HalsteadMetrics.EMPTY, 0, 0, 0, 0, 0, "", false, 0);
    }

    private static FileMetric file(ClassMetric type) {
        return file("A.java", type);
    }

    private static ClassMetric classWithMethods(String className, MethodMetric... methods) {
        return new ClassMetric(className, "CLASS", 1, 40, methods.length, 0, 20, 0, 8, 4, 4.0,
                0, RiskLevel.LOW, List.of(), List.of(methods), HalsteadMetrics.EMPTY, List.of(), 0, false,
                0, 0, 0, 0, 0);
    }

    private static ClassMetric classWithMethods(MethodMetric... methods) {
        return classWithMethods("A", methods);
    }

    private static MethodMetric trivial(String name, String returnType, String signature) {
        return trivial(name, returnType, signature, HASH, HASH);
    }

    private static MethodMetric trivial(String name, String returnType, String signature,
                                        String structuralHash, String bodyHash) {
        return MethodMetric.withoutLegacyExtensions(
                name, "METHOD", signature, returnType,
                1, 12,
                4, 12, 10, 2,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0,
                0, 0,
                false, 10.0, RiskLevel.LOW, List.of(), null, bodyHash, structuralHash);
    }
}
