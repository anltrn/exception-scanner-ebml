package com.scanner.complexity;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtendedScanMetricsTest {

    private static final JavaParser PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    @Test
    void countsCognitiveExitCatchAndSwitch() {
        String src = """
                package demo;
                class Rules {
                    int pick(int x) {
                        if (x < 0) {
                            return -1;
                        } else if (x == 0) {
                            return 0;
                        }
                        try {
                            switch (x) {
                                case 1 -> { return 1; }
                                case 2 -> { return 2; }
                                default -> { return 3; }
                            }
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Error e) {
                            throw e;
                        }
                        return x;
                    }
                }
                """;
        MethodMetric pick = findMethod(src, "pick");
        assertTrue(pick.cognitiveComplexity() >= 5, "cog=" + pick.cognitiveComplexity());
        assertTrue(pick.exitPoints() >= 5, "exit=" + pick.exitPoints());
        assertEquals(2, pick.catchClauses());
        assertTrue(pick.switchCases() >= 2, "sw=" + pick.switchCases());
        assertTrue(pick.logicalStatements() > 0);
    }

    @Test
    void faz3OutboundLambdasTryLocalsChainAndCatchQuality() {
        String src = """
                package demo;
                class Orchestrator {
                    OrderRepo orderRepo;
                    KafkaClient kafka;
                    AuditTrail audit;
                    NestHelper helper;
                    java.util.List<String> list;
                    void run(Order o) {
                        orderRepo.save(o);
                        kafka.send(o.id());
                        audit.log(o);
                        try {
                            try {
                                helper.nest();
                            } catch (IllegalStateException e) {
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        int a = 1;
                        int b = 2;
                        o.getLine().getSku().getCode();
                        list.stream().map(x -> x.toString()).forEach(System.out::println);
                    }
                }
                interface OrderRepo { void save(Order o); }
                interface KafkaClient { void send(String id); }
                interface AuditTrail { void log(Order o); }
                class NestHelper { void nest() {} }
                class Order { String id() { return ""; } Line getLine() { return null; } }
                class Line { Sku getSku() { return null; } }
                class Sku { String getCode() { return ""; } }
                """;
        MethodMetric run = findMethod(src, "run");
        assertTrue(run.outboundDistinctCalls() >= 5,
                "fout=" + run.outboundDistinctCalls());
        assertEquals(1, run.lambdaCount());
        assertEquals(2, run.maxTryNestingDepth());
        assertTrue(run.localVariableCount() >= 2, "locals=" + run.localVariableCount());
        assertTrue(run.maxMethodCallChainLength() >= 3,
                "chain=" + run.maxMethodCallChainLength());
        assertEquals(1, run.emptyCatchBlocks());
        assertEquals(1, run.catchExceptionOrThrowable());
        assertEquals(1, run.catchWithOnlyPrintStackTrace());
    }

    @Test
    void throwCountsAsExitPoint() {
        String src = """
                class T {
                    void fail() {
                        throw new IllegalStateException();
                    }
                }
                """;
        assertEquals(1, findMethod(src, "fail").exitPoints());
    }

    @Test
    void nestedIfIncreasesCognitiveMoreThanFlat() {
        String flat = """
                class A {
                    void flat() {
                        if (a) { x(); }
                        if (b) { y(); }
                    }
                }
                """;
        String nested = """
                class B {
                    void nested() {
                        if (a) {
                            if (b) {
                                x();
                            }
                        }
                    }
                }
                """;
        int cogFlat = findMethod(flat, "flat").cognitiveComplexity();
        int cogNested = findMethod(nested, "nested").cognitiveComplexity();
        assertTrue(cogNested > cogFlat, "flat=" + cogFlat + " nested=" + cogNested);
    }

    private static MethodMetric findMethod(String src, String name) {
        List<AnalysisReport.ClassMetric> types = analyse(src);
        return types.stream()
                .flatMap(t -> t.methods().stream())
                .filter(m -> name.equals(m.name()))
                .findFirst()
                .orElseThrow();
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
