/**
 * How long an epoch is said to have taken.
 *
 * The epoch list used to print `endedAt - startedAt`, and that is not a duration.
 * An epoch closes LATE: it closes when it is reconciled, which is the next fire, a
 * resume, or a restart recovery sweep, so the header span counts the idle tail. On
 * prod that rendered 32h42m and 6h01m next to epochs whose nodes really ran for 5 to
 * 35 seconds - the run-history column had the same bug one level up, and both are
 * fixed by taking the window the backend measures on the step rows instead.
 *
 * The rule lives here rather than inside the component because it is arithmetic
 * about which of two quantities to trust, and the component that renders it is
 * virtualized - proving the rule through the DOM would test react-window.
 */
import { describe, expect, it } from 'vitest';
import { epochDisplayDurationMs } from '@/components/workflow/run-panel/runFormatting';

const NOW = Date.parse('2026-08-02T12:00:30Z');

describe('epochDisplayDurationMs', () => {
  it('reports the measured execution window, not the header span', () => {
    // The exact prod shape: a closed epoch whose header spans 32h42m while its
    // nodes ran for 12 seconds.
    expect(epochDisplayDurationMs({
      startedAt: '2026-08-01T03:18:00Z',
      endedAt: '2026-08-02T12:00:00Z',
      workDurationMs: 12_000,
    }, NOW)).toBe(12_000);
  });

  it('keeps ticking on an open epoch instead of freezing at the settled window', () => {
    // An open epoch has not been closed by anything, so no idle time can have been
    // added after the fact and elapsed-since-start is the truth. Returning the
    // measured window here would show "4s" for an epoch running for an hour, and it
    // would only move when the next node finishes.
    expect(epochDisplayDurationMs({
      startedAt: '2026-08-02T11:00:00Z',
      endedAt: null,
      workDurationMs: 4_000,
    }, NOW)).toBe(3_630_000);
  });

  it('counts elapsed time for an open epoch that has not produced a step row yet', () => {
    // Nothing has finished, so there is no measured window. Elapsed-since-start is
    // the only honest thing to show, and it is what makes the row tick.
    expect(epochDisplayDurationMs({
      startedAt: '2026-08-02T12:00:00Z',
      endedAt: null,
    }, NOW)).toBe(30_000);
  });

  it('reports UNKNOWN, not zero, for a closed epoch with no measured window', () => {
    // Two things must not happen here. Falling back to the header span resurrects
    // the 32h42m. Returning 0 is worse in a subtler way: the timeline prints "<1s",
    // a confident claim that the epoch was instantaneous. Null lets callers show
    // nothing, which is what we actually know.
    //
    // This is not hypothetical. A marketplace showcase snapshot is frozen JSONB:
    // every application published before this field existed replays without it, and
    // nothing backfills those rows.
    expect(epochDisplayDurationMs({
      startedAt: '2026-08-01T03:18:00Z',
      endedAt: '2026-08-02T12:00:00Z',
    }, NOW)).toBeNull();
  });

  it('distinguishes a measured zero from a missing measurement', () => {
    // An all-skipped epoch really does start and end at the same instant, and "<1s"
    // is the right thing to print for it. A `|| null` style check would collapse the
    // two and hide a real measurement.
    const measuredZero = epochDisplayDurationMs({
      startedAt: '2026-08-01T03:18:00Z',
      endedAt: '2026-08-02T12:00:00Z',
      workDurationMs: 0,
    }, NOW);
    const notMeasured = epochDisplayDurationMs({
      startedAt: '2026-08-01T03:18:00Z',
      endedAt: '2026-08-02T12:00:00Z',
      workDurationMs: null,
    }, NOW);

    expect(measuredZero).toBe(0);
    expect(notMeasured).toBeNull();
  });

  it('never returns a negative duration', () => {
    // A browser clock behind the server puts startedAt in the future; "-30s" in the
    // column would look like a rendering bug rather than a clock difference.
    expect(epochDisplayDurationMs({
      startedAt: '2026-08-02T12:01:00Z',
      endedAt: null,
    }, NOW)).toBe(0);
  });

  it('falls back to the measured window when the clock puts the start in the future', () => {
    // Same skew, but this epoch HAS executed. Elapsed would be negative, so the
    // measured window is the only figure left and must not be discarded.
    expect(epochDisplayDurationMs({
      startedAt: '2026-08-02T12:01:00Z',
      endedAt: null,
      workDurationMs: 4_000,
    }, NOW)).toBe(4_000);
  });

  it('reports nothing when there is no start timestamp at all', () => {
    expect(epochDisplayDurationMs({ startedAt: '', endedAt: null }, NOW)).toBeNull();
  });

  it('reports nothing rather than NaN when the timestamp is unparseable', () => {
    // NaN would propagate into the waterfall bar width and blank the whole row.
    expect(epochDisplayDurationMs({ startedAt: 'not-a-date', endedAt: null }, NOW)).toBeNull();
    // ...and an unparseable start must not throw away a window we did measure.
    expect(epochDisplayDurationMs(
      { startedAt: 'not-a-date', endedAt: null, workDurationMs: 7_000 }, NOW)).toBe(7_000);
  });
});
