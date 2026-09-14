# Request intent, scope, and completion

Request intent is separate from graph scope and tools. `discuss`, `diagnose`, and
`clarify` do not authorize draft edits. `build` authorizes an isolated new draft;
`edit` authorizes an isolated draft of the open preset. Nothing authorizes applying
or executing a preset without user confirmation.

The model judges intent semantically from the latest user request, with an exact
`intentEvidence` quote checked against that request. This is not keyword routing or
a prompt-to-preset template. Evidence matching anchors the decision to the current
request; it does not prove the model's semantic interpretation is correct. Live evals
remain necessary. Historical decisions never authorize a later request.

Use `assess_request`, or supply `requestIntent` and `intentEvidence` on the first useful
tool call. Nullable intent fields on later calls preserve the assessment. Read tools
can run before assessment. `target:inspect` and `target:undecided` are neutral, not
read-only permissions. `inspect_preset` never locks a request into a mode.

Choose `current` for edit or `new` for build before `plan_graph`. Scope may be corrected
before the first successful patch. Changing scope invalidates the earlier plan. After
the first successful patch, scope is frozen so later responses cannot discard the
draft inadvertently. Read-only intent cannot escalate to editing inside that request;
ask clarification instead. Genuine ambiguity should not be resolved by silent edits.
Current-preset editing also requires reading the preset or relevant subgraph first.

Completion outcomes:

- `ANSWER`: ordinary discussion, or diagnosis after inspecting the open preset.
- `PROPOSAL`: actual draft edits, validated after the last mutation, with execution
  preview and review. It has not been applied.
- `CLARIFICATION`: a specific missing-information reason and a user-facing question.
- `BLOCKED`: a specific explanation supported by `blockingToolTurn`, referencing a
  real prior failed context/capability/command/validation result. Permission selection
  errors and an inspect target do not establish inability to edit.

An edit/build cannot finish `complete` with advice or an unchanged, unedited draft.
Use `finish` with `completion:clarification|blocked` when appropriate, and provide
`completionReason`. Null `completion` means `complete`. Non-proposal outcomes carry no
graph; the legacy response target `inspect` remains for compatibility, but the UI uses
the explicit completion outcome. A completed conversation response is not necessarily
a fulfilled construction request.

Work logs are application-authored summaries of successful tool results. Model-supplied
work logs are ignored. `AiRunReport.trace()` retains success/error codes, tool names,
turns, bounded result messages, and revision transitions for failed and successful runs.
`AiRequestDiagnostics.recent()` exposes the last 20 local runs in memory, including
assessed intent and final outcome. No raw arguments, graph payloads, credentials, or
reasoning blocks are retained in these traces. Result messages can contain node names
or user-chosen parameter labels, so traces should be treated as local diagnostic data.
Live eval reports export these traces only when the benchmark is explicitly run.

The lifecycle regression using the reported position/walk/pathfinding prompt checks
intent/scope recovery with a minimal real patch. It does not claim to test the full
world-dependent requested behavior. Behavioral and in-game evals belong to the later
verification pass.

## Chat and request progress

Chat preserves paragraphs, lists, and complete responses instead of the former
280/120-character excerpts. Responses have a 64,000-character safety limit and
individual technical details a 16,000-character limit; oversized responses are
rejected explicitly rather than saved partially. Oversized diagnostic messages
include an explicit omission notice. Previously truncated messages cannot be restored.
Agent replies normally must fit within 600 characters and 90 words; an explicit request
for detail raises that budget to 4,000 characters and 650 words. If a model exceeds the
applicable budget, the tool result asks it to retry instead of silently cutting the text.
Technical tool results are grouped behind a Details toggle, including failed tools.

Scrolling keeps a message/character anchor across resizing, new messages, and detail
expansion. At the end it follows new output; while reading older messages it stays
put. The scrollbar and Latest control allow navigation back to recent output.

Application-authored progress identifies inspection, planning, building, validation,
repair, and awaiting review. The composer action becomes Stop during requests.
Stopping, switching providers, resetting chat, or leaving the editor closes the
request session and prevents late callbacks from presenting a stale proposal.
Merely hiding the popup does not cancel work. HTTP cancellation is best effort;
it does not guarantee that a provider stops already received work or refunds usage.
No provider background mode or additional server-side conversation storage is enabled.

## Workspace context and continuity

The archive remains complete and local. The request context has four distinct layers:

- Recent saved conversation, within a shared 24,000-character conversation budget.
- Fresh workspace facts: active preset name/fingerprint, node/routine counts, and up
  to 64 deterministic selected node IDs. IDs absent from the current graph are removed.
  Additional selected nodes are counted explicitly. Actual graph details come from
  inspection tools against the same request-time snapshot, not historical chat.
- A compact, AI-authored conversational summary: goal, confirmed user decisions,
  unresolved work. `finish` refreshes it without an extra provider request. A successful,
  non-cancelled request saves it; an omitted goal retains the existing goal while still
  accepting updated decisions and unresolved work. It is
  advisory and visible, never a graph snapshot or edit authorization. Older archives
  start without summaries; this cannot recover information that was never saved or
  no longer reaches the model. Summarization quality still needs live evaluation.
- Explicit, user-edited preferences, shared across providers. The AI cannot write
  preferences. They are defaults subordinate to the latest request, not permission.

Summaries and recent lifecycle receipts are provider-isolated. Application-authored
PROPOSED/APPLIED/DISCARDED receipts retain preset identity, fingerprint, timestamp,
and compact accepted graph changes, separate from assistant claims. Receipt history
is not proof of the current graph; the fresh fingerprint and inspection are authoritative.
Older pending-review claims must yield to later application/discard receipts.

Open AI settings → Context & preferences to inspect/clear the summary and edit
preferences in the multiline composer. Press its arrow (or Enter) to save; Shift+Enter
adds a line. Back leaves without saving and restores the unsent chat draft. Resetting
chat clears that provider's history, summary, and receipts; explicit preferences survive
and can be removed by saving an empty field. Neither chat nor notes are encrypted;
the API key is kept in the existing separate secret store. Nothing new is saved at
the provider. Summary and preference safety limits reject oversized data explicitly.
