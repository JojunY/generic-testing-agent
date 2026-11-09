package com.gta.junit.cucumber;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gta.junit.KnownDefects;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

/** Glue of KnownDefectsTest: the hook a service adds, and two steps. */
public class PinnedSteps {
    private int value;

    @Before(order = 0)
    public void knownDefects(Scenario scenario) {
        KnownDefects.abortPinnedOutsideGta(scenario.getSourceTagNames());
    }

    @Given("the value is {int}")
    public void value(int v) {
        value = v;
    }

    @Then("the value equals {int}")
    public void equals(int expected) {
        assertEquals(expected, value);
    }
}
