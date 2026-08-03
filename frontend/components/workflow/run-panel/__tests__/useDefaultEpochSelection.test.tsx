/**
 * @vitest-environment jsdom
 *
 * Opening a run shows ALL of its epochs. These tests pin the three rules that
 * make that true:
 *   - the run surfaces never select an epoch on the user's behalf,
 *   - an EXPLICIT pick survives the remount of a surface (the canvas and the
 *     side-panel Run tab are separate React trees, and `viewingEpoch` dies with
 *     its provider),
 *   - a deliberate return to all epochs sticks instead of being undone by that
 *     same restore.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, renderHook } from '@testing-library/react';
import {
  getPickedEpoch,
  markEpochPickedByUser,
  resetEpochSelectionState,
  selectAllEpochs,
  useDefaultEpochSelection,
} from '@/components/workflow/run-panel/useDefaultEpochSelection';

beforeEach(() => resetEpochSelectionState());
afterEach(() => { resetEpochSelectionState(); cleanup(); });

describe('useDefaultEpochSelection', () => {
  it('leaves a freshly opened run on "All epochs"', () => {
    // The regression this guards: a run that selects an epoch for the user hides
    // every other fire behind a selector nobody touched.
    const onSelectEpoch = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-1',
      selectedEpoch: null,
      onSelectEpoch,
    }));
    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('leaves it there while the run keeps firing', () => {
    // Nothing follows the newest epoch: re-rendering as fires land must not
    // move a user who never chose an epoch.
    const onSelectEpoch = vi.fn();
    const { rerender } = renderHook(
      ({ runId }) => useDefaultEpochSelection({
        runId,
        selectedEpoch: null,
        onSelectEpoch,
      }),
      { initialProps: { runId: 'run-1' } },
    );

    rerender({ runId: 'run-1' });
    rerender({ runId: 'run-1' });

    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('never moves a surface that already shows an epoch, even to the picked one', () => {
    // Discriminating case: the pick (1) differs from what this surface shows
    // (3). "Restore" applies to an EMPTY surface only, never as a correction.
    markEpochPickedByUser('run-1', 1);
    const onSelectEpoch = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-1',
      selectedEpoch: 3,
      onSelectEpoch,
    }));
    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('restores the picked epoch on a surface that mounts with nothing selected', () => {
    markEpochPickedByUser('run-1', 1);
    const onSelectEpoch = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-1',
      selectedEpoch: null,
      onSelectEpoch,
    }));
    expect(onSelectEpoch).toHaveBeenCalledWith(1);
  });

  it('restores nothing when the pick WAS "All epochs"', () => {
    // A null pick is the default view: there is nothing to re-apply.
    markEpochPickedByUser('run-1', null);
    const onSelectEpoch = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-1',
      selectedEpoch: null,
      onSelectEpoch,
    }));
    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('keeps each run independent - a pick on one run is not applied to another', () => {
    markEpochPickedByUser('run-1', 2);
    const onSelectEpoch = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-2',
      selectedEpoch: null,
      onSelectEpoch,
    }));
    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('gives the pick back when the user returns to that run', () => {
    // The continuity the remembered pick exists for: leave a run for another one
    // and come back, and you are where you left off.
    markEpochPickedByUser('run-1', 2);
    const onSelectEpoch = vi.fn();
    const { rerender } = renderHook(
      ({ runId, selected }) => useDefaultEpochSelection({
        runId,
        selectedEpoch: selected,
        onSelectEpoch,
      }),
      { initialProps: { runId: 'run-1', selected: 2 as number | null } },
    );

    rerender({ runId: 'run-2', selected: null });
    expect(onSelectEpoch, 'the other run opens on all epochs').not.toHaveBeenCalled();

    rerender({ runId: 'run-1', selected: null });
    expect(onSelectEpoch).toHaveBeenCalledWith(2);
  });

  it('opens another run on "All epochs" even when the previous one was pinned', () => {
    markEpochPickedByUser('run-1', 2);
    const onSelectEpoch = vi.fn();
    const { rerender } = renderHook(
      ({ runId, selected }) => useDefaultEpochSelection({
        runId,
        selectedEpoch: selected,
        onSelectEpoch,
      }),
      { initialProps: { runId: 'run-1', selected: 2 as number | null } },
    );

    rerender({ runId: 'run-2', selected: null });

    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('does nothing when disabled (edit mode) or with no run bound', () => {
    markEpochPickedByUser('run-1', 1);
    const disabled = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-1',
      selectedEpoch: null,
      onSelectEpoch: disabled,
      enabled: false,
    }));
    expect(disabled).not.toHaveBeenCalled();

    const noRun = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: null,
      selectedEpoch: null,
      onSelectEpoch: noRun,
    }));
    expect(noRun).not.toHaveBeenCalled();
  });
});

describe('selectAllEpochs', () => {
  it('clears the selection AND records it, so the restore cannot undo it', () => {
    // The bug this closes: firing a trigger from a focused epoch cleared the
    // selection, the surface then reported "nothing selected", and the restore
    // put the user straight back on the epoch they had just left.
    markEpochPickedByUser('run-1', 2);
    const onSelectEpoch = vi.fn();

    selectAllEpochs('run-1', onSelectEpoch);

    expect(onSelectEpoch).toHaveBeenCalledWith(null);
    expect(getPickedEpoch('run-1')).toBeNull();

    const afterClear = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-1',
      selectedEpoch: null,
      onSelectEpoch: afterClear,
    }));
    expect(afterClear).not.toHaveBeenCalled();
  });

  it('still clears the selection when no run is bound', () => {
    const onSelectEpoch = vi.fn();
    selectAllEpochs(null, onSelectEpoch);
    expect(onSelectEpoch).toHaveBeenCalledWith(null);
  });
});

describe('the remembered picks', () => {
  it('keep the run on screen when the tracked-run budget overflows', () => {
    // Re-picking must refresh recency, or eviction drops the run the user is
    // looking at while keeping ids nobody has touched for a hundred runs.
    markEpochPickedByUser('run-hot', 2);
    for (let i = 0; i < 49; i += 1) markEpochPickedByUser(`run-${i}`, 1);
    markEpochPickedByUser('run-hot', 2);
    for (let i = 50; i < 60; i += 1) markEpochPickedByUser(`run-${i}`, 1);

    expect(getPickedEpoch('run-hot')).toBe(2);
    // The oldest ids are the ones evicted, and a forgotten run simply reverts
    // to the default view.
    expect(getPickedEpoch('run-0')).toBeUndefined();
  });
});
