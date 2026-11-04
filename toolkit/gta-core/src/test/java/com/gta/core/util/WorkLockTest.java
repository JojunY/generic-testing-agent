package com.gta.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One writer at a time (plan §5.2). */
class WorkLockTest {
    @TempDir
    Path dir;

    @Test
    void shouldRefuseASecondWriterWhileTheLockIsHeld() {
        Path lock = dir.resolve(".gta/work/.lock");
        try (WorkLock ignored = WorkLock.acquire(lock)) {
            GtaException e = assertThrows(GtaException.class, () -> WorkLock.acquire(lock));
            assertEquals(ErrorCode.STATE_LOCKED, e.code());
        }
        try (WorkLock again = WorkLock.acquire(lock)) {
            // released on close: the next command gets it
        }
    }
}
