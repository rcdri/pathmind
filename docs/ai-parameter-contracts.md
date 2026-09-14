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
Static reporter readback covers matching exported field IDs only; dynamic resolution remains
conservative. In-game checks are still needed for inventory contents, recipes and navigation.
