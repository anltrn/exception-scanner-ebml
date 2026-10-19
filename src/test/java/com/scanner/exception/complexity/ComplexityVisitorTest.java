package com.scanner.exception.complexity;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplexityVisitorTest {

    private static final JavaParser PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    @Test
    void recordMethodsAndCompactConstructorAreCounted() {
        String src = """
                package demo;
                public record User(String id, int score) {
                    public User {
                        if (score < 0) {
                            throw new IllegalArgumentException();
                        }
                    }
                    public boolean isHigh() {
                        if (score > 90) {
                            return true;
                        }
                        return false;
                    }
                }
                """;
        List<AnalysisReport.ClassMetric> types = analyse(src);
        AnalysisReport.ClassMetric record = types.stream()
                .filter(t -> "RECORD".equals(t.kind()))
                .findFirst()
                .orElseThrow();
        assertTrue(record.methodCount() >= 2, "compact ctor + isHigh");
        Optional<MethodMetric> compact = record.methods().stream()
                .filter(m -> "COMPACT_CONSTRUCTOR".equals(m.kind()))
                .findFirst();
        assertTrue(compact.isPresent());
        assertTrue(compact.get().cyclomaticComplexity() >= 2);
        Optional<MethodMetric> isHigh = record.methods().stream()
                .filter(m -> "isHigh".equals(m.name()))
                .findFirst();
        assertTrue(isHigh.isPresent());
        assertTrue(isHigh.get().cyclomaticComplexity() >= 2);
    }

    @Test
    void staticInitializerAndFieldLambdaAreCounted() {
        String src = """
                package demo;
                public class Hooks {
                    static {
                        if (true) {
                            System.out.println("init");
                        }
                    }
                    private Runnable task = () -> {
                        if (Boolean.TRUE) {
                            System.out.println("run");
                        }
                    };
                }
                """;
        List<AnalysisReport.ClassMetric> types = analyse(src);
        AnalysisReport.ClassMetric hooks = types.stream()
                .filter(t -> t.name().endsWith("Hooks"))
                .findFirst()
                .orElseThrow();
        assertTrue(hooks.methods().stream().anyMatch(m -> "STATIC_INITIALIZER".equals(m.kind())));
        assertTrue(hooks.methods().stream().anyMatch(m -> "FIELD_LAMBDA".equals(m.kind())));
    }

    @Test
    void anonymousClassMethodsIncludeStartLineInSignature() {
        String src = """
                package demo;
                public class Outer {
                    void runTwice() {
                        Runnable a = new Runnable() {
                            public void run() {
                                if (true) { }
                            }
                        };
                        Runnable b = new Runnable() {
                            public void run() {
                                if (false) { }
                            }
                        };
                    }
                }
                """;
        List<AnalysisReport.ClassMetric> types = analyse(src);
        long anonymousRuns = types.stream()
                .filter(t -> "ANONYMOUS_CLASS".equals(t.kind()))
                .flatMap(t -> t.methods().stream())
                .filter(m -> "run".equals(m.name()))
                .peek(m -> assertTrue(m.signature().contains(" @"), m.signature()))
                .count();
        assertEquals(2, anonymousRuns);
    }

    private static List<AnalysisReport.ClassMetric> analyse(String src) {
        ParseResult<CompilationUnit> parsed = PARSER.parse(src);
        assertTrue(parsed.isSuccessful(), parsed.getProblems().toString());
        CompilationUnit cu = parsed.getResult().orElseThrow();
        BitSet lines = ComplexityVisitor.computeCodeLines(cu);
        ComplexityVisitor visitor = new ComplexityVisitor(new RiskCalculator(), lines);
        cu.accept(visitor, null);
        return visitor.getClassMetrics();
    }
}
