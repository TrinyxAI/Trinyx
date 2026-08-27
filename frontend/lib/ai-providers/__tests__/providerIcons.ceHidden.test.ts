import { describe, it, expect } from 'vitest';
import {
  isProviderHiddenInCe,
  CE_HIDDEN_PROVIDERS,
  PROVIDER_ICON_MAP,
  PROVIDER_DISPLAY_NAMES,
} from '@/lib/ai-providers/providerIcons';

describe('CE-hidden providers boundary', () => {
  it('hides the multi-provider aggregator (openrouter) and cohere', () => {
    expect(isProviderHiddenInCe('openrouter')).toBe(true);
    expect(isProviderHiddenInCe('cohere')).toBe(true);
  });

  it('does not hide any other provider, incl. the new qwen/moonshot/minimax', () => {
    for (const p of ['openai', 'anthropic', 'google', 'deepseek', 'zai', 'qwen', 'moonshot', 'minimax']) {
      expect(isProviderHiddenInCe(p)).toBe(false);
    }
  });

  it('is case-insensitive and null-safe', () => {
    expect(isProviderHiddenInCe('OpenRouter')).toBe(true);
    expect(isProviderHiddenInCe(null)).toBe(false);
    expect(isProviderHiddenInCe(undefined)).toBe(false);
    expect(isProviderHiddenInCe('')).toBe(false);
  });

  it('exposes exactly the two blocked providers', () => {
    expect([...CE_HIDDEN_PROVIDERS].sort()).toEqual(['cohere', 'openrouter']);
  });
});

describe('new Chinese providers are wired into the icon/label maps', () => {
  it('qwen and moonshot have an icon slug and a display name', () => {
    expect(PROVIDER_ICON_MAP.qwen).toBe('qwen');
    expect(PROVIDER_ICON_MAP.moonshot).toBe('moonshot');
    expect(PROVIDER_DISPLAY_NAMES.qwen).toBe('Qwen');
    expect(PROVIDER_DISPLAY_NAMES.moonshot).toBe('Moonshot');
  });

  // getProviderIconSlug falls back to the lowercased provider name, so a
  // provider missing from the map renders a 404 <img> rather than nothing.
  // Pin minimax explicitly: /icons/services/minimax.svg is the asset that has
  // to exist for the MiniMax rows seeded by V437 to render.
  it('minimax has an icon slug and a display name', () => {
    expect(PROVIDER_ICON_MAP.minimax).toBe('minimax');
    expect(PROVIDER_DISPLAY_NAMES.minimax).toBe('MiniMax');
  });
});
