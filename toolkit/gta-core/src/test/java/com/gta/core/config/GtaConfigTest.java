package com.gta.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GtaConfigTest {
    @TempDir
    Path root;

    private GtaConfig load(String json) throws Exception {
        GtaPaths paths = new GtaPaths(root);
        Files.createDirectories(paths.gta());
        Files.writeString(paths.config(), json);
        return GtaConfig.load(paths);
    }

    @Test
    void shouldReportAMistypedKeyInsteadOfIgnoringIt() {
        GtaException e = assertThrows(GtaException.class, () -> load("{ \"budgets\": { \"maxItemPerSession\": 3 } }"));

        assertEquals(ErrorCode.CONFIG_INVALID, e.code());
        assertTrue(e.getMessage().contains("maxItemPerSession"), e.getMessage());
    }

    @Test
    void shouldAcceptTheSchemaReference() throws Exception {
        GtaConfig c = load(
                "{ \"$schema\": \"./schemas/config.v1.schema.json\", \"budgets\": { \"maxItemsPerSession\": 3 } }");

        assertEquals(3, c.budgets.maxItemsPerSession);
    }

    @Test
    void shouldNotLetARepositoryLowerTheOrganisationFloors() throws Exception {
        GtaConfig c = load(
                "{ \"qualityGates\": { \"oracleReview\": false, \"stabilityRepeat\": 1 }, \"spec\": { \"maxQuestionsPerRound\": 9 } }");

        assertTrue(c.qualityGates.oracleReview);
        assertEquals(2, c.qualityGates.stabilityRepeat);
        assertEquals(5, c.spec.maxQuestionsPerRound);
    }
}
