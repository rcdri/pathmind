package com.pathmind.ai;

/** Application-authored diagnostics, without model reasoning or tool arguments. */
public record AiToolTrace(int turn, String tool, boolean success, String code, String message,
                          int revisionBefore, int revisionAfter) { }
