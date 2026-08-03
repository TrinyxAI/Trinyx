import { describe, it, expect } from 'vitest';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * Repo-wide strict parity guard for frontend/messages/*.json.
 *
 * AGENTS.md requires every key to exist in EVERY locale file, and requires verifying
 * that before committing. Nothing automated it: the four pre-existing parity tests each
 * narrow to one subtree (taskBoard, pricing.planCards, pricing.deployment,
 * workflowBuilder.canvas), covering at most ~371 of 8300+ keys.
 *
 * A gap here is invisible at runtime, which is exactly why it needs a test:
 * i18n/request.ts deep-merges en.json UNDER the active locale, so a missing key renders
 * plausible English instead of a loud MISSING_MESSAGE. The safety net is deliberate, but
 * it means a regression only ever surfaces as "why is this one line in English".
 *
 * This guard asserts the key SETS match. It deliberately does not assert that values
 * differ from English: many keys are legitimately identical across locales (brand names,
 * "API", "OK", punctuation-only strings), so a blanket check would be noise. Untranslated
 * clusters are tracked separately.
 */

type Messages = Record<string, unknown>;

const REFERENCE = 'en';
const LOCALES: Record<string, Messages> = { en, fr, de, es, pt, zh };
const TRANSLATED = Object.keys(LOCALES).filter((l) => l !== REFERENCE);

/** Flattens nested message objects to dotted leaf paths. */
function flatten(value: Messages, prefix = '', out: Record<string, unknown> = {}): Record<string, unknown> {
  for (const [key, child] of Object.entries(value)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (child && typeof child === 'object' && !Array.isArray(child)) {
      flatten(child as Messages, path, out);
    } else {
      out[path] = child;
    }
  }
  return out;
}

const flat = Object.fromEntries(
  Object.entries(LOCALES).map(([locale, messages]) => [locale, flatten(messages)]),
) as Record<string, Record<string, unknown>>;

const referenceKeys = Object.keys(flat[REFERENCE]).sort();

describe('i18n locale parity (all keys, all locales)', () => {
  it('the reference locale is non-empty, so the comparisons below cannot pass vacuously', () => {
    expect(referenceKeys.length).toBeGreaterThan(1000);
  });

  it.each(TRANSLATED)('%s.json defines every key en.json defines', (locale) => {
    const keys = new Set(Object.keys(flat[locale]));
    const missing = referenceKeys.filter((k) => !keys.has(k));
    expect(
      missing,
      `${locale}.json is missing ${missing.length} key(s); they would silently fall back to English. First 20: ${missing.slice(0, 20).join(', ')}`,
    ).toEqual([]);
  });

  it.each(TRANSLATED)('%s.json defines no key en.json does not', (locale) => {
    const extra = Object.keys(flat[locale]).filter((k) => !(k in flat[REFERENCE])).sort();
    expect(
      extra,
      `${locale}.json has ${extra.length} orphan key(s) no other locale carries, so they are unreachable. First 20: ${extra.slice(0, 20).join(', ')}`,
    ).toEqual([]);
  });

  it.each(TRANSLATED)('%s.json gives every key the same value TYPE as en.json', (locale) => {
    const mismatched = referenceKeys
      .filter((k) => k in flat[locale])
      .filter((k) => typeof flat[locale][k] !== typeof flat[REFERENCE][k])
      .map((k) => `${k} (en=${typeof flat[REFERENCE][k]}, ${locale}=${typeof flat[locale][k]})`);
    expect(mismatched, `type drift would break interpolation at runtime: ${mismatched.slice(0, 10).join('; ')}`).toEqual([]);
  });

  it.each(TRANSLATED)('%s.json declares the same ICU arguments as en.json for every key', (locale) => {
    // A locale that invents or drops an {argument} throws at format time rather than
    // degrading, so this is the one value-level check worth enforcing repo-wide.
    // Depth-aware: only braces at nesting level 0 introduce an argument. Inside a
    // plural/select, `one {Application}` is a branch BODY, not an argument, and zh
    // legitimately has no plural branches at all - a naive regex reports those as drift.
    const argsOf = (value: unknown): string => {
      if (typeof value !== 'string') return '';
      const args = new Set<string>();
      let depth = 0;
      for (let i = 0; i < value.length; i++) {
        const ch = value[i];
        if (ch === '{') {
          if (depth === 0) {
            const m = /^\{\s*([a-zA-Z0-9_]+)\s*[,}]/.exec(value.slice(i));
            if (m) args.add(m[1]);
          }
          depth++;
        } else if (ch === '}') {
          depth = Math.max(0, depth - 1);
        }
      }
      return [...args].sort().join(',');
    };
    const drifted = referenceKeys
      .filter((k) => k in flat[locale])
      .filter((k) => argsOf(flat[locale][k]) !== argsOf(flat[REFERENCE][k]))
      .map((k) => `${k} (en={${argsOf(flat[REFERENCE][k])}}, ${locale}={${argsOf(flat[locale][k])}})`);
    expect(drifted, `ICU argument drift: ${drifted.slice(0, 10).join('; ')}`).toEqual([]);
  });
});
