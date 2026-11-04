package com.gta.core.spec;

import com.gta.core.error.GtaException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerParserTest {

    @Test
    void shouldParseRoundPicksAndRestDefault() {
        AnswerParser.Answers a = assertInstanceOf(AnswerParser.Answers.class, AnswerParser.parse("R3: 1A 2b 其余默认"));

        assertEquals("R3", a.round());
        assertEquals(Map.of("1", "A", "2", "B"), a.picks());
        assertTrue(a.restDefault());
    }

    @Test
    void shouldAcceptAsciiRestDefault() {
        AnswerParser.Answers a = assertInstanceOf(AnswerParser.Answers.class, AnswerParser.parse("R1: 1B rest default"));

        assertTrue(a.restDefault());
    }

    @Test
    void shouldParseApprovalInChineseAndEnglish() {
        assertEquals(new AnswerParser.Approval("order.create", "R4"), AnswerParser.parse("批准 order.create@R4"));
        assertEquals(new AnswerParser.Approval("order.create", "R4"), AnswerParser.parse("approve order.create@R4"));
    }

    @Test
    void shouldParseSingleChoice() {
        assertEquals(new AnswerParser.Choice("A"), AnswerParser.parse(" a) "));
    }

    @Test
    void shouldPointToAsciiFormsWhenTheTerminalMangledTheReply() {
        GtaException e = assertThrows(GtaException.class, () -> AnswerParser.parse("?? order.create@R2"));

        assertTrue(e.hint().contains("approve order.create@R4"), e.hint());
    }

    @Test
    void shouldNeverReadANegatedOrConditionalReplyAsApproval() {
        for (String reply : List.of("不批准 order.create@R2", "不批准 order.create@R2，q2 还要改",
                "don't approve order.create@R2 yet", "approve order.create@R2 after q2 is fixed")) {
            assertThrows(GtaException.class, () -> AnswerParser.parse(reply), reply);
        }
        assertEquals(new AnswerParser.Approval("order.create", "R2"), AnswerParser.parse("  批准 order.create@R2。 "));
    }
}
