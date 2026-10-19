package com.scanner.complexity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodAccessorFilterTest {

    @Test
    void recognizesSimpleGetter() {
        assertTrue(MethodAccessorFilter.isSimpleGetterOrSetter(
                "METHOD", "getName", 0, 1, 3, 1, 0, 0));
    }

    @Test
    void rejectsGetterWithOutboundCalls() {
        assertFalse(MethodAccessorFilter.isSimpleGetterOrSetter(
                "METHOD", "getName", 0, 1, 3, 1, 1, 0));
    }

    @Test
    void rejectsFatGetOrder() {
        assertFalse(MethodAccessorFilter.isSimpleGetterOrSetter(
                "METHOD", "getOrder", 0, 5, 40, 15, 0, 0));
    }
}
