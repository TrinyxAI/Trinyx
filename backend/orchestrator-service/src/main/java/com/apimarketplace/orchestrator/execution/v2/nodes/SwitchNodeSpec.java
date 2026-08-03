package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SwitchNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("SWITCH")
            .label("Switch")
            .category("core")
            .variablePrefix("core")
            .description("Evaluates a value against multiple cases")
            .branching(true)
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("selected_branches")
                    // A STRING, despite the plural name, and despite OPTION declaring a key of
                    // the same name as a real array. SwitchNode never emits selected_branches,
                    // so GenericOutputSchemaMapper resolves it through the aliases below, and
                    // both selected_case_label and selected_case are strings. The checked-in
                    // GenericOutputSchemaMapperTest.shouldPersistSwitchContractFields has
                    // asserted assertEquals("Gold", ...) all along; only this declaration was
                    // out of step, and it is what /api/node-definitions and the agent docs read.
                    // Consequence of getting it wrong: an agent writes [0] or size() against it
                    // and resolves nothing, with the run still reporting COMPLETED.
                    .type("string")
                    .description("Label of the case that matched, or the case type when it has no label. Singular despite the plural name; skipped_branches IS an array")
                    // Reachable: SwitchNode always PUTS selected_case but its value is null when
                    // nothing matched and no default case exists, and selected_case_label is only
                    // set when a case object was selected. Both aliases then resolve null and this
                    // default is stored. "" keeps that row the declared type; it used to store [].
                    .defaultValue("")
                    .aliases(List.of("selected_case_label", "selected_case"))
                    .build(),
                OutputFieldDef.builder()
                    .key("selected_case_index")
                    .type("number")
                    .description("Index of the selected case for state reconstruction")
                    .build(),
                OutputFieldDef.builder()
                    .key("evaluations")
                    .type("array")
                    .description("Evaluation details for each case")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("skipped_branches")
                    .type("array")
                    .description("Cases that were not executed")
                    .defaultValue(List.of())
                    .aliases(List.of("skipped_case_labels", "skipped_cases"))
                    .build(),
                // split_item_count must be preserved: ReadyNodeCalculator uses it to detect
                // switch-in-split context and traverse ALL case targets (not just the last
                // item's selected case). Mirrors SplitAwareNodeExecutor injection.
                OutputFieldDef.builder()
                    .key("split_item_count")
                    .type("number")
                    .description("Total number of items when executed inside a split context (used for branch routing)")
                    .build()
            ))
            .keywords(List.of("switch", "case", "match"))
            .build();
    }
}
