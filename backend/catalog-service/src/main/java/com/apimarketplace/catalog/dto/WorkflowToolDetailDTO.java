package com.apimarketplace.catalog.dto;

import java.util.List;

/**
 * DTO optimisé pour les détails complets d'un tool dans le workflow inspector
 * Contient les paramètres, réponses et credentials
 */
public record WorkflowToolDetailDTO(
    String slug,
    String name,
    String description,
    String method,
    String apiSlug,
    String iconSlug,
    String iconUrl,
    List<WorkflowParameterDTO> parameters,
    List<WorkflowToolResponseDTO> responses,
    List<WorkflowToolCredentialDTO> credentials,
    /**
     * Stable api_tool UUID. The workflow inspector reads this to resolve
     * per-endpoint pricing for legacy nodes that did not persist
     * {@code toolData.apiToolId} when they were created.
     */
    String toolId,
    /**
     * V166: per-endpoint OAuth scope requirements. Null when no requirement
     * (95% of catalog). Read by McpToolSelector → CredentialSection →
     * MissingScopesBanner to warn when the bound credential lacks scopes.
     */
    List<String> requiredScopes,
    /**
     * V166: unique-per-API integration name (matches auth.credentials.integration).
     * Required because iconSlug is brand-shared (e.g. googlecloud covers GCS,
     * Translate, Vision...) and cannot disambiguate the user's credential.
     */
    String integrationName,
    /**
     * True when this endpoint carries a generation descriptor, i.e. every call
     * produces a paid asset on the platform owner's provider key.
     *
     * <p>Carried so the inspector can state it when it asks auth-service for a
     * price. That service decides whether the credential-wide default may be
     * quoted, refuses it for a generation exactly as the billing path does, and
     * cannot read {@code catalog.api_tools} to find out on its own. Without it
     * an {@code mcp:} step bound to a generation endpoint (which names no
     * model) was quoted the catch-all default and shown as sellable, while
     * execution refused that very call.
     */
    boolean generation
) {}

