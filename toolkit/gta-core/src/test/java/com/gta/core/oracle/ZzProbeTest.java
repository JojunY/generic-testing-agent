package com.gta.core.oracle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZzProbeTest {
    @TempDir Path dir;

    AssertionScanner.ScenarioOracle scan(String body) throws Exception {
        Path f = dir.resolve("T" + System.nanoTime() + ".java");
        Files.writeString(f, "class T { @Test @Tag(\"scenario:order.create/s01\") void t() {\n" + body + "\n} }");
        return AssertionScanner.scan(f, "order.create/s01", null);
    }

    @Test
    void probe() throws Exception {
        var a = scan("assertThat(body).isEqualTo(\"{\\\"status\\\":\\\"PENDING\\\"}\");");
        System.out.println("JSON tokens=" + a.tokens() + " missing=" + AssertionScanner.missingThenLiterals(List.of("{\"status\":\"PENDING\"}"), a.tokens()));
        var c1 = scan("assertThat(r.status())\n  // gateway answers\n  .isEqualTo(503);");
        var c2 = scan("assertThat(r.status())\n  // upstream down\n  .isEqualTo(503);");
        var l = OracleLock.create(c1, List.of("503"), "r1");
        System.out.println("C1 stmts=" + c1.assertionStatements() + "\nC2 stmts=" + c2.assertionStatements());
        System.out.println("comment check=" + OracleLock.check(l, c2).verdict() + " fp1=" + OracleLock.fingerprint(OracleLock.lockedStatements(c1.assertionStatements(), List.of("503"))) + " fp2=" + OracleLock.fingerprint(OracleLock.lockedStatements(c2.assertionStatements(), List.of("503"))));
        var d1 = scan("assertThat(r.status()).isEqualTo(/* gw */ 503);");
        System.out.println("D1 stmts=" + d1.assertionStatements());
        var e1 = scan("assertThat(x).isEqualTo(5L);");
        System.out.println("long tokens=" + e1.tokens());
        var n = scan("assertThat(x).isEqualTo(1_000);");
        System.out.println("underscore tokens=" + n.tokens() + " missing=" + AssertionScanner.missingThenLiterals(List.of("1000"), n.tokens()));
        var h = scan("assertThat(x).isEqualTo(0x10);");
        System.out.println("hex tokens=" + h.tokens());
        var ch = scan("assertThat(x).isEqualTo('A');");
        System.out.println("char tokens=" + ch.tokens());
        var neg = scan("assertThat(x).isEqualTo(-1.5);");
        System.out.println("neg tokens=" + neg.tokens()+ " missing1.5=" + AssertionScanner.missingThenLiterals(List.of("1.5"), neg.tokens()));
    }
}
