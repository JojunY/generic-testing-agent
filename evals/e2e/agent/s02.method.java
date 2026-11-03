
    @Test
    @Tag("scenario:order.create-order/s02")
    @Tag("characterization")
    @DisplayName("[order.create-order/s02] large order gets the discount")
    void shouldApplyDiscountWhenSubtotalIsAboveThreshold() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"c-1\",\"subtotal\":150.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discount").value(15.00))
                .andExpect(jsonPath("$.total").value(135.00));
    }
