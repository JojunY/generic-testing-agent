package com.gta.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.error.GtaException;
import org.junit.jupiter.api.Test;

/** Id formats (AGENTS.md naming, plan §9.4). */
class IdsTest {
    @Test
    void shouldAcceptOnlyTwoSegmentFlowIdsOutsideTheReservedDomain() {
        assertEquals("order.create", Ids.requireFlowId("order.create"));
        assertThrows(GtaException.class, () -> Ids.requireFlowId("order"));
        assertThrows(GtaException.class, () -> Ids.requireFlowId("Order.Create"));
        assertThrows(GtaException.class, () -> Ids.requireFlowId(null));
        assertThrows(GtaException.class, () -> Ids.requireFlowId("foundation.seed"), "foundation is reserved");
        assertEquals("order", Ids.domainOf("order.create-order"));
        assertEquals("create-order", Ids.flowNameOf("order.create-order"));
    }

    @Test
    void shouldMakeFileSafeKeysAndTimestampedIds() {
        assertEquals("order.create__s01__component-01", Ids.itemKey("order.create/s01#component-01"));
        assertTrue(Ids.newSessionId().matches("s\\d{14}-[a-z2-9]{4}"));
        assertTrue(Ids.newRunId("1234567890abcdef").matches("r\\d{14}-1234567-[a-z2-9]{4}"));
        assertTrue(Ids.newRunId(null).contains("-0000000-"), "no commit yet");
    }

    @Test
    void shouldConvertBetweenCamelKebabAndPascalCase() {
        assertEquals("create-order", Ids.kebab("createOrder"));
        assertEquals("on-payment", Ids.kebab("onPayment"));
        assertEquals("CreateOrder", Ids.pascal("create-order"));
        assertEquals("OrderCreate", Ids.pascal("order.create"));
    }
}
