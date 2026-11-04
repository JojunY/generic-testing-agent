package com.gta.core.spec;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefTest {
    @Test
    void shouldParseAMethodRef() {
        Ref r = Ref.parse("method:com.acme.order.api.OrderController#create").orElseThrow();

        assertEquals("method", r.kind());
        assertTrue(r.isCode());
        assertEquals("com.acme.order.api", r.packageName());
        assertEquals("OrderController", r.simpleName());
        assertEquals("com/acme/order/api/OrderController.java", r.sourcePath());
        assertEquals("com.acme.order.api.OrderController#create", r.classAndMember());
    }

    @Test
    void shouldParseABeanRefWithoutMember() {
        Ref r = Ref.parse("bean:com.acme.Clock").orElseThrow();

        assertEquals(null, r.member());
        assertEquals("com.acme.Clock", r.classAndMember());
    }

    @Test
    void shouldGiveTheDefaultPackageAsEmpty() {
        Ref r = Ref.parse("method:App#run").orElseThrow();

        assertEquals("", r.packageName());
        assertEquals("App", r.simpleName());
    }

    @Test
    void shouldKeepConfigKeysAsTheTarget() {
        Ref r = Ref.parse("config:order.max-items").orElseThrow();

        assertFalse(r.isCode());
        assertEquals("order.max-items", r.target());
    }

    @Test
    void shouldRejectTextThatIsNotARef() {
        assertEquals(Optional.empty(), Ref.parse(null));
        assertEquals(Optional.empty(), Ref.parse("OrderController"));
        assertEquals(Optional.empty(), Ref.parse("method:"));
        assertEquals(Optional.empty(), Ref.parse(":x"));
        assertEquals(Optional.empty(), Ref.parse("method:#create"));
    }
}
