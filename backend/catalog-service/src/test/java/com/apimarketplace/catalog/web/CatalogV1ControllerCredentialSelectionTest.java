package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.service.CatalogV1Service;
import com.apimarketplace.catalog.service.exception.CredentialSelectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How a refused run-time credential selection leaves the catalog.
 *
 * <p>Two things are pinned here, and both were unreachable contracts before they
 * were tested. The refusal must arrive as its OWN status and error code rather
 * than as a generic 500 or a 200 envelope, because the reader has to be able to
 * tell "the account you named does not exist" apart from "the provider failed".
 * And a request that carries a selection without saying it runs on the caller own
 * credentials must be refused at the door: the branches that read the selection
 * are not the only ones a caller can reach, so checking only where it is read
 * leaves the flag settable on paths that ignore it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogV1Controller - a refused run-time credential selection")
class CatalogV1ControllerCredentialSelectionTest {

    @Mock private CatalogV1Service catalogV1Service;
    @Mock private com.apimarketplace.catalog.service.execution.MockToolExecutionService mockToolExecutionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CatalogV1Controller(catalogV1Service, mockToolExecutionService))
                .build();
    }

    private static final String TOOL = "instagram/instagram-publish";

    @Test
    @DisplayName("answers 422 with the error code and the sentence written for the reader")
    void refusalIsItsOwnStatus() throws Exception {
        when(catalogV1Service.executeTool(anyString(), any(), anyString(), any(), anyString()))
                .thenThrow(new CredentialSelectionException(
                        "This step selects its credential at run time (name 'Client Z') but no active "
                                + "credential of this integration is named that."));

        mockMvc.perform(post("/catalog/v1/tools/{id}/execute", TOOL)
                        .header("X-User-ID", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credentialSource\":\"user\",\"selectedCredentialName\":\"Client Z\","
                                + "\"credentialSelectionStrict\":true,\"parameters\":{}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("CREDENTIAL_SELECTION_UNRESOLVED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Client Z")));
    }

    @Test
    @DisplayName("a selection with no user source is refused before the tool is reached")
    void strictWithoutUserSourceIsRefusedAtTheDoor() throws Exception {
        // The agentic branch and the platform branch never consult the selection, so
        // a request carrying one on either of them would have run on a different
        // account and reported success. The proof is the absence: the service is
        // never called at all.
        mockMvc.perform(post("/catalog/v1/tools/{id}/execute", TOOL)
                        .header("X-User-ID", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedCredentialName\":\"Client B\","
                                + "\"credentialSelectionStrict\":true,\"parameters\":{}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CREDENTIAL_SELECTION_UNRESOLVED"));

        verify(catalogV1Service, never()).executeTool(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("the same selection on the platform pool is refused too")
    void strictOnThePlatformPoolIsRefused() throws Exception {
        mockMvc.perform(post("/catalog/v1/tools/{id}/execute", TOOL)
                        .header("X-User-ID", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credentialSource\":\"platform\",\"selectedCredentialName\":\"Client B\","
                                + "\"credentialSelectionStrict\":true,\"parameters\":{}}"))
                .andExpect(status().isUnprocessableEntity());

        verify(catalogV1Service, never()).executeTool(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("an ordinary call with no selection is untouched by any of this")
    void ordinaryCallsAreUnaffected() throws Exception {
        // The no-regression half: every call made today takes this branch, and a
        // refusal here would break all of them.
        when(catalogV1Service.executeTool(anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(com.apimarketplace.catalog.domain.dto.ToolExecutionResponse.builder()
                        .success(true)
                        .build());

        mockMvc.perform(post("/catalog/v1/tools/{id}/execute", TOOL)
                        .header("X-User-ID", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parameters\":{}}"))
                .andExpect(status().isOk());

        verify(catalogV1Service).executeTool(anyString(), any(), anyString(), any(), anyString());
    }
}
