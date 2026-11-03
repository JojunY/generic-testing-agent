package com.example.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("component")
@Tag("gta")
@SpringBootTest
class OrderSeedIT {

    @Autowired
    ApplicationContext context;

    @Test
    @Tag("scenario:order")
    @DisplayName("seed: application context starts")
    void shouldStartContextWhenApplicationBoots() {
        assertThat(context.getBeanDefinitionCount()).isPositive();
    }
}
