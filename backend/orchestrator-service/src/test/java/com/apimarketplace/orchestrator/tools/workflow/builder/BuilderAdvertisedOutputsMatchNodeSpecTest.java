package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the fourth surface of the output-name contract.
 *
 * <p>Three surfaces were already covered: the NodeSpec/mapper source, the
 * node_type_documentation rows an agent reads, and GET /api/node-definitions for the
 * inspector. The MCP builder session is the fourth, and it was the one nobody checked:
 * on every load, {@code WorkflowBuilderLoader.rebuildCoreSchema} hands the agent a
 * ready-made {@code {{core:<label>.output.<key>}}} template per core node, and those
 * keys were hardcoded in a switch statement that had drifted from the specs.
 *
 * <p>Four of its seven arms were wrong when this test was written:
 * <ul>
 *   <li>{@code switch} advertised {@code selected_case}</li>
 *   <li>{@code option} shared the decision arm and advertised {@code selected_branch}</li>
 *   <li>{@code transform} advertised {@code result}</li>
 *   <li>{@code wait} advertised {@code completed}</li>
 * </ul>
 * None of those four is a key any mapper writes. The failure is silent by construction:
 * a template naming a key that was never stored resolves to an empty string in mixed
 * text (or null as a bare {@code {{...}}}), the node still succeeds, and the run still
 * reports COMPLETED. There is no error to grep for, which is exactly why it needs a test
 * rather than a review.
 *
 * <p>Aliases deliberately do NOT satisfy this check. An {@code .aliases()} entry is
 * consulted when RESOLVING an input value and never determines the stored key
 * ({@code GenericOutputSchemaMapper} writes {@code field.key()} verbatim), so
 * advertising one is the precise bug being guarded against: {@code selected_case} IS a
 * declared alias of {@code selected_branches}, and it was still unusable as an output
 * reference.
 */
@DisplayName("Builder-advertised output keys match the NodeSpec contracts")
class BuilderAdvertisedOutputsMatchNodeSpecTest {

    /**
     * Builder arm names that share another type's spec. The builder groups them
     * ({@code case "loop", "while"}) because they execute the same node.
     */
    private static final Map<String, String> ARM_TO_SPEC_TYPE = Map.of(
        "while", "LOOP",
        "for_each", "SPLIT"
    );

    /** Every core type the builder's switch statement has an arm for. */
    private static final List<String> BUILDER_ARMS = List.of(
        "decision", "option", "loop", "while", "split", "for_each",
        "switch", "transform", "wait", "http_request"
    );

    @Test
    @DisplayName("every key rebuildCoreSchema advertises is a declared output of the matching NodeSpec")
    void everyAdvertisedKeyIsADeclaredSpecOutput() throws Exception {
        Map<String, Set<String>> declaredKeys = declaredOutputKeysByNodeType();
        Map<String, Set<String>> aliasesOnly = aliasOnlyNamesByNodeType();

        Method rebuild = WorkflowBuilderLoader.class.getDeclaredMethod(
            "rebuildCoreSchema", WorkflowBuilderSession.class, String.class, Map.class);
        rebuild.setAccessible(true);

        WorkflowBuilderLoader loader = uninitializedLoader();
        List<String> problems = new ArrayList<>();

        for (String arm : BUILDER_ARMS) {
            String specType = ARM_TO_SPEC_TYPE.getOrDefault(arm, arm.toUpperCase(Locale.ROOT));
            Set<String> allowed = declaredKeys.get(specType);
            if (allowed == null) {
                problems.add("builder arm '" + arm + "' has no NodeSpec (looked for nodeType " + specType + ")");
                continue;
            }

            WorkflowBuilderSession session = WorkflowBuilderSession.builder().build();
            String nodeId = "core:probe";
            Map<String, Object> core = new LinkedHashMap<>();
            core.put("label", "Probe");
            core.put("type", arm);

            rebuild.invoke(loader, session, nodeId, core);

            WorkflowBuilderSession.NodeSchema schema = session.getNodeSchemas().get(nodeId);
            if (schema == null) {
                continue;   // fork / merge and friends advertise nothing, which is fine
            }

            for (String advertised : schema.getOutputs().keySet()) {
                if (allowed.contains(advertised)) {
                    continue;
                }
                String hint = aliasesOnly.getOrDefault(specType, Set.of()).contains(advertised)
                    ? " (it is an .aliases() entry, which resolves an INPUT and is never the stored key)"
                    : "";
                problems.add(String.format(
                    "arm '%s' advertises '%s'%s; %s declares %s",
                    arm, advertised, hint, specType, new TreeSet<>(allowed)));
            }
        }

        assertTrue(problems.isEmpty(),
            "The MCP builder hands agents templates for keys no mapper writes. "
                + "A template against one of these resolves to empty/null with the run still "
                + "COMPLETED, so nothing surfaces at runtime:\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("selected_case stays out of the builder: it is an alias, not a stored key")
    void selectedCaseIsNeverAdvertised() throws Exception {
        // Pins the specific string that shipped in both WorkflowBuilderLoader and
        // WorkflowBuilderViewer, so a copy-paste cannot quietly bring it back.
        Method rebuild = WorkflowBuilderLoader.class.getDeclaredMethod(
            "rebuildCoreSchema", WorkflowBuilderSession.class, String.class, Map.class);
        rebuild.setAccessible(true);

        WorkflowBuilderSession session = WorkflowBuilderSession.builder().build();
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("label", "Route");
        core.put("type", "switch");
        rebuild.invoke(uninitializedLoader(), session, "core:route", core);

        WorkflowBuilderSession.NodeSchema schema = session.getNodeSchemas().get("core:route");
        assertFalse(schema.getOutputs().containsKey("selected_case"),
            "switch must not advertise selected_case");
        assertTrue(schema.getOutputs().containsKey("selected_branches"),
            "switch should advertise the key the mapper actually writes");
        assertTrue(schema.getReferenceSyntax().get("selected_branches")
                .equals("{{core:route.output.selected_branches}}"),
            "the reference template should name the persisted key");
    }

    /**
     * rebuildCoreSchema touches none of the loader's collaborators, so an instance with
     * null dependencies exercises the real method without a Spring context or a dozen mocks.
     */
    private WorkflowBuilderLoader uninitializedLoader() throws Exception {
        var ctor = WorkflowBuilderLoader.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        return (WorkflowBuilderLoader) ctor.newInstance(args);
    }

    private Map<String, Set<String>> declaredOutputKeysByNodeType() throws Exception {
        Map<String, Set<String>> byType = new TreeMap<>();
        for (NodeSpec spec : scanNodeSpecs()) {
            NodeDefinition def = spec.definition();
            Set<String> keys = new TreeSet<>();
            for (OutputFieldDef field : def.outputs()) {
                keys.add(field.key());
            }
            byType.put(def.nodeType(), keys);
        }
        return byType;
    }

    /** Alias names that are NOT also a declared key, used only to sharpen the failure message. */
    private Map<String, Set<String>> aliasOnlyNamesByNodeType() throws Exception {
        Map<String, Set<String>> byType = new HashMap<>();
        for (NodeSpec spec : scanNodeSpecs()) {
            NodeDefinition def = spec.definition();
            Set<String> keys = new TreeSet<>();
            Set<String> aliases = new TreeSet<>();
            for (OutputFieldDef field : def.outputs()) {
                keys.add(field.key());
                if (field.aliases() != null) {
                    aliases.addAll(field.aliases());
                }
            }
            aliases.removeAll(keys);
            byType.put(def.nodeType(), aliases);
        }
        return byType;
    }

    private List<NodeSpec> scanNodeSpecs() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(NodeSpec.class));

        List<NodeSpec> specs = new ArrayList<>();
        for (var beanDefinition : scanner.findCandidateComponents(
            "com.apimarketplace.orchestrator.execution.v2.nodes")) {
            Class<?> candidateClass = Class.forName(beanDefinition.getBeanClassName());
            if (candidateClass.isInterface() || Modifier.isAbstract(candidateClass.getModifiers())) {
                continue;
            }
            specs.add((NodeSpec) candidateClass.getDeclaredConstructor().newInstance());
        }
        if (specs.isEmpty()) {
            fail("no NodeSpec implementations found; the scan package must have moved");
        }
        return specs;
    }
}
