package com.gta.core.run;

import com.gta.core.config.GtaConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenRunnerTest {
    @Test
    void shouldRunTheStabilityVariantInTheConfiguredTimezoneAndLocale() {
        GtaConfig.QualityGates gates = new GtaConfig.QualityGates();

        assertEquals("-Duser.timezone=Asia/Kolkata -Duser.language=de -Duser.country=DE", MavenRunner.stabilityVariantOptions(gates));
        assertEquals("Asia/Kolkata timezone / de-DE locale", MavenRunner.stabilityVariantLabel(gates));

        gates.stabilityTimezone = "Pacific/Chatham";
        gates.stabilityLocale = "tr";
        assertEquals("-Duser.timezone=Pacific/Chatham -Duser.language=tr", MavenRunner.stabilityVariantOptions(gates));
    }
}
