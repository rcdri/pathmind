package com.pathmind.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiDisplayTextTest {
    @Test void preservesMessageAndDetailsBeyondOldLimits() {
        String response = "Paragraph one.\r\n\r\n1. First item\r\n2. Second item\n" + "tail ".repeat(300);
        assertEquals(response.replace("\r\n", "\n").strip(), AiDisplayText.message(response));
        assertEquals("detail ".repeat(100).strip(), AiDisplayText.detail("detail ".repeat(100)));
    }
    @Test void oversizedMessagesAreRejectedRatherThanTruncated() {
        assertThrows(IllegalArgumentException.class, () -> AiDisplayText.message("x".repeat(64001)));
        assertTrue(AiDisplayText.diagnostic("x".repeat(16001)).contains("remaining detail omitted"));
    }
    @Test void legacyProposalParserKeepsFormattingAndAllDetails() {
        var object = new com.google.gson.JsonObject(); object.addProperty("target", "inspect");
        String response = "Discussion\n\n- " + "details ".repeat(100).strip(); object.addProperty("response", response);
        var log = new com.google.gson.JsonArray(); for (int i = 0; i < 8; i++) log.add("entry " + i + " "+ "text".repeat(50)); object.add("workLog", log);
        var proposal = AiPresetService.parseProposal(object.toString());
        assertEquals(response, proposal.response()); assertEquals(8, proposal.workLog().size());
        assertTrue(proposal.workLog().get(0).length() > 120);
    }
}
