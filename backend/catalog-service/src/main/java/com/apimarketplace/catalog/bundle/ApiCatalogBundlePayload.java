package com.apimarketplace.catalog.bundle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Canonical JSON payload of an API-catalog bundle (cloud → CE distribution).
 *
 * <p><b>Canonicalisation rules</b> (must stay stable - CE verifies the exact
 * same bytes the cloud signed; mirrors {@code agent-service CatalogBundlePayload}):
 * <ul>
 *   <li>Map keys sorted alphabetically at every level
 *       ({@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS} + TreeMap rows).</li>
 *   <li>APIs/tools/parameters/responses sorted by UUID string; tool-credential
 *       links and credential templates by (credentialName, variant) - so input
 *       row order never changes the bytes.</li>
 *   <li>No pretty-printing. Unicode not escaped. UTF-8 bytes.</li>
 *   <li>JSONB columns ({@code execution_spec}, {@code properties}, …) ship as
 *       their RAW database string - never re-parsed/re-serialised, which keeps
 *       byte-determinism trivially true and round-trips exotic formatting.</li>
 *   <li>{@code null} fields omitted entirely.</li>
 * </ul>
 *
 * <p><b>Gzip:</b> unlike the LLM model bundle (a few hundred small rows, served
 * uncompressed), the full API catalog is megabytes of JSON. The canonical bytes
 * are therefore gzipped via {@link #gzip(byte[])} and the cloud signs the
 * GZIP bytes (which it also persists in {@code api_catalog_bundles.payload_gz}
 * so {@code /latest} serves straight from the DB - no re-snapshot, no checksum
 * drift when the live table changes after a build).
 *
 * <p><b>Category linking:</b> {@code category_id}/{@code subcategory_id} are
 * NOT NULL FKs whose UUIDs differ between installs, so the payload carries
 * {@code category{name,slug}} / {@code subcategory{name,slug}} instead. CE
 * resolves by slug (then name) and creates missing rows - see
 * {@code ApiCatalogMergeService}.
 */
public final class ApiCatalogBundlePayload {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, true);

    private ApiCatalogBundlePayload() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Snapshot row model (built by ApiCatalogSnapshotReader on the cloud side)
    // ─────────────────────────────────────────────────────────────────────────

    /** {@code catalog.api_tool_parameters} row. */
    public record ParameterRow(
            UUID id, String parameterType, String name, String dataType, Boolean isRequired,
            String description, String exampleValue, String defaultValue, String allowedValues,
            String filePath, String extras, Boolean isHidden) {}

    /** {@code catalog.tool_responses} row. JSONB columns are raw strings. */
    public record ResponseRow(
            UUID id, String name, String description, String schemaJson, String example,
            String exampleJsonb, String structureSkeleton, Integer statusCode, Boolean isDefault,
            String format, Boolean isActive) {}

    /** {@code catalog.tool_credentials} link row (re-linked on CE by name+variant). */
    public record ToolCredentialRow(
            String credentialName, String variant, Boolean isRequired, String usage,
            String conditionJson, String metadataJson) {}

    /** {@code catalog.api_tools} row plus children. */
    public record ToolRow(
            UUID id, String toolSlug, String description, String toolNameId, String method,
            String endpoint, String protocol, String defaultHeaders, String runtimeMetadata,
            String executionSpec, String outputSchema, String executionMode, String pagination,
            String requiredScopes,
            // The generation descriptor. Carried like requiredScopes above: it is
            // what makes an endpoint visible to the generation surface, and what
            // ApiToolEntity.isGeneration() reads, so an install that receives the
            // catalog through this bundle and not through the importer would
            // otherwise have every generation invisible and every fail-closed
            // billing guard that asks "is this a generation" answering no.
            String generationSpec,
            String nextHint, String status, String testStatus,
            Boolean isActive, String version,
            List<ParameterRow> parameters, List<ResponseRow> responses,
            List<ToolCredentialRow> toolCredentials) {}

    /** {@code catalog.apis} row plus children; categories resolved to name+slug. */
    public record ApiRow(
            UUID id, String apiName, String apiSlug, String description, String baseUrl,
            String healthcheckEndpoint, String categoryName, String categorySlug,
            String subcategoryName, String subcategorySlug, String authType,
            String authHeaderName, String authHeaderValue, String visibility, Boolean isPublic,
            Boolean isActive, Boolean isLocal, String pricingModel, String status, String version,
            String iconSlug, String platformCredentialName, String iconUrl, String apiVersion,
            String documentation, String rateLimits, List<ToolRow> tools) {}

    /** {@code catalog.credentials} template row. Unique key is (credentialName, variant). */
    public record CredentialTemplateRow(
            String credentialName, String variant, String displayName, String description,
            String credentialType, String authType, String testEndpoint, String documentationUrl,
            String iconUrl, String iconSlug, String propertiesJson, String extendsJson,
            String metadataJson) {}

    /**
     * One published generation price (V430), read from {@code auth.pricing_version_entry}
     * through auth-service (catalog never touches the {@code auth} schema).
     *
     * <p><b>Why the price rides the same bundle as the descriptor.</b> The
     * descriptor already travels here, and a descriptor without a price is a
     * model an install can see and cannot sell: an unpriced generation is
     * refused by design ({@code CatalogToolBillingService.preflightReserve}), so
     * an install fed only by this bundle receives the whole generation catalogue
     * and can run none of it. Putting the two in one signed blob also makes them
     * impossible to separate: the price is covered by the same Ed25519 signature
     * over the same gzip bytes, so an install cannot keep the descriptor and
     * substitute its own rate.
     *
     * <p><b>Keys are the portable ones.</b> {@code integrationName} rather than
     * the platform credential's serial id (install-local), {@code apiToolId}
     * because catalog tool UUIDs are stable by seed contract, {@code modelId}
     * because one endpoint can back several models at different rates.
     *
     * <p>Amounts are PLAIN STRINGS. The bytes are signed, so a rate must not
     * round-trip through a double, and {@code "60"} versus {@code "60.000000"}
     * must not depend on how a driver happened to scale a DECIMAL.
     */
    public record GenerationPriceRow(
            String integrationName, String apiToolId, String modelId, String priceUnit,
            String baseCredits, String unitCredits, String minCredits, String maxCredits) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Canonical serialisation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Serialise a catalog snapshot into the canonical (UNCOMPRESSED) payload
     * bytes. Two calls with the same logical inputs - regardless of list order -
     * produce byte-identical output; the signer depends on this.
     */
    public static byte[] canonicalBytes(long version, int schemaVersion, String issuer,
                                        Instant snapshotTakenAt,
                                        List<ApiRow> apis,
                                        List<CredentialTemplateRow> credentialTemplates) {
        return canonicalBytes(version, schemaVersion, issuer, snapshotTakenAt, apis,
                credentialTemplates, List.of());
    }

    /**
     * V430 form, additionally carrying the published generation prices.
     *
     * <p><b>The key is omitted entirely when there are no prices</b>, so this
     * produces byte-identical output to the overload above for a catalog with no
     * priced generation. That is what makes the addition invisible to a cloud
     * that has nothing to price, and it is why {@code schemaVersion} does NOT
     * move: nothing about an older payload became invalid, and an older CE reads
     * the root as a map and simply never asks for a key it does not know.
     */
    public static byte[] canonicalBytes(long version, int schemaVersion, String issuer,
                                        Instant snapshotTakenAt,
                                        List<ApiRow> apis,
                                        List<CredentialTemplateRow> credentialTemplates,
                                        List<GenerationPriceRow> generationPrices) {
        if (issuer == null || snapshotTakenAt == null || apis == null) {
            throw new IllegalArgumentException(
                    "canonicalBytes requires non-null issuer, snapshotTakenAt, apis");
        }
        if (credentialTemplates == null) credentialTemplates = List.of();

        Map<String, Object> root = new TreeMap<>();
        root.put("version", version);
        root.put("schemaVersion", schemaVersion);
        root.put("issuer", issuer);
        root.put("snapshotAt", snapshotTakenAt.toString());

        List<ApiRow> sortedApis = new ArrayList<>(apis);
        sortedApis.sort(Comparator.comparing(a -> String.valueOf(a.id())));
        List<Map<String, Object>> apiJson = new ArrayList<>(sortedApis.size());
        for (ApiRow api : sortedApis) {
            apiJson.add(apiMap(api));
        }
        root.put("apis", apiJson);

        List<CredentialTemplateRow> sortedTemplates = new ArrayList<>(credentialTemplates);
        sortedTemplates.sort(Comparator
                .comparing(CredentialTemplateRow::credentialName, Comparator.nullsLast(String::compareTo))
                .thenComparing(CredentialTemplateRow::variant, Comparator.nullsLast(String::compareTo)));
        List<Map<String, Object>> templateJson = new ArrayList<>(sortedTemplates.size());
        for (CredentialTemplateRow t : sortedTemplates) {
            templateJson.add(templateMap(t));
        }
        root.put("credentialTemplates", templateJson);

        if (generationPrices != null && !generationPrices.isEmpty()) {
            List<GenerationPriceRow> sortedPrices = new ArrayList<>(generationPrices);
            sortedPrices.sort(Comparator
                    .comparing(GenerationPriceRow::integrationName, Comparator.nullsLast(String::compareTo))
                    .thenComparing(GenerationPriceRow::apiToolId, Comparator.nullsLast(String::compareTo))
                    .thenComparing(p -> p.modelId() == null ? "" : p.modelId()));
            List<Map<String, Object>> priceJson = new ArrayList<>(sortedPrices.size());
            for (GenerationPriceRow p : sortedPrices) {
                priceJson.add(generationPriceMap(p));
            }
            root.put("generationPrices", priceJson);
        }

        try {
            return CANONICAL_MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise canonical API catalog payload", e);
        }
    }

    private static Map<String, Object> apiMap(ApiRow api) {
        Map<String, Object> row = new TreeMap<>();
        row.put("id", String.valueOf(api.id()));
        row.put("apiName", api.apiName());
        putIfNotNull(row, "apiSlug", api.apiSlug());
        putIfNotNull(row, "description", api.description());
        putIfNotNull(row, "baseUrl", api.baseUrl());
        putIfNotNull(row, "healthcheckEndpoint", api.healthcheckEndpoint());
        // Categories ship as name+slug (FK UUIDs are install-local).
        if (api.categoryName() != null || api.categorySlug() != null) {
            Map<String, Object> cat = new TreeMap<>();
            putIfNotNull(cat, "name", api.categoryName());
            putIfNotNull(cat, "slug", api.categorySlug());
            row.put("category", cat);
        }
        if (api.subcategoryName() != null || api.subcategorySlug() != null) {
            Map<String, Object> sub = new TreeMap<>();
            putIfNotNull(sub, "name", api.subcategoryName());
            putIfNotNull(sub, "slug", api.subcategorySlug());
            row.put("subcategory", sub);
        }
        putIfNotNull(row, "authType", api.authType());
        putIfNotNull(row, "authHeaderName", api.authHeaderName());
        putIfNotNull(row, "authHeaderValue", api.authHeaderValue());
        putIfNotNull(row, "visibility", api.visibility());
        putIfNotNull(row, "isPublic", api.isPublic());
        putIfNotNull(row, "isActive", api.isActive());
        putIfNotNull(row, "isLocal", api.isLocal());
        putIfNotNull(row, "pricingModel", api.pricingModel());
        putIfNotNull(row, "status", api.status());
        putIfNotNull(row, "version", api.version());
        putIfNotNull(row, "iconSlug", api.iconSlug());
        putIfNotNull(row, "platformCredentialName", api.platformCredentialName());
        putIfNotNull(row, "iconUrl", api.iconUrl());
        putIfNotNull(row, "apiVersion", api.apiVersion());
        putIfNotNull(row, "documentation", api.documentation());
        putIfNotNull(row, "rateLimits", api.rateLimits());

        List<ToolRow> tools = api.tools() == null ? List.of() : api.tools();
        List<ToolRow> sorted = new ArrayList<>(tools);
        sorted.sort(Comparator.comparing(t -> String.valueOf(t.id())));
        List<Map<String, Object>> toolJson = new ArrayList<>(sorted.size());
        for (ToolRow tool : sorted) {
            toolJson.add(toolMap(tool));
        }
        row.put("tools", toolJson);
        return row;
    }

    private static Map<String, Object> toolMap(ToolRow tool) {
        Map<String, Object> row = new TreeMap<>();
        row.put("id", String.valueOf(tool.id()));
        putIfNotNull(row, "toolSlug", tool.toolSlug());
        putIfNotNull(row, "description", tool.description());
        putIfNotNull(row, "toolNameId", tool.toolNameId());
        putIfNotNull(row, "method", tool.method());
        putIfNotNull(row, "endpoint", tool.endpoint());
        putIfNotNull(row, "protocol", tool.protocol());
        putIfNotNull(row, "defaultHeaders", tool.defaultHeaders());
        putIfNotNull(row, "runtimeMetadata", tool.runtimeMetadata());
        putIfNotNull(row, "executionSpec", tool.executionSpec());
        putIfNotNull(row, "outputSchema", tool.outputSchema());
        putIfNotNull(row, "executionMode", tool.executionMode());
        putIfNotNull(row, "pagination", tool.pagination());
        putIfNotNull(row, "requiredScopes", tool.requiredScopes());
        putIfNotNull(row, "generationSpec", tool.generationSpec());
        putIfNotNull(row, "nextHint", tool.nextHint());
        putIfNotNull(row, "status", tool.status());
        putIfNotNull(row, "testStatus", tool.testStatus());
        putIfNotNull(row, "isActive", tool.isActive());
        putIfNotNull(row, "version", tool.version());

        List<ParameterRow> params = tool.parameters() == null ? List.of() : tool.parameters();
        List<ParameterRow> sortedParams = new ArrayList<>(params);
        sortedParams.sort(Comparator.comparing(p -> String.valueOf(p.id())));
        List<Map<String, Object>> paramJson = new ArrayList<>(sortedParams.size());
        for (ParameterRow p : sortedParams) {
            paramJson.add(parameterMap(p));
        }
        row.put("parameters", paramJson);

        List<ResponseRow> responses = tool.responses() == null ? List.of() : tool.responses();
        List<ResponseRow> sortedResponses = new ArrayList<>(responses);
        sortedResponses.sort(Comparator.comparing(r -> String.valueOf(r.id())));
        List<Map<String, Object>> respJson = new ArrayList<>(sortedResponses.size());
        for (ResponseRow r : sortedResponses) {
            respJson.add(responseMap(r));
        }
        row.put("responses", respJson);

        List<ToolCredentialRow> creds = tool.toolCredentials() == null ? List.of() : tool.toolCredentials();
        List<ToolCredentialRow> sortedCreds = new ArrayList<>(creds);
        sortedCreds.sort(Comparator
                .comparing(ToolCredentialRow::credentialName, Comparator.nullsLast(String::compareTo))
                .thenComparing(ToolCredentialRow::variant, Comparator.nullsLast(String::compareTo)));
        List<Map<String, Object>> credJson = new ArrayList<>(sortedCreds.size());
        for (ToolCredentialRow c : sortedCreds) {
            credJson.add(toolCredentialMap(c));
        }
        row.put("toolCredentials", credJson);
        return row;
    }

    private static Map<String, Object> parameterMap(ParameterRow p) {
        Map<String, Object> row = new TreeMap<>();
        row.put("id", String.valueOf(p.id()));
        putIfNotNull(row, "parameterType", p.parameterType());
        putIfNotNull(row, "name", p.name());
        putIfNotNull(row, "dataType", p.dataType());
        putIfNotNull(row, "isRequired", p.isRequired());
        putIfNotNull(row, "description", p.description());
        putIfNotNull(row, "exampleValue", p.exampleValue());
        putIfNotNull(row, "defaultValue", p.defaultValue());
        putIfNotNull(row, "allowedValues", p.allowedValues());
        putIfNotNull(row, "filePath", p.filePath());
        putIfNotNull(row, "extras", p.extras());
        putIfNotNull(row, "isHidden", p.isHidden());
        return row;
    }

    private static Map<String, Object> responseMap(ResponseRow r) {
        Map<String, Object> row = new TreeMap<>();
        row.put("id", String.valueOf(r.id()));
        putIfNotNull(row, "name", r.name());
        putIfNotNull(row, "description", r.description());
        putIfNotNull(row, "schema", r.schemaJson());
        putIfNotNull(row, "example", r.example());
        putIfNotNull(row, "exampleJsonb", r.exampleJsonb());
        putIfNotNull(row, "structureSkeleton", r.structureSkeleton());
        putIfNotNull(row, "statusCode", r.statusCode());
        putIfNotNull(row, "isDefault", r.isDefault());
        putIfNotNull(row, "format", r.format());
        putIfNotNull(row, "isActive", r.isActive());
        return row;
    }

    private static Map<String, Object> toolCredentialMap(ToolCredentialRow c) {
        Map<String, Object> row = new TreeMap<>();
        putIfNotNull(row, "credentialName", c.credentialName());
        putIfNotNull(row, "variant", c.variant());
        putIfNotNull(row, "isRequired", c.isRequired());
        putIfNotNull(row, "usage", c.usage());
        putIfNotNull(row, "condition", c.conditionJson());
        putIfNotNull(row, "metadata", c.metadataJson());
        return row;
    }

    private static Map<String, Object> templateMap(CredentialTemplateRow t) {
        Map<String, Object> row = new TreeMap<>();
        putIfNotNull(row, "credentialName", t.credentialName());
        putIfNotNull(row, "variant", t.variant());
        putIfNotNull(row, "displayName", t.displayName());
        putIfNotNull(row, "description", t.description());
        putIfNotNull(row, "credentialType", t.credentialType());
        putIfNotNull(row, "authType", t.authType());
        putIfNotNull(row, "testEndpoint", t.testEndpoint());
        putIfNotNull(row, "documentationUrl", t.documentationUrl());
        putIfNotNull(row, "iconUrl", t.iconUrl());
        putIfNotNull(row, "iconSlug", t.iconSlug());
        putIfNotNull(row, "properties", t.propertiesJson());
        putIfNotNull(row, "extends", t.extendsJson());
        putIfNotNull(row, "metadata", t.metadataJson());
        return row;
    }

    private static Map<String, Object> generationPriceMap(GenerationPriceRow p) {
        Map<String, Object> row = new TreeMap<>();
        putIfNotNull(row, "integrationName", p.integrationName());
        putIfNotNull(row, "apiToolId", p.apiToolId());
        putIfNotNull(row, "modelId", p.modelId());
        putIfNotNull(row, "priceUnit", p.priceUnit());
        putIfNotNull(row, "baseCredits", p.baseCredits());
        putIfNotNull(row, "unitCredits", p.unitCredits());
        putIfNotNull(row, "minCredits", p.minCredits());
        putIfNotNull(row, "maxCredits", p.maxCredits());
        return row;
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gzip helpers - the cloud signs gzip(canonicalBytes); CE gunzips after
    // signature verification. Determinism across JVMs is NOT required: the
    // cloud persists the exact gzipped bytes it signed (payload_gz) and always
    // serves those, never a re-compression.
    // ─────────────────────────────────────────────────────────────────────────

    public static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to gzip catalog bundle payload", e);
        }
        return bos.toByteArray();
    }

    public static byte[] gunzip(byte[] gzipped) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return gz.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to gunzip catalog bundle payload", e);
        }
    }
}
