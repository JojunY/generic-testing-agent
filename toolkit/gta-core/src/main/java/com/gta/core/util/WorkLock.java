package com.gta.core.util;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** OS-level lock on .gta/work/.lock. Held for every state mutation, run and commit (single writer). */
public final class WorkLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private WorkLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static WorkLock acquire(Path lockFile) {
        try {
            Files.createDirectories(lockFile.getParent());
            FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                channel.close();
                throw new GtaException(ErrorCode.STATE_LOCKED, "Another gtaw process holds " + lockFile,
                        "Wait for it to finish, then run ./gtaw next again");
            }
            return new WorkLock(channel, lock);
        } catch (IOException e) {
            throw new GtaException(ErrorCode.INTERNAL_ERROR, "Cannot lock " + lockFile + ": " + e.getMessage(), "Check file permissions");
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException ignored) {
            // releasing a lock on close is best effort
        }
    }
}
