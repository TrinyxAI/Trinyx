import { describe, expect, it } from 'vitest';
import {
  epochDisplayDurationMs,
  isEpochLive,
  resolveEpochBadgeStatus,
} from '@/components/workflow/run-panel/runFormatting';

/**
 * Which status one epoch row badges, and whether its duration is still counting.
 *
 * The trap both answers share: an epoch's header stays OPEN long after its last node
 * finished, because the close is deferred to the next fire / a resume / a recovery
 * sweep. So `endedAt == null` means "not reconciled yet", NOT "still running". Reading
 * it as "running" is what painted a settled epoch with a permanent blue pulse beside a
 * duration that counted up forever; reading the presence of `endedAt` as "completed" is
 * what made a FAILED epoch announce success.
 */
describe('resolveEpochBadgeStatus', () => {
  const closed = { endedAt: '2026-08-02T09:00:30Z' };
  const open = { endedAt: null };

  it('shows a closed epoch its own outcome, not the run status', () => {
    // The run is already executing the NEXT epoch. A closed epoch's verdict is final.
    expect(resolveEpochBadgeStatus({ ...closed, status: 'FAILED' }, 'running')).toBe('FAILED');
    expect(resolveEpochBadgeStatus({ ...closed, status: 'COMPLETED' }, 'running')).toBe('COMPLETED');
  });

  it('does not call a failed epoch completed just because it ended', () => {
    expect(resolveEpochBadgeStatus({ ...closed, status: 'FAILED' }, 'completed')).toBe('FAILED');
  });

  it('shows RUNNING for an open epoch while the run is executing', () => {
    for (const runStatus of ['running', 'RUNNING', 'paused', 'awaiting_signal', 'pending']) {
      expect(resolveEpochBadgeStatus({ ...open, status: 'COMPLETED' }, runStatus)).toBe('RUNNING');
    }
  });

  it('does not claim RUNNING for an unclosed epoch of a run parked between fires', () => {
    // A reusable-trigger run at WAITING_TRIGGER is executing nothing, whatever its last
    // epoch's header says - the close is what is late, not the work. No badge rather
    // than a live pulse that never stops.
    expect(resolveEpochBadgeStatus({ ...open, status: null }, 'waiting_trigger')).toBeNull();
    // The backend attaches no outcome to an open epoch (its stored state is the one
    // written when it opened), so this fallback is defensive - if one ever arrives, it
    // is shown rather than dropped.
    expect(resolveEpochBadgeStatus({ ...open, status: 'FAILED' }, 'waiting_trigger')).toBe('FAILED');
  });

  it('does not read an unrecognised run status as "executing"', () => {
    // A value from a newer backend, or another surface's vocabulary. Treating "not
    // terminal" as "running" would put a live pulse on a settled epoch and restart its
    // duration - the opposite of the bug this whole helper exists to prevent.
    expect(isEpochLive({ ...open, status: null }, 'zzz_unknown')).toBe(false);
    expect(resolveEpochBadgeStatus({ ...open, status: null }, 'zzz_unknown')).toBeNull();
    expect(resolveEpochBadgeStatus({ ...closed, status: 'COMPLETED' }, 'zzz_unknown')).toBe('COMPLETED');
  });

  it('lets a cancelled, stopped or timed-out run override its abandoned epoch', () => {
    // The epoch's tally says work completed, but the run was killed mid-flight: it never
    // reached the ending "completed" would claim.
    expect(resolveEpochBadgeStatus({ ...open, status: 'COMPLETED' }, 'cancelled')).toBe('CANCELLED');
    expect(resolveEpochBadgeStatus({ ...open, status: 'COMPLETED' }, 'timeout')).toBe('TIMEOUT');
    expect(resolveEpochBadgeStatus({ ...open, status: 'COMPLETED' }, 'stopped')).toBe('STOPPED');
    // A CLOSED epoch of a cancelled run keeps its own outcome: it finished before the
    // cancel landed.
    expect(resolveEpochBadgeStatus({ ...closed, status: 'COMPLETED' }, 'cancelled')).toBe('COMPLETED');
  });

  it('badges nothing when the payload carries no outcome', () => {
    // Two real cases: an epoch that ran nothing but its trigger (the backend sends no
    // status on purpose), and a showcase snapshot frozen before the field existed.
    // Inventing a status here would badge an idle run as a success.
    expect(resolveEpochBadgeStatus({ ...closed, status: null }, 'waiting_trigger')).toBeNull();
    expect(resolveEpochBadgeStatus({ ...closed }, 'completed')).toBeNull();
    expect(resolveEpochBadgeStatus({ ...open, status: undefined }, 'waiting_trigger')).toBeNull();
    expect(resolveEpochBadgeStatus(null, 'running')).toBeNull();
  });

  it('survives a missing run status without claiming RUNNING', () => {
    // A surface that has no run status to offer. The epochs it renders are settled ones,
    // and they keep their verdict; an open one gets no badge rather than a live pulse.
    expect(resolveEpochBadgeStatus({ ...closed, status: 'FAILED' }, undefined)).toBe('FAILED');
    expect(resolveEpochBadgeStatus({ ...closed, status: 'COMPLETED' }, '')).toBe('COMPLETED');
    expect(isEpochLive({ ...open, status: null }, undefined)).toBe(false);
  });

  it('normalizes the outcome to upper case whatever the backend sent', () => {
    expect(resolveEpochBadgeStatus({ ...closed, status: 'completed' }, 'running')).toBe('COMPLETED');
  });
});

describe('isEpochLive + epochDisplayDurationMs', () => {
  const startedAt = '2026-08-02T09:00:00Z';
  const NOW = Date.parse('2026-08-02T11:05:00Z'); // 2h05m after the start

  it('stops the clock on the unclosed epoch of a run that will never resume it', () => {
    // The regression this pins: the duration read "2h05m and rising" for an epoch whose
    // nodes ran for 12 seconds, because nothing closed its header. A stopped or
    // cancelled run is the reachable case - it abandons whatever epoch was open.
    const entry = { startedAt, endedAt: null, status: null, workDurationMs: 12_000 };

    for (const runStatus of ['stopped', 'cancelled', 'timeout', 'waiting_trigger']) {
      expect(isEpochLive(entry, runStatus), runStatus).toBe(false);
      expect(epochDisplayDurationMs(entry, NOW, false), runStatus).toBe(12_000);
    }
  });

  it('keeps counting while the run really is executing', () => {
    // An epoch three minutes into an approval is not a twelve-second epoch.
    const entry = { startedAt, endedAt: null, status: null, workDurationMs: 12_000 };

    expect(isEpochLive(entry, 'running')).toBe(true);
    expect(epochDisplayDurationMs(entry, NOW, true)).toBe(NOW - Date.parse(startedAt));
  });

  it('reports nothing rather than zero for an unmeasured settled epoch', () => {
    // A showcase snapshot frozen before workDurationMs existed. Zero would print "<1s".
    const entry = { startedAt, endedAt: null, status: 'COMPLETED' };

    expect(epochDisplayDurationMs(entry, NOW, false)).toBeNull();
  });

  it('defaults to the open-means-live reading when no run status is available', () => {
    // Back-compat for callers that cannot supply one; the default must not silently
    // change what they render.
    const entry = { startedAt, endedAt: null, workDurationMs: 12_000 };

    expect(epochDisplayDurationMs(entry, NOW)).toBe(NOW - Date.parse(startedAt));
  });
});
