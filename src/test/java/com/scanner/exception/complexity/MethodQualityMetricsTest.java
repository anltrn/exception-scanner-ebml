package com.scanner.exception.complexity;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodQualityMetricsTest {

    private static final JavaParser PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    @Test
    void primitiveObsessionWeightsSignatureAndLiterals() {
        MethodDeclaration method = parseMethod("""
                void charge(String id, int amount, boolean dryRun, String currency) {
                    String msg = "paid";
                    int x = 1;
                }
                """);
        int index = MethodQualityMetrics.primitiveObsessionIndex(method, method.getBody().orElseThrow());
        assertTrue(index >= 10, "index=" + index);
    }

    @Test
    void maxBooleanOperatorsPeaksOnLongConditional() {
        MethodDeclaration method = parseMethod("""
                boolean ok(int a, int b, int c, int d) {
                    if (a > 0 && b > 0 || c > 0 && d > 0 && a == b) {
                        return true;
                    }
                    return false;
                }
                """);
        int peak = MethodQualityMetrics.maxBooleanOperatorsInCondition(method.getBody().orElseThrow());
        assertEquals(4, peak);
    }

    private static MethodDeclaration parseMethod(String bodySource) {
        ParseResult<CompilationUnit> parsed = PARSER.parse("class T { " + bodySource + " }");
        CompilationUnit cu = parsed.getResult().orElseThrow();
        return cu.findFirst(MethodDeclaration.class).orElseThrow();
    }
}
