package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * In-app, authenticated read-only access to another user's profile. NOT on the gateway
 * public allowlist, so a JWT is required - logged-out visitors cannot read profiles.
 *
 * <p>Two lookups, neither of which can expose the real first/last name or the raw OAuth
 * account username:
 * <ul>
 *   <li>{@code by-handle/{handle}} - the canonical URL lookup ({@code /app/u/{handle}}). The
 *       handle is a chosen, URL-safe public slug derived from the display name (never the raw
 *       account username, never the numeric user/tenant id).</li>
 *   <li>{@code by-id/{userId}} - for internal links that already carry the numeric id (e.g. a
 *       DM thread or a publication card), which resolve the profile without a handle.</li>
 * </ul>
 *
 * <p>The returned {@link PublicProfileDto} exposes the display name + @handle, avatar, bio and
 * join date - no email, no roles. Returns 404 when the user does not exist, is disabled, or has
 * set their profile to PRIVATE (indistinguishable, so this can't be a user-existence oracle).
 */
@RestController
@RequestMapping("/api/users/public")
public class PublicProfileController {

    private final UserService userService;

    public PublicProfileController(UserService userService) {
        this.userService = userService;
    }

    /** Canonical URL lookup by the public @handle ({@code /app/u/{handle}}). */
    @GetMapping("/by-handle/{handle}")
    public ResponseEntity<PublicProfileDto> getByHandle(@PathVariable String handle) {
        return userService.findByHandle(handle)
                .flatMap(userService::getPublicProfile)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Lookup by numeric user id - for in-app links (DM threads / publication
     * cards carry the id, not the handle).
     *
     * <p><b>Requires an authenticated caller.</b> The id is sequential, so an
     * anonymous by-id would let anyone walk 1..N and harvest every profile on
     * the platform: display name and handle for the whole user base. The
     * cloud gateway already keeps this path off its public allowlist (only
     * {@code /by-handle} is public), but CE has no gateway - its monolith
     * filter passes any request with no Authorization header straight through
     * and leaves the decision to this layer. Enforcing it here is what makes
     * the rule hold in BOTH editions instead of only on cloud.
     *
     * <p>Answers 404, not 401, so it stays indistinguishable from a missing or
     * private profile and cannot be used to probe which ids exist.
     */
    @GetMapping("/by-id/{userId}")
    public ResponseEntity<PublicProfileDto> getById(
            @PathVariable Long userId,
            @RequestHeader(value = "X-User-ID", required = false) String requesterId) {
        if (requesterId == null || requesterId.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return userService.findById(userId)
                .flatMap(userService::getPublicProfile)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
