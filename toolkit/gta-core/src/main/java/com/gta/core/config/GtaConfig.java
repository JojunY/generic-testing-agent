package com.gta.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.util.Json;

import java.io.IOException;
import java.nio.file.Files;

/**
 * .gta/config.json. Every field is optional; defaults below are the built-in values (plan §10.4).
 * Organisation floors are enforced in {@link #applyFloors()} and cannot be lowered by a repository.
 */
public class GtaConfig {
    /** Lets editors validate the file against core/schemas/config.v1.schema.json; ignored by gtaw. */
    @JsonProperty("$schema")
    public String jsonSchema;
    public String schemaVersion = "1.0";
    public Build build = new Build();
    public String dispatch = "subagent";
    public Spec spec = new Spec();
    public Environments environments = new Environments();
    public Distribution distribution = new Distribution();
    public Budgets budgets = new Budgets();
    public QualityGates qualityGates = new QualityGates();

    public static class Build {
        public String tool = "maven";
        /** Package prefix of production code; "auto" = package of the @SpringBootApplication class. */
        public String basePackage = "auto";
        /** Maven executable; "auto" = ./mvnw when present, otherwise mvn. */
        public String maven = "auto";
    }

    public static class Spec {
        public int maxQuestionsPerRound = 5;
        public int maxClarifyRounds = 2;
        /** Rejected spec submissions in a row before the flow escalates to a human. */
        public int maxSubmitAttempts = 3;
    }

    public static class Environments {
        public Oracle oracle = new Oracle();
    }

    public static class Oracle {
        public String testUrl;
        public String userEnv = "GTA_ORACLE_USER";
        public String passwordEnv = "GTA_ORACLE_PASSWORD";
    }

    public static class Distribution {
        public String toolsUrl;
    }

    public static class Budgets {
        public int maxChecksPerItem = 8;
        public int maxAttemptsPerItem = 2;
        public int maxConsecutiveFailures = 3;
        public int maxItemsPerSession = 5;
        public int nodeTimeoutMinutes = 15;
        public int sessionIdleMinutes = 10;
    }

    public static class QualityGates {
        public int stabilityRepeat = 3;
        public boolean oracleReview = true;
        public Mutation mutation = new Mutation();
        public double concernsCharacterizationRatio = 0.5;
    }

    public static class Mutation {
        public String mode = "advisory";
        public double minStrength = 0.7;
    }

    public static GtaConfig load(GtaPaths paths) {
        GtaConfig config = Files.exists(paths.config()) ? readStrict(paths) : new GtaConfig();
        config.validate();
        config.applyFloors();
        return config;
    }

    /** Unknown keys are errors: a typo such as "maxItemPerSession" must not silently fall back to the default. */
    private static GtaConfig readStrict(GtaPaths paths) {
        try {
            return Json.MAPPER.readerFor(GtaConfig.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(paths.config().toFile());
        } catch (IOException e) {
            throw new GtaException(ErrorCode.CONFIG_INVALID, "Invalid " + paths.rel(paths.config()) + ": " + e.getMessage(),
                    "Fix the key or value; core/schemas/config.v1.schema.json lists every setting", e);
        }
    }

    void validate() {
        if (!"maven".equals(build.tool)) {
            throw new GtaException(ErrorCode.ENV_UNSUPPORTED_BUILD, "build.tool=" + build.tool + " is not supported",
                    "gta v1 supports Maven only");
        }
        if (!"subagent".equals(dispatch) && !"inline".equals(dispatch)) {
            throw new GtaException(ErrorCode.CONFIG_INVALID, "dispatch must be subagent or inline", "Fix .gta/config.json");
        }
        if (!"advisory".equals(qualityGates.mutation.mode) && !"enforced".equals(qualityGates.mutation.mode)) {
            throw new GtaException(ErrorCode.CONFIG_INVALID, "qualityGates.mutation.mode must be advisory or enforced",
                    "Fix .gta/config.json");
        }
    }

    /** Organisation floors: a repository may tighten these values but never loosen them. */
    void applyFloors() {
        qualityGates.stabilityRepeat = Math.max(qualityGates.stabilityRepeat, 2);
        qualityGates.oracleReview = true; // the independent oracle review cannot be switched off
        spec.maxQuestionsPerRound = Math.min(Math.max(spec.maxQuestionsPerRound, 1), 5);
        spec.maxClarifyRounds = Math.min(Math.max(spec.maxClarifyRounds, 1), 3);
        spec.maxSubmitAttempts = Math.min(Math.max(spec.maxSubmitAttempts, 1), 5);
        budgets.maxAttemptsPerItem = Math.min(Math.max(budgets.maxAttemptsPerItem, 1), 3);
        budgets.maxChecksPerItem = Math.min(Math.max(budgets.maxChecksPerItem, 1), 20);
        budgets.maxItemsPerSession = Math.min(Math.max(budgets.maxItemsPerSession, 1), 10);
    }
}
