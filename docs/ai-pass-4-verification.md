# Pass 4: evidence and remaining checks

## Offline evidence

Tests use real graph commands, conversion/validation, behavior grading, intent/scope
guards, history persistence, callback generations, cancellation, and chat layout.
Providers are scripted. Passing these tests demonstrates application safeguards and
graders, **not** model tool selection quality or real Minecraft movement.

The exact reported prompt is reproduced from a stable Start → south-facing five-second
Walk → Jump fixture. A scripted agent inspects, recovers from a premature inspect-mode
finish, plans, adds a saved Self XYZ/five-block forward walk/pathfinding return, validates,
and produces a concise proposal without changing the source fixture.
Valid-but-wrong seconds, return-variable, and capture-order graphs fail behavior grading.
Other tests cover discussion vs implementation, clarification, cancelled/late responses,
reset/provider-switch callback rejection, discarded/unaccepted proposals, long messages,
and recent chat subordinate to fresh graph state.

```sh
./gradlew test
./gradlew :common:aiEval -PaiEvalDifficulty=regression -PaiEvalLimit=7
```

JUnit results are in `common/build/reports/tests/test/index.html` and
`common/build/test-results/test/TEST-com.pathmind.ai.*.xml`. Offline aiEval checks
selection only; it deliberately does not report a model pass rate.

## Live benchmark: not run

No benchmark API key was available in the environment. The runner never reads the
player's saved key. Supply the appropriate environment variable through secure setup
and choose a model explicitly; don't paste keys in chat. Live calls incur charges.

```sh
./gradlew :common:aiEval -PaiEvalLive=true -PaiEvalProvider=OPENAI \
  -PaiEvalModel=YOUR_MODEL -PaiEvalDifficulty=regression \
  -PaiEvalLimit=7 -PaiEvalRepeats=1
```

Start small, then repeat with `-PaiEvalRepeats=3` across matched providers/models.
Use `-PaiEvalCases=lifecycle-position-return` for only the reported workflow.
Reports record case/repetition, structural failures, trace/turns/repairs, latency,
token usage, and optional estimated cost. Missing usage/pricing is unknown, not zero.
Reports identify their evidence as live-provider structural grading, not world execution.
Open-ended response-concept checks are lexical proxies and still need manual review.
Review failures before changing expectations. No measured provider quality is claimed yet.

## In-game checks: not run

Minecraft was absent from the running-app inventory. Use the new build in a disposable
local flat world with an unobstructed route and pathfinding available. Do not execute
benchmark graphs in an important world/server.

1. Recreate the original Walk (south, five seconds) → Jump fixture. Send the exact
   `lifecycle-position-return` prompt. Require a reviewable proposal, no claims that
   inspect mode prevents editing, and no changes before Apply.
2. Review capture after Jump, Distance=5 rather than Duration, and GOTO reading the
   same saved XYZ variable. Apply only after reviewing this result.
3. Record saved XYZ after Jump, maximum displacement during the new Walk, and final
   XYZ. Verify approximately five blocks forward and return near the saved position
   using an explicit tolerance (e.g. 0.5 blocks). Record obstacles, tick rate, and
   whether any blocks were broken/placed. This checks actual behavior, not graph validity.
4. Ask for discussion only; require no graph proposal. Select nodes and say “change
   that part”; require a proposal focused on the fresh selection, or clarification
   when the intended change is ambiguous.
5. Discard a proposal, then inspect what's present; discarded changes must not be
   described as applied. Manually edit while a request runs; stale Apply must fail.
6. Stop/reset/switch providers during requests. No later progress, response, or proposal
   may resurrect that request.
7. Request a long multiline explanation. Resize/scroll, expand Details, and use Latest.
   Require complete text and stable reading position. Reopen editor/game and verify
   chat-history persistence and the stated reset behavior.

Record build, Minecraft version, provider/model, case/repetition, observations, and
failures. Mark live/world checks passed only after observing them. Model and world
reliability remain **unmeasured** until then, regardless of a green build.
