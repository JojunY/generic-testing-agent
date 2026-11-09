package com.gta.junit;

import java.util.Collection;
import org.opentest4j.TestAbortedException;

/**
 * Pins of Cucumber scenarios (plan §5.3 item 7). Cucumber cannot wrap a step the way {@link KnownDefectExtension} wraps
 * a test method, so a pinned scenario carries the tags {@code @known-defect @known-defect:<fingerprint>} and is checked
 * in two places:
 * <ul>
 *   <li>a plain build (the team's {@code mvn verify}, the IDE): the service's Cucumber hook calls
 *       {@link #abortPinnedOutsideGta}, the scenario is reported as aborted and the build stays green;</li>
 *   <li>a gtaw run ({@code -Dgta.run=true}): the scenario runs, and {@link GtaResultsListener} reports it as a known
 *       defect only while it fails with the recorded fingerprint; a pass or another failure fails the item.</li>
 * </ul>
 * The hook, in the service's glue package:
 *
 * <pre>
 * &#64;Before(order = 0)
 * public void knownDefects(Scenario scenario) {
 *     KnownDefects.abortPinnedOutsideGta(scenario.getSourceTagNames());
 * }
 * </pre>
 */
public final class KnownDefects {
    /** The tag that carries the recorded failure fingerprint. */
    public static final String TAG_PREFIX = "known-defect:";

    /** The system property gtaw sets on its own test runs. */
    public static final String GTA_RUN = "gta.run";

    private KnownDefects() {}

    /** Reports a pinned scenario as aborted, unless gtaw runs it to check that it still fails as recorded. */
    public static void abortPinnedOutsideGta(Collection<String> tags) {
        String fingerprint = fingerprint(tags);
        if (fingerprint == null || Boolean.getBoolean(GTA_RUN)) {
            return;
        }
        throw new TestAbortedException(KnownDefectExtension.ABORT_PREFIX + scenario(tags)
                + " (pinned; gtaw runs it and checks it still fails as recorded: " + fingerprint + ")");
    }

    /** The fingerprint of {@code @known-defect:<fingerprint>} (with or without the {@code @}), or null. */
    public static String fingerprint(Collection<String> tags) {
        for (String t : tags) {
            String tag = t.startsWith("@") ? t.substring(1) : t;
            if (tag.startsWith(TAG_PREFIX) && tag.length() > TAG_PREFIX.length()) {
                return tag.substring(TAG_PREFIX.length());
            }
        }
        return null;
    }

    private static String scenario(Collection<String> tags) {
        for (String t : tags) {
            String tag = t.startsWith("@") ? t.substring(1) : t;
            if (tag.startsWith("scenario:")) {
                return tag.substring("scenario:".length());
            }
        }
        return "a pinned scenario";
    }
}
