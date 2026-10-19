package com.scanner.complexity;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundCallKeysTest {

    private static final JavaParser PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    @Test
    void filtersLocalJdkAndSameTypeCalls() {
        MethodDeclaration method = parseMethod("""
                package demo;
                import com.google.gson.stream.JsonWriter;
                class WriterHost {
                    void write(JsonWriter w) {
                        w.name("x");
                        save();
                        String s = "a";
                        list.add(s);
                    }
                    void save() {}
                    java.util.List<String> list;
                }
                """);
        CompilationUnit cu = method.findCompilationUnit().orElseThrow();
        ImportTypeIndex index = ImportTypeIndex.of(cu);
        OutboundCallContext ctx = OutboundCallContext.forEnclosingType("WriterHost", index,
                LocalReceiverTypes.forCallable(method, index));
        Set<String> keys = new HashSet<>();
        method.getBody().orElseThrow().findAll(MethodCallExpr.class).forEach(call ->
                OutboundCallKeys.externalCallKey(call, ctx).ifPresent(keys::add));
        assertTrue(keys.stream().anyMatch(k -> k.contains("JsonWriter")), "keys=" + keys);
        assertFalse(keys.stream().anyMatch(k -> k.contains("name") && k.startsWith("<local>")), "keys=" + keys);
    }

    private static MethodDeclaration parseMethod(String src) {
        ParseResult<CompilationUnit> parsed = PARSER.parse(src);
        return parsed.getResult().orElseThrow().findFirst(MethodDeclaration.class).orElseThrow();
    }
}
