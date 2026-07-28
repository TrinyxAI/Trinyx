package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicProfileController")
class PublicProfileControllerTest {

    @Mock
    private UserService userService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicProfileController(userService)).build();
    }

    private User user() {
        User u = new User();
        u.setId(7L);
        u.setUsername("alice");
        u.setEnabled(true);
        return u;
    }

    private PublicProfileDto sampleProfile() {
        return new PublicProfileDto(7L, "Alice A.", "alice_a", "/api/users/7/avatar",
                "Builder", LocalDateTime.of(2024, 3, 1, 0, 0), false);
    }

    @Test
    @DisplayName("GET /by-id/{userId} → 200 with display name + public @handle; never the raw username, email or roles")
    void byIdReturnsProfile() throws Exception {
        User u = user();
        when(userService.findById(7L)).thenReturn(Optional.of(u));
        when(userService.getPublicProfile(u)).thenReturn(Optional.of(sampleProfile()));

        mockMvc.perform(get("/api/users/public/by-id/7").header("X-User-ID", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.displayName").value("Alice A."))
                .andExpect(jsonPath("$.handle").value("alice_a"))
                .andExpect(jsonPath("$.bio").value("Builder"))
                // Privacy: the raw OAuth account username (can be the real name), website, social
                // links, email and roles must never appear - only the chosen @handle is public.
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.websiteUrl").doesNotExist())
                .andExpect(jsonPath("$.socialLinks").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.roles").doesNotExist());
    }

    @Test
    @DisplayName("GET /by-handle/{handle} → 200; resolves by the public @handle, not the numeric id")
    void byHandleReturnsProfile() throws Exception {
        User u = user();
        when(userService.findByHandle("alice_a")).thenReturn(Optional.of(u));
        when(userService.getPublicProfile(u)).thenReturn(Optional.of(sampleProfile()));

        mockMvc.perform(get("/api/users/public/by-handle/alice_a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handle").value("alice_a"))
                .andExpect(jsonPath("$.displayName").value("Alice A."))
                .andExpect(jsonPath("$.username").doesNotExist());
    }

    @Test
    @DisplayName("GET /by-handle/{handle} → 404 when no profile has that handle")
    void byHandleUnknownReturns404() throws Exception {
        when(userService.findByHandle("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/users/public/by-handle/ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /by-id/{userId} → 404 when the user does not exist")
    void byIdUnknownReturns404() throws Exception {
        when(userService.findById(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/users/public/by-id/404").header("X-User-ID", "42"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /by-id/{userId} → 404 when the profile is PRIVATE (service returns empty)")
    void byIdPrivateReturns404() throws Exception {
        User u = user();
        when(userService.findById(7L)).thenReturn(Optional.of(u));
        when(userService.getPublicProfile(u)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/users/public/by-id/7").header("X-User-ID", "42"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /by-id/{userId} → 404 for an ANONYMOUS caller, and the profile is never looked up")
    void byIdAnonymousIsRefused() throws Exception {
        // Found by e2e against a live CE stack: by-id answered 200 with a full
        // profile to an unauthenticated caller. CE has no gateway, so the cloud
        // allowlist (which exposes only /by-handle) never applied there, and the
        // monolith filter passes credential-less requests straight through for
        // "the controller layer to decide". The id is sequential, so that let
        // anyone walk 1..N and harvest every display name and handle.
        mockMvc.perform(get("/api/users/public/by-id/7"))
                .andExpect(status().isNotFound());

        // 404 rather than 401 keeps it indistinguishable from a missing or
        // private profile, so it cannot be used to probe which ids exist.
        org.mockito.Mockito.verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("GET /by-id/{userId} → 404 when the caller header is present but blank")
    void byIdBlankRequesterIsRefused() throws Exception {
        mockMvc.perform(get("/api/users/public/by-id/7").header("X-User-ID", "   "))
                .andExpect(status().isNotFound());

        org.mockito.Mockito.verifyNoInteractions(userService);
    }
}
