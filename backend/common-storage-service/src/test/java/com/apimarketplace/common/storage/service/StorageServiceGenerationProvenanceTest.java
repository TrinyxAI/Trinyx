package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.GenerationProvenanceFields;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.api.MappingOperations;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.storage.util.JsonSkeletonGenerator;
import com.apimarketplace.common.storage.util.StorageUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StorageService#stampGenerationProvenance}.
 *
 * <p>This is how a generated asset comes to carry the recipe it was made from, which is what lets a
 * reader tell it apart from an upload and run it again with one word changed. Like adoption, it
 * fills a gap on a row that already exists and has already been paid for, so what matters most is
 * what it REFUSES to do: never rewrite a recipe, never destroy another producer's metadata, never
 * store a recipe too large to be worth keeping, and never throw at a generation that has succeeded.
 */
@DisplayName("StorageService.stampGenerationProvenance")
@ExtendWith(MockitoExtension.class)
class StorageServiceGenerationProvenanceTest {

    private static final String TENANT = "tenant-1";
    private static final UUID ASSET = UUID.randomUUID();

    @Mock private StorageRepository storageRepository;
    @Mock private QuotaOperations quotaService;
    @Mock private MappingOperations mappingService;
    @Mock private StorageUtils storageUtils;
    @Mock private JsonSkeletonGenerator skeletonGenerator;
    @Mock private StorageBreakdownService breakdownService;

    private StorageService storageService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        storageService = new StorageService(
                storageRepository, quotaService, mappingService,
                storageUtils, skeletonGenerator, new ObjectMapper(), breakdownService);
    }

    /** A generated asset as the upload leaves it: stored, with nothing saying where it came from. */
    private static StorageEntity asset(String metadata) {
        StorageEntity e = new StorageEntity();
        e.setId(ASSET);
        e.setTenantId(TENANT);
        e.setStatus(StorageStatus.ACTIVE);
        e.setStorageType("S3_FILE");
        e.setSourceType(StorageSourceTypes.S3_FILE);
        e.setS3Key("1/general/catalog-binary/c31d_flux.png");
        e.setFileName("flux.png");
        e.setMimeType("image/png");
        e.setMetadata(metadata);
        return e;
    }

    private static Map<String, Object> recipe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(GenerationProvenanceFields.MODEL, "flux-1.1-pro");
        out.put(GenerationProvenanceFields.KIND, "image");
        out.put(GenerationProvenanceFields.PROMPT, "a lighthouse at dusk");
        return out;
    }

    private StorageEntity stampAndCapture(Map<String, Object> provenance) {
        ArgumentCaptor<StorageEntity> saved = ArgumentCaptor.forClass(StorageEntity.class);
        storageService.stampGenerationProvenance(TENANT, List.of(ASSET), provenance);
        verify(storageRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("records the recipe under the generation key of the asset's metadata")
    void recordsTheRecipe() throws Exception {
        when(storageRepository.findByIdAndTenantId(ASSET, TENANT)).thenReturn(Optional.of(asset(null)));

        StorageEntity saved = stampAndCapture(recipe());

        JsonNode written = mapper.readTree(saved.getMetadata())
                .get(GenerationProvenanceFields.METADATA_KEY);
        assertThat(written.get(GenerationProvenanceFields.MODEL).asText()).isEqualTo("flux-1.1-pro");
        assertThat(written.get(GenerationProvenanceFields.PROMPT).asText())
                .isEqualTo("a lighthouse at dusk");
    }

    @Test
    @DisplayName("never rewrites a recipe an asset already carries")
    void neverRewritesAnExistingRecipe() {
        // Write-once, like adoption: a second stamp on the same row would describe an asset as
        // having been made by something that did not make it.
        when(storageRepository.findByIdAndTenantId(ASSET, TENANT))
                .thenReturn(Optional.of(asset("{\"generation\":{\"model\":\"first\"}}")));

        int stamped = storageService.stampGenerationProvenance(TENANT, List.of(ASSET), recipe());

        assertThat(stamped).isZero();
        verify(storageRepository, never()).save(any());
    }

    @Test
    @DisplayName("treats ANY value under the key as already recorded, even one it could not have written")
    void neverOverwritesAValueUnderTheKey() {
        // A scalar under `generation` is not a recipe, and the readers ignore it - but this stays
        // write-once on the KEY rather than on the shape. Replacing "something I cannot read" would
        // make the rule "overwrite when I disagree with what is there", which is how a producer
        // ends up destroying another one's data on a shared column.
        when(storageRepository.findByIdAndTenantId(ASSET, TENANT))
                .thenReturn(Optional.of(asset("{\"generation\":5}")));

        assertThat(storageService.stampGenerationProvenance(TENANT, List.of(ASSET), recipe())).isZero();
        verify(storageRepository, never()).save(any());
    }

    @Test
    @DisplayName("keeps the metadata another producer already wrote")
    void keepsForeignMetadata() throws Exception {
        // The column is shared. Replacing it wholesale would silently destroy a sibling's data.
        when(storageRepository.findByIdAndTenantId(ASSET, TENANT))
                .thenReturn(Optional.of(asset("{\"screenshot\":{\"width\":1080}}")));

        StorageEntity saved = stampAndCapture(recipe());

        JsonNode written = mapper.readTree(saved.getMetadata());
        assertThat(written.get("screenshot").get("width").asInt()).isEqualTo(1080);
        assertThat(written.has(GenerationProvenanceFields.METADATA_KEY)).isTrue();
    }

    @Test
    @DisplayName("starts a fresh object when the metadata is not one")
    void survivesNonObjectMetadata() throws Exception {
        // A legacy row can hold anything. Refusing here would cost the asset its recipe over a
        // shape no reader of this column expects, and nothing readable is being discarded.
        when(storageRepository.findByIdAndTenantId(ASSET, TENANT))
                .thenReturn(Optional.of(asset("\"legacy string\"")));

        StorageEntity saved = stampAndCapture(recipe());

        assertThat(mapper.readTree(saved.getMetadata())
                .has(GenerationProvenanceFields.METADATA_KEY)).isTrue();
    }

    @Test
    @DisplayName("stores nothing at all rather than a recipe too large to keep")
    void refusesAnOversizedRecipe() {
        // A truncated recipe cannot reproduce the asset, so it would offer a Regenerate button that
        // quietly makes something else. Storing none is the honest outcome.
        Map<String, Object> huge = recipe();
        huge.put("blob", "x".repeat(GenerationProvenanceFields.MAX_PROVENANCE_BYTES + 1));

        int stamped = storageService.stampGenerationProvenance(TENANT, List.of(ASSET), huge);

        assertThat(stamped).isZero();
        verify(storageRepository, never()).save(any());
    }

    @Test
    @DisplayName("skips a row that belongs to another tenant instead of failing")
    void skipsAnotherTenantsRow() {
        // The finder is tenant-scoped, so a foreign id simply is not found. The generation has
        // already run and been charged: it must not fail over where its recipe went.
        when(storageRepository.findByIdAndTenantId(ASSET, TENANT)).thenReturn(Optional.empty());

        int stamped = storageService.stampGenerationProvenance(TENANT, List.of(ASSET), recipe());

        assertThat(stamped).isZero();
        verify(storageRepository, never()).save(any());
    }

    @Test
    @DisplayName("does nothing without ids or without a recipe")
    void doesNothingWithoutInput() {
        assertThat(storageService.stampGenerationProvenance(TENANT, List.of(), recipe())).isZero();
        assertThat(storageService.stampGenerationProvenance(TENANT, List.of(ASSET), Map.of())).isZero();
        assertThat(storageService.stampGenerationProvenance(TENANT, null, recipe())).isZero();
        verify(storageRepository, never()).save(any());
    }

    @Test
    @DisplayName("leaves the source type alone, because the usage ledger reads it on delete")
    void doesNotRetypeTheRow() {
        // Re-typing the row would book bytes under one bucket at insert and remove them from
        // another at delete, permanently skewing the tenant's storage breakdown.
        when(storageRepository.findByIdAndTenantId(ASSET, TENANT)).thenReturn(Optional.of(asset(null)));

        StorageEntity saved = stampAndCapture(recipe());

        assertThat(saved.getSourceType()).isEqualTo(StorageSourceTypes.S3_FILE);
    }
}
