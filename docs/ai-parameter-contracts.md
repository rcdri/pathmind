# Typed node configuration

Parameter domain metadata belongs to `NodeParameterDefinition` in the node catalog.
`ParameterValueContract` supplements the existing persisted primitive type with a format,
minimum and unit. It does not change preset serialization or introduce prompt-to-node templates.
The AI adapter, final proposal validation and contract documentation consume the same metadata.

`configure_node(ref, mode, parameterValues)` applies mode and parameter changes in an atomic
draft batch. Partial updates are supported. Compatible parameter IDs/types retain their values
when changing modes; newly introduced fields use catalog defaults. Numeric/boolean types are
checked generically. Domain annotations currently cover identifier/quantity fields used by
crafting, item reporters, walking, distance and range. Other strings retain their existing meaning;
they are not guessed to be identifiers based on their names.

Final validation checks recorded plan requirements and configured parameters, including routine
graphs. Requirements freeze after the first successful edit. Readback and review distinguish
literal fields from values supplied by catalog-declared literal reporters. Expressions, variables,
sensors and other runtime-dependent inputs are not executed or claimed to be statically verified.

Limits: resource identifier validation checks syntax, not live registry membership or recipe
availability. Declared requirements do not prove that every user requirement was extracted.
Static reporter readback uses the runtime value exporter and slot mapping through
`NodeParameterSemantics`; repeat remapping and slot filters are not duplicated in the AI layer.
Dynamic uncertainty is tracked by the input's catalog traits and possible mapped exports, with
a conservative fallback when the output footprint is unknown. Unknown requirements are warnings,
not incorrect-value errors. Shadowed host literals are not execution inputs and are not validated
as though they execute. In-game checks are still needed for inventory contents, recipes and navigation.

Native and fallback providers share behavioral instructions. A clarification must contain an
actual question; repairable validation errors cannot establish a genuine blocker. Blocked output
is application-authored from the confirmed context/capability failure and never offers a dropped
draft for review.

## Scoped edits, verification and lifecycle

Commands and focused queries accept `graphRef`: null/root selects the root graph; a routine ID or
alias selects its body. Existing body IDs/aliases survive extraction. Connections cannot cross
scopes. Routine definitions are created in root; body edits can add calls to root definitions.
`bind_node_ref` names an inspected existing node without changing the graph or granting permission.
Parameter requirements can select a routine scope. Frozen `structuralRequirements` check explicit
node types, flow edges and action/sensor/parameter attachments. These verify recorded relationships,
not completeness of natural-language understanding or world execution. Routine-only changes are
included in proposal review.

Progress tracking ignores reply wording and detects repeated information across alternating tool
cycles. No-op patches do not advance the draft revision. Detail-history bursts are saved on a
background scheduler; user/assistant/lifecycle messages flush immediately and disposal flushes
remaining details. Reset cannot restore queued old details.

Preset writes serialize to a sibling temporary file before atomic replacement when supported.
Failed apply/save attempts restore the previous editor snapshot; restoration failure is reported
explicitly instead of claiming no editor changes occurred.
