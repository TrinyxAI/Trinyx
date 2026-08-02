package com.apimarketplace.agent.domain;

import java.util.Locale;
import java.util.Set;

/**
 * Canonical names of the local-CLI "bridge" providers.
 *
 * <p>A bridge provider is NOT an API provider: it runs a CLI binary on the
 * bridge host (Claude Code, Codex, Gemini CLI, Mistral Vibe) instead of calling
 * a remote HTTP API with a key. That distinction is what makes an agent bound
 * to one of these providers a self-hosted concern, so the set is consumed well
 * outside the agent stack, e.g. by the marketplace to decide whether a
 * publication is Community-Edition exclusive.
 *
 * <p>This lives in {@code agent-common} (the lowest common module) so every
 * consumer reads the SAME list. {@code BridgeAllowlist} in
 * {@code shared-agent-lib} stays the authority for which MODELS each bridge
 * routes and re-exports this set as its {@code BRIDGE_PROVIDERS}; modules that
 * only need the provider NAMES depend on this class instead of pulling in the
 * whole agent runtime.
 */
public final class BridgeProviders {

    private BridgeProviders() {}

    /** The four bridges wired in the platform. */
    public static final Set<String> NAMES =
            Set.of("claude-code", "codex", "gemini-cli", "mistral-vibe");

    /**
     * True when {@code provider} names one of the local-CLI bridges. Null-safe;
     * comparison is case-insensitive and trims surrounding whitespace, because
     * the value often arrives from a persisted snapshot rather than from code.
     */
    public static boolean isBridgeProvider(String provider) {
        if (provider == null) {
            return false;
        }
        return NAMES.contains(provider.trim().toLowerCase(Locale.ROOT));
    }
}
