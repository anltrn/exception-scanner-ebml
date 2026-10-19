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

class ClassScanMetricsTest {

    private static final JavaParser PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    @Test
    void publicMethodCountAndCeProxy() {
        String src = """
                package demo;
                import java.util.List;
                import java.util.ArrayList;
                class Facade {
                    private final OrderRepo repo;
                    Facade(OrderRepo repo) { this.repo = repo; }
                    public void a() { repo.save(new Order()); }
                    public void b() { List<Order> x = new ArrayList<>(); }
                    void internal() { }
                }
                interface OrderRepo { void save(Order o); }
                class Order { }
                """;
        AnalysisReport.ClassMetric facade = findType(src, "Facade");
        assertEquals(2, facade.publicMethodCount());
        assertTrue(facade.efferentCouplingProxy() >= 2,
                "ce=" + facade.efferentCouplingProxy());
    }

    private static AnalysisReport.ClassMetric findType(String src, String name) {
        List<AnalysisReport.ClassMetric> types = analyse(src);
        return types.stream()
                .filter(t -> t.name().endsWith(name))
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
