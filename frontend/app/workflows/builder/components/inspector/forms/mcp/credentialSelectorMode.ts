/**
 * The three states of a step's account choice, as the inspector reads and writes
 * them.
 *
 * <p>This is where the three states are PRODUCED, so it is extracted from the
 * component rather than left inline: rendering the whole inspector to assert a
 * mode swap needs a large, brittle harness, and the rules themselves are pure.
 *
 * <p>The states, and why each distinction is load-bearing:
 * - absent (null/undefined)  -> static. The step uses the account picked in the
 *   builder, or the account's default. Every workflow written before this feature
 *   is in this state, so it must stay indistinguishable from what it was.
 * - present but blank ('')   -> dynamic, not filled in. A deliberate gesture that
 *   is not finished. It FAILS the run rather than quietly using the default
 *   account, and the builder flags it, so it must survive a save and a reload.
 * - filled                   -> resolved at run time.
 *
 * The mode is therefore the PRESENCE of the key, never the emptiness of its text:
 * derived from the text, clearing the field to retype swapped the control back to
 * the picker mid-edit and dropped focus.
 */

export interface CredentialToolData {
  credentialSelector?: unknown;
  selectedCredentialId?: number | null;
  selectedCredentialName?: string | null;
  credentialSource?: string;
  platformCredentialId?: number | null;
  [key: string]: unknown;
}

/** True when this step decides its account at run time. */
export function isDynamicCredential(toolData: CredentialToolData | undefined): boolean {
  const raw = toolData?.credentialSelector;
  return raw !== null && raw !== undefined;
}

/**
 * The expression to show. Normalised to a string because an agent-built plan can
 * legitimately carry a number here (a credential id).
 */
export function credentialSelectorText(toolData: CredentialToolData | undefined): string {
  const raw = toolData?.credentialSelector;
  return raw === null || raw === undefined ? '' : String(raw);
}

/**
 * The toolData after the mode toggle is clicked.
 *
 * <p>Going dynamic clears BOTH answers the other mode could give: the account pin
 * and the pool choice. Keeping the pool pin looked kinder, but the exporter writes
 * only the selector when one is present, so the pin did not survive a save: the
 * author toggled back after a reload and found it gone with no message. Clearing
 * it at the toggle makes the loss visible at the moment it is caused, which is the
 * only honest option while a plan cannot hold both answers.
 *
 * <p>Going static writes null, not an empty string: the key must be ABSENT to mean
 * static, or the control never leaves dynamic mode.
 */
export function toggleCredentialMode(toolData: CredentialToolData | undefined): CredentialToolData {
  const existing = toolData ?? {};
  const goingDynamic = !isDynamicCredential(existing);
  if (!goingDynamic) {
    return { ...existing, credentialSelector: null };
  }
  return {
    ...existing,
    // Empty, not a guessed template: seeding an expression that is wrong for most
    // workflows invites saving it unread, and the run then fails on a value nobody
    // chose. Blank is honest, is the documented third state, and is flagged.
    credentialSelector: '',
    selectedCredentialId: null,
    selectedCredentialName: null,
    credentialSource: undefined,
    platformCredentialId: null,
  };
}
