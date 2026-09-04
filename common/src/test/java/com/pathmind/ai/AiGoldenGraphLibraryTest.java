package com.pathmind.ai;

import com.google.gson.JsonArray;
import java.util.List;
import java.util.Set;
import com.pathmind.nodes.NodeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiGoldenGraphLibraryTest {
    @Test
    void bundledExamplesAreDiscoverableAndPassSerializedValidation() {
        JsonArray examples = AiGoldenGraphLibrary.list();

        assertEquals(3, examples.size());
        for (int index = 0; index < examples.size(); index++) {
            String id = examples.get(index).getAsJsonObject().get("id").getAsString();
            AiGoldenGraphLibrary.Entry entry = AiGoldenGraphLibrary.find(id).orElseThrow();
            List<String> issues = AiGraphIntegrityValidator.validate(entry.graph(), true, true);
            assertTrue(issues.isEmpty(), id + ": " + issues);
            AiPresetService.Validation validation = AiPresetService.validateProposal(
                new AiPresetService.Proposal(entry.name(), "", List.of(), entry.graph(), "new"),
                "", true, true);
            assertTrue(validation.valid(), id + ": " + validation.issues());
            assertFalse(AiExecutionPreview.preview(entry.graph()).getAsJsonArray("paths").isEmpty());
        }
    }

    @Test
    void arbitraryResourcePathsCannotBeLoaded() {
        assertTrue(AiGoldenGraphLibrary.find("../../settings").isEmpty());
    }

    @Test
    void relevantExamplesAreFilteredByRequestedNodeTypes() {
        JsonArray matches = AiGoldenGraphLibrary.listMatching(Set.of(NodeType.CREATE_LIST));

        assertEquals(1, matches.size());
        assertEquals("onboarding-3", matches.get(0).getAsJsonObject().get("id").getAsString());
    }
}
