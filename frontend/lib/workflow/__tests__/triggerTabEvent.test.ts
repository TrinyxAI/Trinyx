/**
 * A workflow can hold several triggers of the same type, so resolving an
 * "open this trigger's tab" request by type alone always lands on the first one
 * and silently ignores the user's pick. These tests pin the id-first contract.
 */
import { describe, it, expect } from 'vitest';
import { findTriggerTabConfig } from '../triggerTabEvent';

const CONFIGS = [
  { triggerId: 'trigger:support_chat', type: 'chat' },
  { triggerId: 'trigger:sales_chat', type: 'chat' },
  { triggerId: 'trigger:signup_form', type: 'form' },
];

describe('findTriggerTabConfig', () => {
  it('selects the exact trigger id even when an earlier config shares its type', () => {
    const match = findTriggerTabConfig(CONFIGS, {
      nodeId: 'n2',
      triggerType: 'chat',
      triggerId: 'trigger:sales_chat',
    });
    expect(match?.triggerId).toBe('trigger:sales_chat');
  });

  it('falls back to the first config of the requested type when no id is given', () => {
    const match = findTriggerTabConfig(CONFIGS, { nodeId: 'n1', triggerType: 'chat' });
    expect(match?.triggerId).toBe('trigger:support_chat');
  });

  it('falls back to the type when the given id is unknown to the panel', () => {
    // The canvas can name a trigger the panel has not loaded yet - opening the
    // right tab family beats opening nothing.
    const match = findTriggerTabConfig(CONFIGS, {
      nodeId: 'n9',
      triggerType: 'form',
      triggerId: 'trigger:not_loaded_yet',
    });
    expect(match?.triggerId).toBe('trigger:signup_form');
  });

  it('returns undefined when no config matches the type', () => {
    expect(findTriggerTabConfig(CONFIGS, { nodeId: 'n1', triggerType: 'webhook' })).toBeUndefined();
  });

  it('returns undefined for an empty or missing config list', () => {
    expect(findTriggerTabConfig([], { nodeId: 'n1', triggerType: 'chat' })).toBeUndefined();
    expect(findTriggerTabConfig(undefined, { nodeId: 'n1', triggerType: 'chat' })).toBeUndefined();
    expect(findTriggerTabConfig(null, { nodeId: 'n1', triggerType: 'chat' })).toBeUndefined();
  });
});
