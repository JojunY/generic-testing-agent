package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gta.core.state.WorkState;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The JSON nodes the orchestrator acts on (plan §5.1). */
class NodeResultsTest {
    @Test
    void shouldBuildNodesResultsAndAnswerNodes() {
        assertEquals("author", NodeResults.node("author", Map.of("itemId", "x")).get("node"));
        assertEquals("all done", NodeResults.done("all done", null).get("message"));
        assertEquals(
                "done", NodeResults.done("all done", Map.of("gate", "pass")).get("node"));
        assertEquals(
                "passed", NodeResults.result("passed", Map.of("runId", "r1")).get("result"));

        WorkState.Pending p = new WorkState.Pending();
        p.type = "approve";
        p.flowId = "order.create";
        p.prompt = "approve?";
        Map<String, Object> answer = NodeResults.answerNode(p);
        assertEquals("answer", answer.get("node"));
        assertEquals("approve?", answer.get("prompt"));
    }

    @Test
    void shouldCutLongTextToItsFirstLines() {
        assertEquals("a\nb", NodeResults.firstLines("a\nb\nc", 2));
        assertEquals("", NodeResults.firstLines(null, 2));
    }

    @Test
    void shouldKeepAnAssertionsExpectedAndActualValuesOnOneLine() {
        assertEquals(
                "AssertionFailedError: [HTTP status code] expected: 503 but was: 201",
                NodeResults.oneLine(
                        "AssertionFailedError: [HTTP status code] \nexpected: 503\n but was: 201\n\tat x.y(Z.java:1)"));
        assertEquals("", NodeResults.oneLine(null));
    }
}
