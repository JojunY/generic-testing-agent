package com.gta.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.config.GtaPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The learning loop (plan §12): lessons gtaw saw, recorded once, shown newest last. */
class LessonsTest {
    @TempDir
    Path root;

    @Test
    void shouldRecordEachLessonOnceAndShowTheNewest() throws Exception {
        GtaPaths paths = new GtaPaths(root);

        Lessons.record(paths, "reviewer", "rest-api component test", "also assert that\n nothing was stored");
        Lessons.record(paths, "reviewer", "rest-api component test", "also assert that nothing was stored");
        Lessons.record(paths, "can-fail", "messaging unit test", "assert what the code does");
        Lessons.record(paths, "reviewer", "x", "  ");

        String file = Files.readString(paths.lessons());
        assertTrue(file.startsWith("# Lessons from earlier gta runs"), file);
        assertEquals(
                List.of(
                        "- [reviewer] rest-api component test: also assert that nothing was stored",
                        "- [can-fail] messaging unit test: assert what the code does"),
                Lessons.recent(paths, 10),
                "whitespace is normalised, duplicates and blanks are dropped");
        assertEquals(List.of("- [can-fail] messaging unit test: assert what the code does"), Lessons.recent(paths, 1));
    }

    @Test
    void shouldHaveNoLessonsBeforeTheFirstRun() {
        assertEquals(List.of(), Lessons.recent(new GtaPaths(root), 10));
    }
}
