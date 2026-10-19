package com.scanner.exception.complexity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScanErrorClassifierTest {

    @Test
    void classifiesSyntaxAndLanguageLevel() {
        assertEquals(ScanErrorCategory.PARSE_SYNTAX,
                ScanErrorClassifier.classify("Parse error: Unexpected token"));
        assertEquals(ScanErrorCategory.PARSE_LANGUAGE_LEVEL,
                ScanErrorClassifier.classify("Parse error: x (tried language levels: JAVA_17, JAVA_21)"));
        assertEquals(ScanErrorCategory.STACK_OVERFLOW,
                ScanErrorClassifier.classify("Analysis aborted: expression nesting too deep (stack overflow)"));
    }
}
