/**
 * @vitest-environment jsdom
 *
 * A run is always read THROUGH an epoch: the run surfaces must never sit on
 * "nothing selected". These tests pin the three rules that make that true -
 * seed with the newest epoch, follow newer epochs while the run is live, and
 * stop following the moment the user picks an epoch themselves (including the
 * "All epochs" null pick, which a naive implementation immediately overwrites).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, renderHook } from '@testing-library/react';
import {
  isEpochAutoFollowing,
  markEpochPickedByUser,
  resetEpochSelectionState,
  useDefaultEpochSelection,
} from '@/components/workflow/run-panel/useDefaultEpochSelection';
import type { EpochTimestamp } from '@/components/workflow/run-panel/runFormatting';

const epochs = (...nums: number[]): EpochTimestamp[] =>
  nums.map(n => ({ epoch: n, startedAt: `2026-07-31T10:0${n}:00Z`, endedAt: null }));

beforeEach(() => resetEpochSelectionState());
afterEach(() => { resetEpochSelectionState(); cleanup(); });

describe('useDefaultEpochSelection', () => {
  it('selects the most recent epoch when nothing is selected yet', () => {
    const onSelectEpoch = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-1',
      epochTimestamps: epochs(1, 2, 3),
      selectedEpoch: null,
      onSelectEpoch,
    }));
    expect(onSelectEpoch).toHaveBeenCalledWith(3);
  });

  it('follows a newly opened epoch while the user has not picked one', () => {
    const onSelectEpoch = vi.fn();
    const { rerender } = renderHook(
      ({ list, selected }) => useDefaultEpochSelection({
        runId: 'run-1',
        epochTimestamps: list,
        selectedEpoch: selected,
        onSelectEpoch,
      }),
      { initialProps: { list: epochs(1, 2), selected: 2 as number | null } },
    );
    expect(onSelectEpoch).not.toHaveBeenCalled();

    rerender({ list: epochs(1, 2, 3), selected: 2 });
    expect(onSelectEpoch).toHaveBeenCalledWith(3);
  });

  it('stops following once the user picks an epoch explicitly', () => {
    const onSelectEpoch = vi.fn();
    markEpochPickedByUser('run-1');
    const { rerender } = renderHook(
      ({ list, selected }) => useDefaultEpochSelection({
        runId: 'run-1',
        epochTimestamps: list,
        selectedEpoch: selected,
        onSelectEpoch,
      }),
      { initialProps: { list: epochs(1, 2), selected: 1 as number | null } },
    );
    rerender({ list: epochs(1, 2, 3), selected: 1 });
    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('never overwrites an explicit "All epochs" (null) pick', () => {
    const onSelectEpoch = vi.fn();
    markEpochPickedByUser('run-1');
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-1',
      epochTimestamps: epochs(1, 2, 3),
      selectedEpoch: null,
      onSelectEpoch,
    }));
    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('restores the picked epoch on a surface that mounts with nothing selected', () => {
    // `viewingEpoch` dies with its provider, so the side panel remounts empty
    // after being closed. Remembering only "they picked" left it on neither the
    // pick nor the latest epoch - the one state a run surface must never be in.
    markEpochPickedByUser('run-1', 1);
    const onSelectEpoch = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-1',
      epochTimestamps: epochs(1, 2, 3),
      selectedEpoch: null,
      onSelectEpoch,
    }));
    expect(onSelectEpoch).toHaveBeenCalledWith(1);
  });

  it('restores nothing when the pick WAS "All epochs"', () => {
    // A null pick is a real choice; re-seeding an epoch would undo it.
    markEpochPickedByUser('run-1', null);
    const onSelectEpoch = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-1',
      epochTimestamps: epochs(1, 2, 3),
      selectedEpoch: null,
      onSelectEpoch,
    }));
    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('keeps the run on screen when the tracked-run budget overflows', () => {
    // Re-picking must refresh recency, or eviction drops the run the user is
    // looking at while keeping ids nobody has touched for a hundred runs.
    markEpochPickedByUser('run-hot', 2);
    for (let i = 0; i < 49; i += 1) markEpochPickedByUser(`run-${i}`, 1);
    markEpochPickedByUser('run-hot', 2);
    for (let i = 50; i < 60; i += 1) markEpochPickedByUser(`run-${i}`, 1);

    expect(isEpochAutoFollowing('run-hot')).toBe(false);
  });

  it('keeps each run independent - a pick on one run does not freeze another', () => {
    markEpochPickedByUser('run-1');
    const onSelectEpoch = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-2',
      epochTimestamps: epochs(1, 2),
      selectedEpoch: null,
      onSelectEpoch,
    }));
    expect(onSelectEpoch).toHaveBeenCalledWith(2);
  });

  it('does NOT follow a new epoch when parked on an older one', () => {
    // The discriminating half of the follow rule: following from anywhere would
    // drag the user off the epoch they were reading the moment a new fire lands.
    const onSelectEpoch = vi.fn();
    const { rerender } = renderHook(
      ({ list, selected }) => useDefaultEpochSelection({
        runId: 'run-1',
        epochTimestamps: list,
        selectedEpoch: selected,
        onSelectEpoch,
      }),
      { initialProps: { list: epochs(1, 2, 3), selected: 1 as number | null } },
    );

    rerender({ list: epochs(1, 2, 3, 4), selected: 1 });

    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('starts over when the panel switches to another run', () => {
    const onSelectEpoch = vi.fn();
    const { rerender } = renderHook(
      ({ runId, list, selected }) => useDefaultEpochSelection({
        runId,
        epochTimestamps: list,
        selectedEpoch: selected,
        onSelectEpoch,
      }),
      { initialProps: { runId: 'run-1', list: epochs(1, 2), selected: 2 as number | null } },
    );
    expect(onSelectEpoch).not.toHaveBeenCalled();

    // Another run: its own epochs, its own choice - and nothing selected yet.
    rerender({ runId: 'run-2', list: epochs(1, 2, 3), selected: null });

    expect(onSelectEpoch).toHaveBeenCalledWith(3);
  });

  it('does nothing when disabled (edit mode) or when the run has no epoch', () => {
    const disabled = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-1',
      epochTimestamps: epochs(1, 2),
      selectedEpoch: null,
      onSelectEpoch: disabled,
      enabled: false,
    }));
    expect(disabled).not.toHaveBeenCalled();

    const noEpochs = vi.fn();
    renderHook(() => useDefaultEpochSelection({
      runId: 'run-3',
      epochTimestamps: [],
      selectedEpoch: null,
      onSelectEpoch: noEpochs,
    }));
    expect(noEpochs).not.toHaveBeenCalled();
  });
});
