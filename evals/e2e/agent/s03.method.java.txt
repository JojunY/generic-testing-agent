
    @Test
    @Tag("scenario:order.create-order/s03")
    @Tag("hardening")
    @DisplayName("[order.create-order/s03] order exactly at the threshold")
    void shouldApplyDiscountWhenSubtotalEqualsThreshold() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"c-1\",\"subtotal\":100.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discount").value(10.00))
                .andExpect(jsonPath("$.total").value(90.00));
    }
