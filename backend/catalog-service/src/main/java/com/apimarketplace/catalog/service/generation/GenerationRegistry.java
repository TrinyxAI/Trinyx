package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves a public generation model id (e.g. {@code seedance-2.0-fast}) to
 * everything needed to call and bill it.
 *
 * <p>This is the piece that lets ONE tool and ONE workflow node cover every
 * format. The surfaces speak model ids; the registry turns a model id into the
 * catalog endpoint that executes it, the provider that owns the credential, and
 * the descriptor that says how to shape the request and where the asset lands.
 * Adding a provider therefore touches no surface at all.
 *
 * <p><b>Source of truth</b> - {@code catalog.api_tools.generation_spec}, seeded
 * from {@code scripts/api-migrations}. There is deliberately no second registry
 * to keep in sync: an admin toggling a model in the platform changes its
 * availability and its price, never its existence.
 *
 * <p><b>Caching</b> - the descriptor set changes only on a catalog import, so
 * it is read in one query and held for {@link #CACHE_TTL}. That keeps the
 * per-call resolution a map lookup instead of a database round trip on the hot
 * path of every generation.
 */
@Slf4j
@Service
public class GenerationRegistry {

    /** Short enough that a re-import is picked up without a restart. */
    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final ApiToolRepository apiToolRepository;
    private final ApiRepository apiRepository;
    private final ObjectMapper objectMapper;

    private final AtomicReference<Snapshot> cache = new AtomicReference<>(null);

    public GenerationRegistry(ApiToolRepository apiToolRepository,
                               ApiRepository apiRepository,
                               ObjectMapper objectMapper) {
        this.apiToolRepository = apiToolRepository;
        this.apiRepository = apiRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * One addressable generation model, resolved.
     *
     * @param modelId              public id the surfaces use
     * @param kind                 modality produced (image, video, voice, ...)
     * @param model                the descriptor entry for this model
     * @param spec                 the endpoint's full descriptor
     * @param apiToolId            catalog endpoint that submits the job, and the
     *                             key the published price is attached to
     * @param toolSlug             composite {@code apiSlug/toolSlug}, the form the
     *                             billing and execution layers already speak
     * @param apiSlug              owning API's slug
     * @param apiName              owning API's display name
     * @param iconSlug             owning API's icon, for the admin screens
     * @param platformCredentialName integration name of the platform key that
     *                             unlocks it, or null when the API has none
     */
    public record GenerationModel(
            String modelId,
            String kind,
            GenerationSpec.Model model,
            GenerationSpec spec,
            UUID apiToolId,
            String toolSlug,
            String apiSlug,
            String apiName,
            String iconSlug,
            String platformCredentialName,
            String executionMode) {

        /**
         * True when the endpoint finishes the job asynchronously, which the
         * CATALOG owns through its execution block. Read from the endpoint
         * rather than from the descriptor so the two can never disagree about
         * whether a caller has to wait.
         */
        public boolean isAsync() {
            return "async_poll".equalsIgnoreCase(executionMode);
        }

        /** Label shown in admin screens and in the tool's discovery payload. */
        public String label() {
            return model.label();
        }

        /** Starting price shipped with the seed, before any platform override. */
        public GenerationSpec.Price seedPrice() {
            return model.price();
        }
    }

    /** Resolve one model by its public id. Case-insensitive. */
    public Optional<GenerationModel> resolve(String modelId) {
        if (modelId == null || modelId.isBlank()) return Optional.empty();
        return Optional.ofNullable(snapshot().byId.get(modelId.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Every model, optionally narrowed to one kind, ordered by kind then label
     * so the discovery payload and the admin tabs read the same way every time.
     */
    public List<GenerationModel> list(String kind) {
        List<GenerationModel> all = new ArrayList<>(snapshot().byId.values());
        if (kind != null && !kind.isBlank()) {
            String k = kind.trim().toLowerCase(Locale.ROOT);
            all.removeIf(m -> !m.kind().equals(k));
        }
        all.sort(Comparator.comparing(GenerationModel::kind).thenComparing(GenerationModel::label));
        return all;
    }

    /** Distinct kinds currently backed by at least one model. */
    public List<String> kinds() {
        return new ArrayList<>(new TreeSet<>(snapshot().byId.values().stream()
                .map(GenerationModel::kind).toList()));
    }

    /** Drop the cache so the next read reflects a just-finished import. */
    public void invalidate() {
        cache.set(null);
    }

    // ── snapshot building ───────────────────────────────────────────────────

    private Snapshot snapshot() {
        Snapshot current = cache.get();
        if (current != null && !current.isStale()) return current;
        Snapshot rebuilt = build();
        cache.set(rebuilt);
        return rebuilt;
    }

    private Snapshot build() {
        Map<String, GenerationModel> byId = new LinkedHashMap<>();
        List<ApiToolEntity> endpoints;
        try {
            endpoints = apiToolRepository.findGenerationEndpoints();
        } catch (Exception e) {
            log.error("[GenerationRegistry] failed to read generation endpoints: {}", e.getMessage(), e);
            return new Snapshot(Map.of(), Instant.now());
        }

        for (ApiToolEntity tool : endpoints) {
            String context = tool.getToolSlug() != null ? tool.getToolSlug() : String.valueOf(tool.getId());
            GenerationSpec spec;
            try {
                spec = GenerationSpec.parse(objectMapper.readTree(tool.getGenerationSpec()), context)
                        .orElse(null);
            } catch (Exception e) {
                // A row that fails to parse is skipped, never fatal: one bad
                // descriptor must not take the whole generation surface down.
                // The import is what refuses malformed descriptors; reaching
                // here means a row predates that gate or was written by hand.
                log.error("[GenerationRegistry] skipping unparseable generation_spec on tool {}: {}",
                        context, e.getMessage());
                continue;
            }
            if (spec == null) continue;

            ApiEntity api = apiRepository.findById(tool.getApiId()).orElse(null);
            if (api == null) {
                log.warn("[GenerationRegistry] tool {} has a generation spec but no owning API", context);
                continue;
            }
            String composite = api.getApiSlug() + "/" + tool.getToolSlug();

            for (GenerationSpec.Model m : spec.models()) {
                if (!m.canAlwaysStateItsSize()) {
                    // The model still works for a caller who states the size,
                    // so it stays listed: deleting it here would turn a seed
                    // mistake into a missing product. But every call that omits
                    // the size is refused, which is worth saying once per
                    // snapshot rather than only in the caller's error.
                    log.warn("[GenerationRegistry] model '{}' on {} is priced per {} but neither "
                                    + "defaults nor requires '{}' - every call that omits it is refused. "
                                    + "Give its constraint an 'allowed' list of sizes, or list it as "
                                    + "required, in the seed.",
                            m.id(), composite, m.price().unit(), m.measuringParam());
                }
                GenerationModel resolved = new GenerationModel(
                        m.id(), spec.kind(), m, spec,
                        tool.getId(), composite,
                        api.getApiSlug(), api.getApiName(), api.getIconSlug(),
                        api.getPlatformCredentialName(), tool.getExecutionMode());
                GenerationModel clash = byId.putIfAbsent(m.id(), resolved);
                if (clash != null) {
                    // Uniqueness is enforced per descriptor at import; a clash
                    // here means two DIFFERENT endpoints claimed the same id.
                    // First registration wins so behaviour stays deterministic,
                    // and the collision is logged loudly because it is a seed
                    // authoring bug that silently shadows a model.
                    log.error("[GenerationRegistry] duplicate generation model id '{}' claimed by {} and {}"
                                    + " - keeping {}. Fix the seed: model ids are global.",
                            m.id(), clash.toolSlug(), composite, clash.toolSlug());
                }
            }
        }

        log.info("[GenerationRegistry] {} generation model(s) across {} endpoint(s)",
                byId.size(), endpoints.size());
        return new Snapshot(Map.copyOf(byId), Instant.now());
    }

    private record Snapshot(Map<String, GenerationModel> byId, Instant builtAt) {
        boolean isStale() {
            return Instant.now().isAfter(builtAt.plus(CACHE_TTL));
        }
    }
}
