package com.gta.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

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
    /** Opt into a later roadmap phase early (plan §15), e.g. "P3" for a pilot service; null = gta's current phase. */
    public String phase;

    public String dispatch = "subagent";
    public Spec spec = new Spec();
    public Environments environments = new Environments();
    public Distribution distribution = new Distribution();
    public Budgets budgets = new Budgets();
    public QualityGates qualityGates = new QualityGates();

    public static class Build {
        /** {@code maven}, {@code gradle}, or {@code auto}: Gradle when the service has a Gradle build and no pom.xml. */
        public String tool = "auto";
        /** Package prefix of production code; "auto" = package of the @SpringBootApplication class. */
        public String basePackage = "auto";
        /** Maven executable; "auto" = ./mvnw when present, otherwise mvn. */
        public String maven = "auto";
        /** Gradle executable; "auto" = ./gradlew when present, otherwise gradle. */
        public String gradle = "auto";
        /**
         * Name of an environment variable holding the JDK this service builds with (for example a JDK 8 for an old
         * Spring Boot 2 service); null = Maven uses the JDK gtaw runs on. A name, not a path, because paths differ
         * per machine.
         */
        public String javaHomeEnv;
        /**
         * The Maven module that holds the service, relative to the repository root (for example "order-api"); "" for
         * a single-module repository. gta works in one module at a time (plan §15, P1).
         */
        public String module = "";
    }

    public static class Spec {
        public int maxQuestionsPerRound = 5;
        public int maxClarifyRounds = 2;
        /** Rejected spec submissions in a row before the flow escalates to a human. */
        public int maxSubmitAttempts = 3;
        /**
         * Where the repository's own specs come from (plan §3.6): {@code gta} (none besides gta's), {@code openspec}
         * ({@code openspec/specs/}) or {@code speckit} ({@code specs/}). Those are read as documented sources, never
         * rewritten.
         */
        public String source = "gta";
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
        /** Timezone and locale of the stability variant run: chosen to differ from typical developer machines. */
        public String stabilityTimezone = "Asia/Kolkata";

        public String stabilityLocale = "de-DE";
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
            return Json.MAPPER
                    .readerFor(GtaConfig.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(paths.config().toFile());
        } catch (IOException e) {
            throw new GtaException(
                    ErrorCode.CONFIG_INVALID,
                    "Invalid " + paths.rel(paths.config()) + ": " + e.getMessage(),
                    "Fix the key or value; core/schemas/config.v1.schema.json lists every setting",
                    e);
        }
    }

    void validate() {
        if (!List.of("auto", "maven", "gradle").contains(build.tool)) {
            throw new GtaException(
                    ErrorCode.CONFIG_INVALID,
                    "build.tool must be auto, maven or gradle, not " + build.tool,
                    "Fix .gta/config.json");
        }
        if (!"subagent".equals(dispatch) && !"inline".equals(dispatch)) {
            throw new GtaException(
                    ErrorCode.CONFIG_INVALID, "dispatch must be subagent or inline", "Fix .gta/config.json");
        }
        if (!List.of("gta", "openspec", "speckit").contains(spec.source)) {
            throw new GtaException(
                    ErrorCode.CONFIG_INVALID, "spec.source must be gta, openspec or speckit", "Fix .gta/config.json");
        }
        if (!"advisory".equals(qualityGates.mutation.mode) && !"enforced".equals(qualityGates.mutation.mode)) {
            throw new GtaException(
                    ErrorCode.CONFIG_INVALID,
                    "qualityGates.mutation.mode must be advisory or enforced",
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
