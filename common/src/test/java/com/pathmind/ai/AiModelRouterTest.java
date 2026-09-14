package com.pathmind.ai;

import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.NodeType;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiModelRouterTest {
    @Test void automaticOpenAiRoutingUsesFastForSimpleAndStrongForStructuralWork() {
        assertEquals("gpt-5.4-mini", AiModelRouter.resolve(AiProviderType.OPENAI, "Auto", "Make a preset that jumps once.", null));
        assertEquals("gpt-5.5", AiModelRouter.resolve(AiProviderType.OPENAI, "",
            "After it jumps, save my current position, then walk five blocks and travel back with pathfinding.", graph(3)));
    }

    @Test void explicitSelectionsAlwaysWinAndOtherProvidersKeepTheirDefaults() {
        assertEquals("gpt-5-mini", AiModelRouter.resolve(AiProviderType.OPENAI, "gpt-5-mini", "Create routines with arguments", graph(20)));
        assertEquals(AiProviderType.ANTHROPIC.defaultModel(), AiModelRouter.resolve(AiProviderType.ANTHROPIC, "Auto", "Complex routine", graph(20)));
    }

    private static NodeGraphData graph(int size) {
        var nodes = new ArrayList<NodeGraphData.NodeData>();
        for (int i = 0; i < size; i++) nodes.add(new NodeGraphData.NodeData("n" + i, i == 0 ? NodeType.START : NodeType.JUMP, null, i * 100, 0, new ArrayList<>()));
        return new NodeGraphData(nodes, new ArrayList<>());
    }
}
