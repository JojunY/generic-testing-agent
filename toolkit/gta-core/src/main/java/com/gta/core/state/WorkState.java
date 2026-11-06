package com.gta.core.state;

import com.gta.core.change.Baseline;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.oracle.Lock;
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
    /** PR mode (plan §6): the affected flows still to work on, after the active one. */
    public List<String> prQueue = new ArrayList<>();
    /** PR mode: the base the branch is compared with; catching tests are also run on it. */
    public String prBase;
    /** PR mode: the PR description, a documented intent for the analyst. */
    public String prIntent;
    /** Incident mode: the reported incident the active flow reproduces. */
    public String incident;
    /** What happened and what was checked, newest last, for the status block (plan §5.10). */
    public List<Event> events = new ArrayList<>();
    /** How many of {@link #events} people have already been shown. */
    public int eventsShown;

    /** One line of the status block: a message key of the catalogue and its arguments. */
    public static class Event {
        public String key;
        public List<String> args = new ArrayList<>();
        /** A check (who verified what) rather than something that happened. */
        public boolean check;
    }

    private static final int MAX_EVENTS = 50;

    /** Records that something happened ({@code check} false) or was verified ({@code check} true). */
    public void event(boolean check, String key, String... args) {
        Event e = new Event();
        e.key = key;
        e.args = new ArrayList<>(List.of(args));
        e.check = check;
        events.add(e);
        if (events.size() > MAX_EVENTS) {
            int drop = events.size() - MAX_EVENTS;
            events.subList(0, drop).clear();
            eventsShown = Math.max(0, eventsShown - drop);
        }
    }

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
        public String type; // answer | approve | defect | escalation | naming-conflict | question
        public String flowId;
        public String itemId;
        public String round;
        public String rev;
        public String prompt; // the exact block the orchestrator must show verbatim
        /**
         * What a plain {@code OK} takes (plan §5.10): the recommended option letter of a decision, or the recommended
         * answer to a test author's question.
         */
        public String recommended;
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
        /** A human allowed gta to add its tests to a developer's existing test class of the same name (plan §9.3). */
        public boolean sharesDeveloperFile;
        /** Catching tests: how the failing test does on the PR's base ({@code passes}, {@code fails}, {@code unknown}). */
        public String onBase;
        /** Questions the test author asked a person instead of guessing, and their answers (plan §5.10). */
        public List<AuthorQuestion> questions = new ArrayList<>();
    }

    /** One {@code gtaw submit --question}; {@code answer} stays null until the person replies. */
    public static class AuthorQuestion {
        public String question;
        public String recommended;
        public String answer;
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
            throw new GtaException(
                    ErrorCode.USAGE_UNKNOWN_ITEM, "Unknown item " + id, "Run ./gtaw status to list items");
        }
        return s;
    }

    public void move(ItemState item, ItemStatus to) {
        if (item.status == to) {
            return;
        }
        if (!item.status.canMoveTo(to)) {
            throw new GtaException(
                    ErrorCode.STATE_ILLEGAL_TRANSITION,
                    "Illegal transition " + item.status.id() + " -> " + to.id() + " for " + item.id,
                    "Run ./gtaw next to get the next valid step");
        }
        item.status = to;
    }
}
