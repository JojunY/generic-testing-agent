
    @Test
    @Tag("scenario:order.create-order/s01")
    @Tag("characterization")
    @DisplayName("[order.create-order/s01] small order has no discount")
    void shouldNotDiscountWhenSubtotalIsBelowThreshold() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"c-1\",\"subtotal\":50.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discount").value(0.00))
                .andExpect(jsonPath("$.total").value(50.00))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }
