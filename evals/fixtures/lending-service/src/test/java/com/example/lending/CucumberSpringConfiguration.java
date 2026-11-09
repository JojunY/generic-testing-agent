package com.example.lending;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The Spring context every Cucumber scenario runs in; inventory-service is a WireMock server. */
@CucumberContextConfiguration
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestChannelBinderConfiguration.class)
public class CucumberSpringConfiguration {
    public static final WireMockServer INVENTORY = new WireMockServer(
            // the JDK client of Boot 4 RestClient asks for HTTP/2 over plain HTTP, which WireMock 3 cancels
            WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));

    static {
        INVENTORY.start();
    }

    @DynamicPropertySource
    static void inventory(DynamicPropertyRegistry registry) {
        registry.add("inventory.url", INVENTORY::baseUrl);
    }
}
