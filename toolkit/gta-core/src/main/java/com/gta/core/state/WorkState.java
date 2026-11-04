package com.gta.core.state;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** .gta/work/state.json - rebuildable working state; only gtaw writes it (plan §5.2). */
public class WorkState {
    public String schemaVersion = "1.0";
    public Session session;
    public String mode = "flow";
    public String activeFlow;
    public int seq;
    public int itemsThisSession;
    public int consecutiveFailures;
    public Pending pending;
    public Map<String, ItemState> items = new LinkedHashMap<>();
    public List<String> sessionPassed = new ArrayList<>();
    public List<String> newConfirmations = new ArrayList<>();
    public List<String> pendingReview = new ArrayList<>();
    public boolean sessionEnded;
    public Map<String, SpecWork> specWork = new LinkedHashMap<>();

    /** Analyst work on one flow's spec. */
    public static class SpecWork {
        public String token;
        public Baseline baseline;
        public boolean needsRevision;
        public List<String> answersToApply = new ArrayList<>();
        public int attempts;
    }

    public static class Session {
        public String id;
        public String startedAt;
        public String lastSeenAt;
    }

    /** A decision waiting for a human (plan §5.5). */
    public static class Pending {
        public String type;      // answer | approve | defect | escalation
        public String flowId;
        public String itemId;
        public String round;
        public String rev;
        public String prompt;    // the exact block the orchestrator must show verbatim
    }

    public static class ItemState {
        public String id;
        public ItemStatus status = ItemStatus.PENDING;
        public int attempts;
        public int checks;
        public String token;
        public Baseline baseline;
        public Lock lock;
        public LastError lastError;
        public String evidenceRunId;
        public String reason;
        public String verdict;
        public String commit;
        public boolean inputsChangedAfterAssertion;
        public boolean structuralOracleChange;
        public String intentOverride;
    }

    public static class Baseline {
        public String head;
        public Map<String, String> dirty = new LinkedHashMap<>();
    }

    public static class Lock {
        public List<String> tokens = new ArrayList<>();
        public List<String> statements = new ArrayList<>();
        public String fingerprint;
        public String watched;
        public String reachedInRun;
    }

    public static class LastError {
        public String code;
        public String fingerprint;
        public String message;
        public int repeats;
        /** Fingerprint in the gta-junit format, used for @KnownDefect(failure=...). */
        public String defectFingerprint;
    }

    public ItemState item(String id) {
        ItemState s = items.get(id);
        if (s == null) {
            throw new GtaException(ErrorCode.USAGE_UNKNOWN_ITEM, "Unknown item " + id, "Run ./gtaw status to list items");
        }
        return s;
    }

    public void move(ItemState item, ItemStatus to) {
        if (item.status == to) {
            return;
        }
        if (!item.status.canMoveTo(to)) {
            throw new GtaException(ErrorCode.STATE_ILLEGAL_TRANSITION,
                    "Illegal transition " + item.status.id() + " -> " + to.id() + " for " + item.id,
                    "Run ./gtaw next to get the next valid step");
        }
        item.status = to;
    }
}
