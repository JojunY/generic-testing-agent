package com.gta.core.error;

import java.util.Optional;

/**
 * Every error and gate condition gtaw can report. The prefix of the name is the category prefix (plan §5.6); the exit code
 * is shared by all codes of a category. Hints are given where a {@link GtaException} is thrown: every exception requires one.
 */
public enum ErrorCode {
    // test-failure (10)
    TEST_ASSERTION_FAILED(Category.TEST_FAILURE),
    TEST_SUT_ERROR(Category.TEST_FAILURE),
    TEST_TIMEOUT(Category.TEST_FAILURE),
    TEST_SETUP_ERROR(Category.TEST_FAILURE),
    TEST_CODE_ERROR(Category.TEST_FAILURE),
    TEST_NOT_FOUND(Category.TEST_FAILURE),
    // gate-failed (11)
    GATE_SCENARIO_UNVERIFIED(Category.GATE_FAILED),
    GATE_SCENARIO_REGRESSED(Category.GATE_FAILED),
    GATE_ORACLE_CHANGED_WITHOUT_SPEC(Category.GATE_FAILED),
    GATE_UNSTABLE_TEST(Category.GATE_FAILED),
    GATE_SHARD_UNREADABLE(Category.GATE_FAILED),

    // gate-concern (0): reported in the PR comment, never fail the build
    GATE_ASSUMED_CLAIM(Category.GATE_CONCERN),
    GATE_DEFAULTED_CLAIM(Category.GATE_CONCERN),
    GATE_NEW_CONFIRMATION(Category.GATE_CONCERN),
    GATE_RELOCKED(Category.GATE_CONCERN),
    GATE_CHARACTERIZATION_RATIO(Category.GATE_CONCERN),
    GATE_UNMAPPED_CHANGE(Category.GATE_CONCERN),
    GATE_WAIVERS_UNREADABLE(Category.GATE_CONCERN),
    GATE_SCENARIOS_UNLOCKED(Category.GATE_CONCERN),
    // usage (20)
    USAGE_UNKNOWN_ITEM(Category.USAGE),
    USAGE_UNKNOWN_FLOW(Category.USAGE),
    USAGE_INVALID_ARGUMENT(Category.USAGE),
    USAGE_NOT_AVAILABLE_IN_PHASE(Category.USAGE),
    USAGE_UNPARSEABLE_ANSWER(Category.USAGE),
    // config (21)
    CONFIG_INVALID(Category.CONFIG),
    CONFIG_NOT_INITIALIZED(Category.CONFIG),
    CONFIG_ORACLE_SHARD_INVALID(Category.CONFIG),
    // environment (30)
    ENV_NOT_A_GIT_REPO(Category.ENVIRONMENT),
    ENV_TOOL_UNAVAILABLE(Category.ENVIRONMENT),
    ENV_CHECK_FAILED(Category.ENVIRONMENT),
    ENV_UNSUPPORTED_BUILD(Category.ENVIRONMENT),
    ENV_EMULATOR_UNAVAILABLE(Category.ENVIRONMENT),
    ENV_WRAPPER_CHECKSUM_MISMATCH(Category.ENVIRONMENT),
    ENV_GIT_FAILED(Category.ENVIRONMENT),
    ENV_TEST_REPORTS_UNUSABLE(Category.ENVIRONMENT),
    // transient (31)
    TRANSIENT_REGISTRY_TIMEOUT(Category.TRANSIENT),
    // build (40)
    BUILD_COMPILE_ERROR(Category.BUILD),
    // policy (50)
    POLICY_OUT_OF_SCOPE_CHANGE(Category.POLICY),
    POLICY_ORACLE_MODIFIED(Category.POLICY),
    POLICY_ORACLE_MISMATCH(Category.POLICY),
    POLICY_TEST_CANNOT_FAIL(Category.POLICY),
    POLICY_SPEC_INVALID(Category.POLICY),
    POLICY_GTA_OWNED_FIELD(Category.POLICY),
    POLICY_UNCONFIRMED_KNOWN_DEFECT(Category.POLICY),
    // state (60)
    STATE_ILLEGAL_TRANSITION(Category.STATE),
    STATE_LOCKED(Category.STATE),
    STATE_STALE_SESSION(Category.STATE),
    STATE_NOTHING_PENDING(Category.STATE),
    STATE_BUDGET_EXHAUSTED(Category.STATE, false),
    STATE_CORRUPT(Category.STATE),
    // internal (70)
    INTERNAL_ERROR(Category.INTERNAL);

    public enum Category {
        TEST_FAILURE("test-failure", 10, false),
        GATE_FAILED("gate-failed", 11, false),
        GATE_CONCERN("gate-concern", 0, false),
        USAGE("usage", 20, false),
        CONFIG("config", 21, false),
        ENVIRONMENT("environment", 30, false),
        TRANSIENT("transient", 31, true),
        BUILD("build", 40, false),
        POLICY("policy", 50, false),
        STATE("state", 60, true),
        INTERNAL("internal", 70, false);

        public final String id;
        public final int exitCode;
        public final boolean retryable;

        Category(String id, int exitCode, boolean retryable) {
            this.id = id;
            this.exitCode = exitCode;
            this.retryable = retryable;
        }
    }

    public final Category category;
    private final boolean retryable;

    ErrorCode(Category category) {
        this(category, category.retryable);
    }

    /** @param retryable overrides the category default (a used-up budget is a STATE error that retrying cannot fix) */
    ErrorCode(Category category, boolean retryable) {
        this.category = category;
        this.retryable = retryable;
    }

    /** The code with this name; empty for a name this version does not know (e.g. read from an older state file). */
    public static Optional<ErrorCode> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** Whether running the same command again can succeed without changing anything. */
    public boolean retryable() {
        return retryable;
    }

    public int exitCode() {
        return category.exitCode;
    }

    /** Test failures that must be triaged by the reviewer instead of being repaired (plan §5.3). */
    public boolean needsTriage() {
        return this == TEST_ASSERTION_FAILED || this == TEST_SUT_ERROR || this == TEST_TIMEOUT;
    }

    /** Failures the author may repair freely (oracle stays locked). */
    public boolean isMechanical() {
        return this == BUILD_COMPILE_ERROR
                || this == TEST_CODE_ERROR
                || this == TEST_SETUP_ERROR
                || this == TEST_NOT_FOUND;
    }
}
