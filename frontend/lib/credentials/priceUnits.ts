/**
 * The price units a published rate can be charged per, and how to name one in
 * the reader's language.
 *
 * <p>Lives here rather than beside the workflow inspector that first needed it:
 * two surfaces now print a price (the inspector and the generation modal), and
 * reaching into an 800-line client component for a four-line pure helper drags
 * that component's whole tree, a credential wizard included, into the other
 * one's bundle, across the chat and workflow-builder boundary.
 *
 * <p>The key path is stated ONCE, here. It was previously split between the
 * helper (which prepended `source.`) and each caller's namespace, so a caller
 * that translated under `credentials.source` produced the lookup
 * `credentials.source.source.priceUnits.second`, which resolves to nothing and
 * printed the key path itself on screen, in every locale.
 */

/**
 * Units the backend quote can report. Anything else is an unknown unit and is
 * shown verbatim rather than mistranslated.
 */
export const KNOWN_PRICE_UNITS = ['call', 'second', 'minute', 'image', 'character'] as const;

/**
 * Localised name of a price unit, falling back to the raw value.
 *
 * @param t a translator bound to the `credentials` namespace, which is where
 *          the dictionary lives. Passing one bound any deeper double-prefixes
 *          the lookup, and the failure is silent: next-intl returns the key.
 */
export function priceUnitLabel(unit: string, t: (key: string) => string): string {
  return (KNOWN_PRICE_UNITS as readonly string[]).includes(unit)
    ? t(`source.priceUnits.${unit}`)
    : unit;
}
