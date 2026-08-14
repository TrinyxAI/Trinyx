/**
 * The `generation` grant (unified image / video / audio / voice / music tool) must
 * travel end to end on every surface where `imageGeneration` does: the new-conversation
 * seed, the agent PUT, the conversation PUT, and the read-back mappers.
 *
 * Pre-change the toggle in the composer Options panel wrote into the local config and
 * every layer above it dropped the key: the builders omitted it and the readers never
 * looked for it, so an admin flipped the switch, reloaded, and it was off again.
 *
 * The grant is also INDEPENDENT of `imageGeneration` in both directions: an image grant
 * must never widen into video, which costs an order of magnitude more per call.
 */
import { describe, it, expect } from 'vitest';
import {
  buildDraftChatConfigBody,
  configFromAgent,
  configFromConversation,
  buildAgentPatch,
  buildConversationPatch,
} from '../useChatConfig';
import type { Agent } from '@/lib/api/orchestrator/types';

describe('buildDraftChatConfigBody - generation seed into a new conversation', () => {
  it('carries the generation grant into the conversation chatConfig body', () => {
    const body = buildDraftChatConfigBody({ generation: { enabled: true } });
    expect(body).toEqual({ generation: { enabled: true } });
  });

  it('carries an explicit enabled:false (a workspace default that denies must reach the conversation)', () => {
    const body = buildDraftChatConfigBody({ generation: { enabled: false } });
    expect(body).toEqual({ generation: { enabled: false } });
  });

  it('never seeds generation from the retired image grant', () => {
    // @ts-expect-error - the retired key is gone from ChatConfig on purpose
    const body = buildDraftChatConfigBody({ imageGeneration: { enabled: true } });
    // The retired key contributes NOTHING, so the draft has nothing to seed and
    // the builder returns undefined (its documented "skip the field" answer)
    // rather than an empty object. Asserting `'generation' in body` instead
    // would read `undefined in undefined`, which is neither true nor false and
    // states no contract at all.
    expect(body).toBeUndefined();
  });

  it('omits generation when the draft does not set it', () => {
    const body = buildDraftChatConfigBody({ webSearch: true });
    expect(body && 'generation' in body).toBe(false);
  });
});

describe('generation read mappers (save-and-reload)', () => {
  it('configFromAgent reads toolsConfig.generation in the object shape', () => {
    const agent = { name: 'A', toolsConfig: { generation: { enabled: true, model: 'seedance-2.0-fast' } } } as unknown as Agent;
    expect(configFromAgent(agent).generation).toEqual({ enabled: true, model: 'seedance-2.0-fast' });
  });

  it('configFromAgent tolerates the boolean shape the agent modal writes', () => {
    expect(configFromAgent({ name: 'A', toolsConfig: { generation: true } } as unknown as Agent).generation)
      .toEqual({ enabled: true, model: undefined });
    expect(configFromAgent({ name: 'A', toolsConfig: { generation: false } } as unknown as Agent).generation)
      .toEqual({ enabled: false, model: undefined });
  });

  it('configFromAgent leaves generation undefined when absent (UI falls back to OFF)', () => {
    expect(configFromAgent({ name: 'A', toolsConfig: { mode: 'all' } } as unknown as Agent).generation).toBeUndefined();
  });

  it('configFromAgent never derives generation from the retired image grant', () => {
    const agent = { name: 'A', toolsConfig: { imageGeneration: true } } as unknown as Agent;
    const config = configFromAgent(agent);
    expect(config.generation).toBeUndefined();
    expect('imageGeneration' in config).toBe(false);
  });

  it('configFromConversation reads chatConfig.generation (both shapes)', () => {
    expect(configFromConversation({ chatConfig: { generation: { enabled: true } } }).generation)
      .toEqual({ enabled: true, model: undefined });
    expect(configFromConversation({ chatConfig: { generation: true } }).generation)
      .toEqual({ enabled: true, model: undefined });
    expect(configFromConversation({ chatConfig: {} }).generation).toBeUndefined();
  });

  it('configFromConversation never derives generation from the image grant', () => {
    expect(configFromConversation({ chatConfig: { imageGeneration: { enabled: true } } }).generation).toBeUndefined();
  });
});

describe('buildAgentPatch - generation persists on the agent surface', () => {
  const agent = { name: 'A' } as Agent;

  it('writes the grant under toolsConfig.generation (PUT /agents)', () => {
    const patch = buildAgentPatch(agent, { generation: { enabled: true } });
    expect(patch.toolsConfig).toMatchObject({ generation: { enabled: true } });
  });

  it('emits enabled:false so revoking survives the backend toolsConfig MERGE', () => {
    // The backend merges toolsConfig on update: an omitted key keeps the stored
    // grant, so an agent that had generation ON could never be switched off.
    const patch = buildAgentPatch(agent, { generation: { enabled: false } });
    expect(patch.toolsConfig).toMatchObject({ generation: { enabled: false } });
  });

  it('keeps the rest of the stored toolsConfig when only generation changes', () => {
    const current = {
      name: 'A',
      toolsConfig: { mode: 'custom', workflowsGrant: 'all', imageGeneration: true },
    } as unknown as Agent;
    const patch = buildAgentPatch(current, { generation: { enabled: true } });
    expect(patch.toolsConfig).toEqual({
      mode: 'custom',
      workflowsGrant: 'all',
      imageGeneration: true,
      generation: { enabled: true },
    });
  });

  it('never writes the retired image grant, and editing generation does not resurrect it', () => {
    const generationOnly = buildAgentPatch(agent, { generation: { enabled: true } }).toolsConfig as Record<string, unknown>;
    expect('imageGeneration' in generationOnly).toBe(false);
    // @ts-expect-error - a stale caller passing the retired key must change nothing
    expect('toolsConfig' in buildAgentPatch(agent, { imageGeneration: { enabled: true } })).toBe(false);
  });

  it('omits toolsConfig entirely when no tool flag is edited (patch semantics)', () => {
    expect('toolsConfig' in buildAgentPatch(agent, { temperature: 0.3 })).toBe(false);
  });
});

describe('buildConversationPatch - generation persists on the conversation surface', () => {
  it('writes chatConfig.generation (PUT /conversations)', () => {
    const patched = buildConversationPatch({}, { generation: { enabled: true } }) as { chatConfig: Record<string, unknown> };
    expect(patched.chatConfig.generation).toEqual({ enabled: true });
  });

  it('writes enabled:false verbatim so turning the grant off persists', () => {
    const patched = buildConversationPatch(
      { generation: { enabled: true } },
      { generation: { enabled: false } },
    ) as { chatConfig: Record<string, unknown> };
    expect(patched.chatConfig.generation).toEqual({ enabled: false });
  });

  it('keeps the stored grant when the edit touches another field', () => {
    // The conversation patch REWRITES chatConfig, so a key it forgets is erased,
    // not merely left alone.
    const patched = buildConversationPatch(
      { generation: { enabled: true } },
      { temperature: 0.5 },
    ) as { chatConfig: Record<string, unknown> };
    expect(patched.chatConfig.generation).toEqual({ enabled: true });
  });

  it('does not carry the retired image grant through the patch', () => {
    const patched = buildConversationPatch(
      // @ts-expect-error - the retired key is gone from ChatConfig on purpose
      { imageGeneration: { enabled: true } },
      { generation: { enabled: false } },
    ) as { chatConfig: Record<string, unknown> };
    expect('imageGeneration' in patched.chatConfig).toBe(false);
    expect(patched.chatConfig.generation).toEqual({ enabled: false });
  });

  it('omits generation when neither the current config nor the edit carries it', () => {
    const patched = buildConversationPatch({}, { webSearch: true }) as { chatConfig: Record<string, unknown> };
    expect('generation' in patched.chatConfig).toBe(false);
  });
});
