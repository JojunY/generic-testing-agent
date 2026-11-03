package com.gta.core.config;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.util.Json;

import java.nio.file.Files;

/**
 * .gta/config.json. Every field is optional; defaults below are the built-in values (plan §10.4).
 * Organisation floors are enforced in {@link #applyFloors()} and cannot be lowered by a repository.
 */
public class GtaConfig {
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
        public String source = "gta";
        public int maxQuestionsPerRound = 5;
        public int maxClarifyRounds = 2;
    }

    public static class Environments {
        public boolean allowCloud = false;
        public String gcpProject;
        public String region = "asia-east1";
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
        GtaConfig config = Files.exists(paths.config()) ? Json.read(paths.config(), GtaConfig.class) : new GtaConfig();
        config.validate();
        config.applyFloors();
        return config;
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
        budgets.maxAttemptsPerItem = Math.min(Math.max(budgets.maxAttemptsPerItem, 1), 3);
        budgets.maxChecksPerItem = Math.min(Math.max(budgets.maxChecksPerItem, 1), 20);
        budgets.maxItemsPerSession = Math.min(Math.max(budgets.maxItemsPerSession, 1), 10);
    }
}
