import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

import de from '@/messages/de.json';
import en from '@/messages/en.json';
import es from '@/messages/es.json';
import fr from '@/messages/fr.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

import {
  credentialSelectorText,
  isDynamicCredential,
  toggleCredentialMode,
} from '../credentialSelectorMode';

/**
 * The inspector is where the three states of a step's account choice are produced,
 * and it had no test of any kind: the mode discriminator, the clearing of the pin,
 * and the null-versus-blank distinction were all verified by nobody.
 */
describe('credential selector mode', () => {
  describe('which mode a step is in', () => {
    it('a step with no key is static, which is every workflow written before this', () => {
      // An error here would light up every canvas in the product.
      expect(isDynamicCredential({})).toBe(false);
      expect(isDynamicCredential(undefined)).toBe(false);
      expect(isDynamicCredential({ credentialSelector: null })).toBe(false);
    });

    it('a step with a BLANK key is dynamic, not static', () => {
      // Derived from the text instead, clearing the field to retype swapped the whole
      // control back to the picker mid-edit, and the picker auto-persists the account
      // default - which is how the feature used to erase itself.
      expect(isDynamicCredential({ credentialSelector: '' })).toBe(true);
      expect(isDynamicCredential({ credentialSelector: '   ' })).toBe(true);
    });

    it('a step with a filled key is dynamic', () => {
      expect(isDynamicCredential({ credentialSelector: '{{item.ig_account}}' })).toBe(true);
    });

    it('a NUMBER counts as a selector, because a credential id is a legitimate value', () => {
      expect(isDynamicCredential({ credentialSelector: 42 })).toBe(true);
      expect(credentialSelectorText({ credentialSelector: 42 })).toBe('42');
    });
  });

  describe('toggling the mode', () => {
    it('going dynamic clears the account pin, so the plan cannot say two things', () => {
      const next = toggleCredentialMode({
        selectedCredentialId: 99,
        selectedCredentialName: 'Client A',
      });

      expect(next.credentialSelector).toBe('');
      expect(next.selectedCredentialId).toBeNull();
      expect(next.selectedCredentialName).toBeNull();
    });

    it('going dynamic seeds nothing, rather than a template that is wrong more often than right', () => {
      expect(toggleCredentialMode({}).credentialSelector).toBe('');
    });

    it('going static writes null, not a blank, or the control never leaves dynamic mode', () => {
      const next = toggleCredentialMode({ credentialSelector: '{{item.account}}' });

      expect(next.credentialSelector).toBeNull();
      expect(isDynamicCredential(next)).toBe(false);
    });

    it('going dynamic clears the pool pin too, because a save would discard it anyway', () => {
      // Kept, it looked kinder and was a lie: the exporter writes only the selector
      // when one is present, so the pin vanished at the next save and the author
      // discovered it after a reload. Clearing it here makes the loss visible at the
      // moment it is caused. The earlier version of this test asserted the opposite
      // and was true only within one unsaved editing session.
      const pinned = { credentialSource: 'platform', platformCredentialId: 7 };

      const dynamic = toggleCredentialMode(pinned);

      expect(dynamic.credentialSource).toBeUndefined();
      expect(dynamic.platformCredentialId).toBeNull();
      expect(isDynamicCredential(dynamic)).toBe(true);
    });

    it('toggling twice from static returns to static', () => {
      const once = toggleCredentialMode({});
      expect(isDynamicCredential(once)).toBe(true);
      expect(isDynamicCredential(toggleCredentialMode(once))).toBe(false);
    });
  });
});

/**
 * next-intl renders a missing key as its raw dotted path, so a key added to en.json
 * alone ships a visible "workflowBuilder.credentialSelector.toggleHelpOn" to five of
 * six audiences. That is exactly the failure the switch rewrite could have caused: it
 * introduced three keys and retired three others that no longer have a reader.
 *
 * The key list is READ OUT OF THE COMPONENT rather than restated here. A hand-kept
 * copy has the property that the one mistake it exists to catch, adding a
 * tCredSelector call and forgetting the translations, is also the mistake that stops
 * it noticing: nobody who forgot the translation remembers the list either.
 */
describe('credentialSelector messages', () => {
  const LOCALES: Array<[string, Record<string, string>]> = [
    ['en', en.workflowBuilder.credentialSelector],
    ['fr', fr.workflowBuilder.credentialSelector],
    ['de', de.workflowBuilder.credentialSelector],
    ['es', es.workflowBuilder.credentialSelector],
    ['pt', pt.workflowBuilder.credentialSelector],
    ['zh', zh.workflowBuilder.credentialSelector],
  ];

  const COMPONENT_SOURCE = readFileSync(
    resolve(__dirname, '..', 'McpToolSelector.tsx'),
    'utf8',
  );
  // Every key the component names inside a tCredSelector(...) call, including both
  // arms of the ternary that picks the help line. Matching the whole argument list
  // rather than a leading literal is deliberate: the first version of this regex only
  // caught tCredSelector('x') and silently missed the ternary, which is precisely the
  // call this guard most needs to see. Both quote styles count, since a double-quoted
  // key missed here would be a key with no translation that this test reports as fine.
  const READ_BY_THE_COMPONENT = [
    ...new Set(
      Array.from(COMPONENT_SOURCE.matchAll(/tCredSelector\(([^)]*)\)/g)).flatMap((call) =>
        Array.from(call[1].matchAll(/['"]([^'"]+)['"]/g), (literal) => literal[1]),
      ),
    ),
  ];

  it('finds the keys by reading the component, so an empty list cannot pass silently', () => {
    // If the regex ever stops matching (the call is renamed, the quotes change), every
    // other case in this block would vacuously pass over an empty list.
    expect(READ_BY_THE_COMPONENT.length).toBeGreaterThanOrEqual(5);
  });

  it.each(LOCALES)('%s translates every key the control reads', (_locale, block) => {
    READ_BY_THE_COMPONENT.forEach((key) => {
      expect(typeof block[key]).toBe('string');
      expect(block[key].trim()).not.toBe('');
    });
  });

  it.each(LOCALES)('%s carries no key without a reader', (_locale, block) => {
    // usePicker/useExpression labelled the two states of the old button, and toggleHelp
    // was the single state-blind help line that replaced them. A switch whose help does
    // not change with its state reads as an instruction already followed, so both arms
    // exist now and the retired keys must not linger as translations nobody reads.
    expect(Object.keys(block).sort()).toEqual([...READ_BY_THE_COMPONENT].sort());
  });

  it('reads both arms of the state-dependent help, not just one', () => {
    // The ternary is the only place either arm is named; a refactor that collapses it
    // back to one string would silently lose the state-awareness.
    expect(READ_BY_THE_COMPONENT).toContain('toggleHelpOff');
    expect(READ_BY_THE_COMPONENT).toContain('toggleHelpOn');
  });

  it.each(LOCALES)('%s uses no em-dash or en-dash', (_locale, block) => {
    Object.values(block).forEach((value) => {
      expect(value).not.toMatch(/[--]/);
    });
  });
});
