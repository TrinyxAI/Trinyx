/**
 * "Every multi-step header looks the same" is a property of EIGHT files, so no
 * test that renders one of them can hold it. And copies are exactly how this
 * broke: the header was extracted into ModalStepIndicator after existing six
 * times character for character, and two more copies survived the extraction.
 *
 * They drifted the moment they were left alone. The generation modal's copy had
 * no completed state at ALL - a step you had finished looked like one you had
 * not reached, with no green and no check - and the publication-review copy,
 * whose comment still said "same pattern as ShareWorkflowModal", drew its steps
 * on the label rung while the pattern it named had moved to the Button rung.
 *
 * So this is the source-level guard: the header is DEFINED once, and every
 * surface that shows steps takes it from there. The rendered shape and states
 * are asserted in ModalStepIndicator.test.tsx.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

const FRONTEND = join(__dirname, '../../..');

/** Where the step header is allowed to be written. */
const THE_ONE_DEFINITION = join('components', 'ui', 'ModalStepIndicator.tsx');

/**
 * What a step header looks like in source: a row built by mapping steps, a
 * branch on whether one is finished, and the pill padding all of them used.
 *
 * Deliberately NOT keyed on `aria-current="step"` alone - the publication-review
 * copy never had it, so that signature would have declared the app clean while
 * two copies were live. A copy is recognised by what it DRAWS.
 */
const STEP_HEADER = (source: string) =>
  /steps?\.map\(/i.test(source)
  && /isCompleted|isDone/.test(source)
  && /px-3 py-1\.5/.test(source);

/** Every surface that shows a multi-step header, and must not re-draw one. */
const CALL_SITES = [
  ['the agent modal', join('components', 'chat', 'CreateAgentModal.tsx')],
  ['the data-source modal', join('components', 'chat', 'CreateDataSourceModal.tsx')],
  ['the interface modal', join('components', 'chat', 'CreateInterfaceModal.tsx')],
  ['the generation modal', join('components', 'chat', 'CreateGenerationModal.tsx')],
  ['the add-column modal', join('components', 'data-table', 'modals', 'AddColumnModal.tsx')],
  ['the project modal', join('components', 'project', 'ProjectMultiStepModal.tsx')],
  ['the share-workflow modal', join('components', 'workflow', 'ShareWorkflowModal.tsx')],
  [
    'the publication review',
    join('app', '[locale]', 'app', 'settings', 'publication-review', 'components', 'PublicationComparisonView.tsx'),
  ],
] as const;

function tsxFilesUnder(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === '.next' || entry === '__tests__') continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) tsxFilesUnder(full, out);
    else if (entry.endsWith('.tsx')) out.push(full);
  }
  return out;
}

describe('the multi-step header lives in one place', () => {
  it('is defined once, and nowhere else in the app', () => {
    const sources = [
      ...tsxFilesUnder(join(FRONTEND, 'components')),
      ...tsxFilesUnder(join(FRONTEND, 'app')),
    ];

    const definitions = sources
      .filter((file) => STEP_HEADER(readFileSync(file, 'utf8')))
      .map((file) => relative(FRONTEND, file));

    expect(definitions).toEqual([THE_ONE_DEFINITION.split(sep).join(sep)]);
  });

  for (const [what, file] of CALL_SITES) {
    const source = readFileSync(join(FRONTEND, file), 'utf8');

    it(`${what} takes its steps from the shared header`, () => {
      expect(source).toContain('ModalStepIndicator');
      expect(source).toMatch(/from ['"]@\/components\/ui\/ModalStepIndicator['"]/);
    });

    it(`${what} does not hand-write the step pill`, () => {
      // The exact spelling every copy used for a step: an icon, a label, and a
      // ternary on "is this one done".
      expect(source).not.toMatch(/px-3 py-1\.5 rounded-(md|full|2xl)/);
    });
  }
});
