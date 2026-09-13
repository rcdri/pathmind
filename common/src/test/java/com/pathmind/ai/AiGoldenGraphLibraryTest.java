package com.pathmind.ai;

import com.google.gson.JsonArray;
import java.util.List;
import java.util.Set;
import com.pathmind.nodes.NodeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiGoldenGraphLibraryTest {
    @Test
    void focusedGeneratedPatternsBuildThroughSemanticCommands() {
        assertDoesNotThrow(AiGoldenGraphPatterns::conditionWithSensor, "condition-with-sensor");
        assertDoesNotThrow(AiGoldenGraphPatterns::repeatUntil, "repeat-until");
        assertDoesNotThrow(AiGoldenGraphPatterns::nestedControls, "nested-controls");
        assertDoesNotThrow(AiGoldenGraphPatterns::routineWithArguments, "routine-arguments");
        assertDoesNotThrow(AiGoldenGraphPatterns::inventoryWorkflow, "inventory-workflow");
        assertDoesNotThrow(AiGoldenGraphPatterns::navigationAndCollection, "navigation-collection");
    }

    @Test
    void bundledExamplesAreDiscoverableAndPassSerializedValidation() {
        JsonArray examples = AiGoldenGraphLibrary.list();

        assertEquals(10, examples.size());
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

    @Test
    void detailedMatchesPrioritizeTheMostRelevantExecutablePattern() {
        JsonArray matches = AiGoldenGraphLibrary.detailsMatching(Set.of(NodeType.CONTROL_REPEAT, NodeType.JUMP), 2);

        assertFalse(matches.isEmpty());
        assertEquals("repeat-action", matches.get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(matches.get(0).getAsJsonObject().has("graph"));
    }

    @Test
    void structuralTraitQueriesReturnOnlyTheClosestBoundedExamples() {
        JsonArray matches = AiGoldenGraphLibrary.listMatching(Set.of(),
            Set.of(AiExampleTrait.REPEAT_UNTIL), 20);

        assertEquals(1, matches.size());
        assertEquals("repeat-until", matches.get(0).getAsJsonObject().get("id").getAsString());
        assertFalse(matches.get(0).getAsJsonObject().has("graph"));
        assertFalse(matches.get(0).getAsJsonObject().has("preview"));
    }

    @Test
    void genericScaffoldingTypesDoNotRetrieveArbitraryExamples() {
        assertTrue(AiGoldenGraphLibrary.listMatching(Set.of(NodeType.START)).isEmpty());
    }

    @Test
    void everyMajorStructuralConceptHasAnIndexedExample() {
        Set<AiExampleTrait> required = Set.of(
            AiExampleTrait.CONDITION_WITH_SENSOR,
            AiExampleTrait.IF_ELSE,
            AiExampleTrait.REPEAT_UNTIL,
            AiExampleTrait.FORK_JOIN,
            AiExampleTrait.PARAMETER_ATTACHMENTS,
            AiExampleTrait.VARIABLES,
            AiExampleTrait.LISTS,
            AiExampleTrait.NESTED_CONTROLS,
            AiExampleTrait.ROUTINE_ARGUMENTS,
            AiExampleTrait.INVENTORY_WORKFLOW,
            AiExampleTrait.NAVIGATION_COLLECTION
        );

        for (AiExampleTrait trait : required) {
            assertFalse(AiGoldenGraphLibrary.listMatching(Set.of(), Set.of(trait), 1).isEmpty(), trait.name());
        }
    }
}
