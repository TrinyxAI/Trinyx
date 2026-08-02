/**
 * The run badge on the canvas pill and in the panel header goes through
 * `getRunStatusLabel`, which falls back to the RAW lowercase status for anything
 * it does not know. Two statuses were missing from that set, so a run blocked on
 * an approval showed the badge text `awaiting_signal` while the history row two
 * inches away correctly said "Awaiting signal".
 *
 * The statuses are listed here explicitly, transcribed from the backend enum:
 * deriving them from the implementation's own set is what let the gap exist.
 */
import { describe, expect, it } from 'vitest';
import { getRunStatusLabel, getStatusClasses } from '@/lib/utils/runStatusUtils';
import messages from '@/messages/en.json';

const BACKEND_STATUSES = [
  'PENDING', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'PARTIAL_SUCCESS',
  'SKIPPED', 'CANCELLED', 'TIMEOUT', 'WAITING_TRIGGER', 'AWAITING_SIGNAL',
  // Not in the enum, but the streaming layer still emits it for a stopped run.
  'STOPPED',
];

/** Stand-in for the next-intl root translator the components pass in. */
const translate = (key: string) => `t:${key}`;

describe('getRunStatusLabel', () => {
  it('translates every status the backend can report', () => {
    for (const status of BACKEND_STATUSES) {
      expect(getRunStatusLabel(status, translate), `${status} rendered raw`)
        .toBe(`t:status.${status.toLowerCase()}`);
    }
  });

  it('has a real message behind each of those keys', () => {
    const statusMessages = (messages as unknown as { status: Record<string, string> }).status;
    for (const status of BACKEND_STATUSES) {
      expect(statusMessages[status.toLowerCase()], `status.${status.toLowerCase()} is missing`).toBeTruthy();
    }
  });

  it('still degrades to the raw value for something nobody knows', () => {
    // Better a lowercase word than a missing-key placeholder.
    expect(getRunStatusLabel('WAT', translate)).toBe('wat');
  });
});

describe('getStatusClasses', () => {
  it('gives a blocked run its own colour, not the idle-yellow default', () => {
    const awaiting = getStatusClasses('AWAITING_SIGNAL');
    expect(awaiting).toContain('violet');
    expect(awaiting).not.toBe(getStatusClasses('WAT'));
  });

  it('treats a stopped run like a cancelled one', () => {
    expect(getStatusClasses('STOPPED')).toBe(getStatusClasses('CANCELLED'));
  });
});
