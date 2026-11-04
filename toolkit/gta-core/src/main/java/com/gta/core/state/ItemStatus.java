package com.gta.core.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Item status and its authoritative transition table (plan §5.4). */
public enum ItemStatus {
    PENDING, AUTHORING, VERIFYING, REPAIRING, TRIAGING, REVIEWING, PASSED, DEFECT_FOUND, NEEDS_HUMAN, CANCELLED;

    private static final Map<ItemStatus, Set<ItemStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(AUTHORING, VERIFYING, CANCELLED), // VERIFYING: adopt an existing test on rebuild
            AUTHORING, EnumSet.of(VERIFYING, PENDING, CANCELLED),
            REPAIRING, EnumSet.of(VERIFYING, NEEDS_HUMAN, PENDING, CANCELLED),
            VERIFYING, EnumSet.of(REPAIRING, NEEDS_HUMAN, TRIAGING, REVIEWING, PASSED, DEFECT_FOUND, PENDING, CANCELLED, AUTHORING),
            TRIAGING, EnumSet.of(REPAIRING, NEEDS_HUMAN, PENDING, CANCELLED),
            REVIEWING, EnumSet.of(PASSED, REPAIRING, NEEDS_HUMAN, PENDING, CANCELLED),
            NEEDS_HUMAN, EnumSet.of(DEFECT_FOUND, CANCELLED, PENDING),
            PASSED, EnumSet.of(TRIAGING, REPAIRING, CANCELLED),
            DEFECT_FOUND, EnumSet.of(PASSED, CANCELLED),
            CANCELLED, EnumSet.noneOf(ItemStatus.class));

    @JsonValue
    public String id() {
        return name().toLowerCase().replace('_', '-');
    }

    @JsonCreator
    public static ItemStatus of(String id) {
        return valueOf(id.toUpperCase().replace('-', '_'));
    }

    public boolean canMoveTo(ItemStatus to) {
        return ALLOWED.get(this).contains(to);
    }

    public boolean terminal() {
        return this == PASSED || this == DEFECT_FOUND || this == CANCELLED;
    }

    /** States an interrupted session can leave behind; takeover resets them to pending. */
    public boolean active() {
        return this == AUTHORING || this == VERIFYING || this == REPAIRING || this == TRIAGING || this == REVIEWING;
    }
}
