/**
 * "The user picked this epoch" is a module-global flag keyed by run id, shared by
 * every surface showing that run - the canvas, the side-panel Run tab and the
 * application tab. Setting it turns OFF the follow-the-newest-epoch behaviour for
 * all of them, permanently.
 *
 * So it must be set by a CLICK and nothing else. The application tab calls its
 * epoch handler from three places, two of them automatic (seed the latest epoch
 * on mount, jump when a new epoch closes); marking from that shared handler let a
 * panel merely being rendered count as a choice, and froze the run's epoch
 * everywhere.
 */
import { afterEach, describe, expect, it } from 'vitest';
import {
  getPickedEpoch,
  isEpochAutoFollowing,
  markEpochPickedByUser,
  resetEpochSelectionState,
} from '@/components/workflow/run-panel/useDefaultEpochSelection';

afterEach(() => resetEpochSelectionState());

describe('epoch pick attribution', () => {
  it('a run nobody has touched keeps following the newest epoch', () => {
    expect(isEpochAutoFollowing('run-1')).toBe(true);
    expect(getPickedEpoch('run-1')).toBeUndefined();
  });

  it('a pick stops the follow for that run and records WHICH epoch', () => {
    markEpochPickedByUser('run-1', 3);
    expect(isEpochAutoFollowing('run-1')).toBe(false);
    expect(getPickedEpoch('run-1')).toBe(3);
  });

  it('the flag reaches every surface of the run, which is why only a click may set it', () => {
    // The point of the global: the canvas and the panel must not fight. The cost:
    // one surface marking automatically silences the others too.
    markEpochPickedByUser('run-1', 2);
    expect(isEpochAutoFollowing('run-1')).toBe(false);
    // ...and never leaks to a different run.
    expect(isEpochAutoFollowing('run-2')).toBe(true);
  });
});

describe('the application tab marks picks only on a click', () => {
  it('routes the dropdown through the marking handler and nothing else through it', async () => {
    // A structural assertion, deliberately: the failure mode is a CALL SITE, not
    // a value. `handleViewEpoch` is invoked by two effects (mount seeding, new
    // epoch auto-jump) and by the dropdown; only the dropdown may mark.
    const source = await import('fs').then(fs =>
      fs.readFileSync('components/chat/ApplicationTabContent.tsx', 'utf8'));

    const markCalls = source.match(/markEpochPickedByUser\(/g) ?? [];
    expect(markCalls, 'exactly one call site - the click handler').toHaveLength(1);

    // It lives in the click-only wrapper, not in the shared handler the effects use.
    const wrapper = source.slice(source.indexOf('const handleEpochPickedByUser'));
    expect(wrapper.slice(0, 300)).toContain('markEpochPickedByUser(runId, epoch)');

    const sharedHandler = source.slice(
      source.indexOf('const handleViewEpoch'),
      source.indexOf('const handleEpochPickedByUser'),
    );
    expect(sharedHandler, 'the shared handler must stay attribution-free').not.toContain('markEpochPickedByUser');
  });
});
