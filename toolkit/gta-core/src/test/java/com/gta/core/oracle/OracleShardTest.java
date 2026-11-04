package com.gta.core.oracle;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OracleShardTest {
    @TempDir
    Path dir;

    @Test
    void shouldFailClosedOnAMergeConflict() throws Exception {
        Path shard = dir.resolve("create.oracles.json");
        Files.writeString(shard, "{\n<<<<<<< HEAD\n  \"a\": {}\n=======\n  \"b\": {}\n>>>>>>> feature\n}\n");

        GtaException e = assertThrows(GtaException.class, () -> OracleShard.read(shard));

        assertEquals(ErrorCode.CONFIG_ORACLE_SHARD_INVALID, e.code());
    }

    @Test
    void shouldReadAMissingShardAsEmpty() {
        assertEquals(0, OracleShard.read(dir.resolve("none.oracles.json")).size());
    }
}
