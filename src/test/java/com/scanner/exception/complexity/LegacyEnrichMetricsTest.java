package com.scanner.exception.complexity;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyEnrichMetricsTest {

    private static final JavaParser PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    @Test
    void enrichAttachesLegacyHalsteadAndSmellsForPackagedTypes() {
        String src = """
                package demo.legacy;
                class SmellBox {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    java.util.List raw() {
                        java.util.List items = new java.util.ArrayList();
                        items.add("x");
                        return items;
                    }

                    void swallowed() {
                        try {
                            fail();
                        } catch (RuntimeException ignored) {
                        }
                    }

                    void fail() {
                        throw new RuntimeException();
                    }
                }
                """;
        MethodMetric raw = findMethod(src, "raw");
        MethodMetric swallowed = findMethod(src, "swallowed");

        assertFalse(raw.codeSmells().isEmpty(), "raw type smell");
        assertTrue(MethodScanValues.fromMetric(raw).rawTypeUsage() > 0);

        assertFalse(swallowed.exceptionSmells().isEmpty(), "swallowed exception smell");
        assertTrue(MethodScanValues.fromMetric(swallowed).swallowedExceptionSmells() > 0);
        assertTrue(swallowed.halstead().volume() > 0 || raw.halstead().volume() > 0, "halstead volume");
    }

    private static MethodMetric findMethod(String src, String name) {
        ParseResult<CompilationUnit> parsed = PARSER.parse(src);
        CompilationUnit cu = parsed.getResult().orElseThrow();
        AnalysisReport.FileMetric file = FileMetricsBuilder.build(
                "SmellBox.java", cu, new RiskCalculator(), Map.of(), null, "", false);
        return file.classes().stream()
                .flatMap(c -> c.methods().stream())
                .filter(m -> name.equals(m.name()))
                .findFirst()
                .orElseThrow();
    }
}
