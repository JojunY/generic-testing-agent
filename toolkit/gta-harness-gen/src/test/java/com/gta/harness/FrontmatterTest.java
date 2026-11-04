package com.gta.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrontmatterTest {

    @Test
    void shouldRoundTripFieldsThatNeedQuoting() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("description", "Does X: with a colon, `code` and \"quotes\". Use when needed.");
        f.put("tools", List.of("read", "search"));
        f.put("user-invocable", false);
        Map<String, Object> bash = new LinkedHashMap<>();
        bash.put("*", "deny");
        bash.put(".\\gtaw.cmd *", "allow");
        f.put("permission", Map.of("bash", bash));

        Frontmatter parsed = Frontmatter.parse(new Frontmatter(f, "\n## Role\n").render());

        assertEquals(f.get("description"), parsed.fields().get("description"));
        assertEquals(List.of("read", "search"), parsed.fields().get("tools"));
        assertEquals(false, parsed.fields().get("user-invocable"));
        assertEquals(Map.of("bash", bash), parsed.fields().get("permission"));
        assertEquals("\n## Role\n", parsed.body());
    }

    @Test
    void shouldQuoteReservedWords() {
        assertEquals("\"true\"", Frontmatter.scalar("true"));
        assertEquals("primary", Frontmatter.scalar("primary"));
    }
}
