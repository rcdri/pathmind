package com.pathmind.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import java.util.Map;

/** Test-only canonical reproduction of the reported walk/jump/return workflow. */
final class AiReportedWorkflowFixture {
    static NodeGraphData original() {
        var commands = JsonParser.parseString("""
            [{"kind":"add_node","ref":"start","nodeType":"START"},
             {"kind":"add_node","ref":"initialWalk","nodeType":"WALK"},
             {"kind":"add_node","ref":"initialDirection","nodeType":"PARAM_DIRECTION"},
             {"kind":"set_parameter","ref":"initialDirection","parameterId":"direction_mode","value":"cardinal"},
             {"kind":"set_parameter","ref":"initialDirection","parameterId":"direction_cardinal","value":"south"},
             {"kind":"add_node","ref":"initialDuration","nodeType":"PARAM_DURATION"},
             {"kind":"set_parameter","ref":"initialDuration","parameterId":"duration","value":"5"},
             {"kind":"attach_parameter","host":"initialWalk","child":"initialDirection","slotIndex":0},
             {"kind":"attach_parameter","host":"initialWalk","child":"initialDuration","slotIndex":1},
             {"kind":"add_node","ref":"jump","nodeType":"JUMP"},
             {"kind":"connect","from":"start","to":"initialWalk","outputSocket":0,"inputSocket":0},
             {"kind":"connect","from":"initialWalk","to":"jump","outputSocket":0,"inputSocket":0}]
            """).getAsJsonArray();
        var result = AiGraphCommandEngine.apply(JsonParser.parseString("{\"nodes\":[],\"connections\":[],\"routines\":[]}").getAsJsonObject(), commands, Map.of(), true, true);
        if (!result.success()) throw new IllegalStateException(result.message());
        // Stable test IDs make fixture preservation measurable across live provider runs.
        var text = result.graph().toString();
        for (var ref : result.references().entrySet()) text = text.replace(ref.getValue(), "reported-" + ref.getKey());
        return NodeGraphPersistence.parseNodeGraphData(text);
    }
    static JsonArray extension() {
        return JsonParser.parseString("""
            [{"kind":"add_node","ref":"position","nodeType":"SENSOR_POSITION_OF"},
             {"kind":"set_mode","ref":"position","mode":"SENSOR_POSITION_XYZ"},
             {"kind":"add_node","ref":"self","nodeType":"PARAM_PLAYER"},
             {"kind":"attach_parameter","host":"position","child":"self","slotIndex":0},
             {"kind":"declare_variable","ref":"save","variableRef":"homeWriter","name":"home","child":"position"},
             {"kind":"add_node","ref":"forward","nodeType":"WALK"},
             {"kind":"add_node","ref":"look","nodeType":"SENSOR_LOOK_DIRECTION"},
             {"kind":"add_node","ref":"distance","nodeType":"PARAM_DISTANCE"},
             {"kind":"set_parameter","ref":"distance","parameterId":"distance","value":"5"},
             {"kind":"attach_parameter","host":"forward","child":"look","slotIndex":0},
             {"kind":"attach_parameter","host":"forward","child":"distance","slotIndex":1},
             {"kind":"add_node","ref":"return","nodeType":"GOTO"},
             {"kind":"add_node","ref":"homeReader","nodeType":"VARIABLE"},
             {"kind":"set_parameter","ref":"homeReader","parameterId":"variable","value":"home"},
             {"kind":"attach_parameter","host":"return","child":"homeReader","slotIndex":0},
             {"kind":"connect","from":"reported-jump","to":"save","outputSocket":0,"inputSocket":0},
             {"kind":"connect","from":"save","to":"forward","outputSocket":0,"inputSocket":0},
             {"kind":"connect","from":"forward","to":"return","outputSocket":0,"inputSocket":0}]
            """).getAsJsonArray();
    }
    static NodeGraphData completed() {
        var result = AiGraphCommandEngine.apply(new Gson().toJsonTree(original()).getAsJsonObject(), extension(), Map.of(), true, true);
        if (!result.success()) throw new IllegalStateException(result.message());
        return NodeGraphPersistence.parseNodeGraphData(result.graph().toString());
    }
}
