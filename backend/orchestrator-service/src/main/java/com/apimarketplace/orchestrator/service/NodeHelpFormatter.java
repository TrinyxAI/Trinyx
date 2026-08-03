package com.apimarketplace.orchestrator.service;

import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formats node documentation for LLM help responses.
 * Optimized for concise, actionable information to minimize token usage.
 */
@Slf4j
@Service
public class NodeHelpFormatter {

    private final ModelCatalogEnricher modelCatalogEnricher;

    public NodeHelpFormatter(ModelCatalogEnricher modelCatalogEnricher) {
        this.modelCatalogEnricher = modelCatalogEnricher;
    }

    /**
     * Format a single node's help for LLM consumption.
     * Returns a concise map with only essential information.
     *
     * <p>For {@code classify} and {@code guardrail} nodes, the {@code params.provider.enum},
     * {@code params.provider.default} and {@code params.model.default} keys are rewritten
     * from the live model catalog via {@link ModelCatalogEnricher} - the same source of
     * truth used by the validator, so help and validation agree at the same instant.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> formatNodeHelp(NodeTypeDocumentationEntity node) {
        Map<String, Object> helpMap = node.toHelpMap();
        Object paramsRaw = helpMap.get("params");
        if (paramsRaw instanceof Map) {
            Map<String, Object> enriched = modelCatalogEnricher.enrichIfLlm(
                node.getType(), (Map<String, Object>) paramsRaw);
            if (enriched != paramsRaw) {
                helpMap.put("params", enriched);
            }
        }
        return helpMap;
    }

    /**
     * Format a brief summary of a node (type, description, key params).
     */
    public Map<String, Object> formatNodeSummary(NodeTypeDocumentationEntity node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", node.getType());
        result.put("description", node.getDescription());

        // Include key parameters only
        if (node.getParameters() != null && !node.getParameters().isEmpty()) {
            List<String> requiredParams = getRequiredParamNames(node.getParameters());
            if (!requiredParams.isEmpty()) {
                result.put("required", requiredParams);
            }
        }

        // Include edge ports if this is a branching node
        if (node.getEdgePorts() != null && !node.getEdgePorts().isEmpty()) {
            result.put("ports", node.getEdgePorts().get("ports"));
        }

        return result;
    }

    /**
     * Extract required parameter names from parameters schema.
     */
    @SuppressWarnings("unchecked")
    private List<String> getRequiredParamNames(Map<String, Object> parameters) {
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            if (entry.getValue() instanceof Map) {
                Map<String, Object> paramSchema = (Map<String, Object>) entry.getValue();
                if (Boolean.TRUE.equals(paramSchema.get("required"))) {
                    required.add(entry.getKey());
                }
            }
        }
        return required;
    }
}
