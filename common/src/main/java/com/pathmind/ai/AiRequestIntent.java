package com.pathmind.ai;

/** Per-request authorization, independent of graph scope and read tools. */
public enum AiRequestIntent {
    DISCUSS, DIAGNOSE, BUILD, EDIT, CLARIFY;
    public boolean permitsDraftEdits() { return this == BUILD || this == EDIT; }
}
