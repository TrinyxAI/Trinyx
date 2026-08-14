// @vitest-environment jsdom
/**
 * Tests the interstitial a deactivated account sees when it signs back in.
 *
 * The account is blocked at the gateway but its identity stays enabled, so the person CAN
 * get in during the grace period, into an app where nothing loads. This screen is the only
 * thing that reaches POST /users/profile/restore, so the branches that matter are: it
 * appears at all, it names the real deadline, it does not stampede the status endpoint
 * when every blocked call re-fires the event, and a failed restore does not pretend to
 * have worked.
 */
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup, act, waitFor, fireEvent } from '@testing-library/react';

const mocks = vi.hoisted(() => ({
  getAccountDeletionStatus: vi.fn(),
  restoreAccount: vi.fn(),
  logout: vi.fn(),
}));

// Keys are echoed with their params so a test can assert WHICH copy rendered and that the
// deadline was actually interpolated, not just that some text appeared.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params ? `${key}|${JSON.stringify(params)}` : key,
}));

vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: {
    getAccountDeletionStatus: mocks.getAccountDeletionStatus,
    restoreAccount: mocks.restoreAccount,
  },
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ logout: mocks.logout }),
}));

import AccountRestoreModal from '../AccountRestoreModal';
import {
  ACCOUNT_INACTIVE_EVENT,
  ApiClient,
  clearBlockedCallLatch,
} from '@/lib/api/api-client';

const SCHEDULED = {
  scheduledForDeletion: true,
  deactivatedAt: '2026-08-09T10:00:00',
  deletionAt: '2026-09-08T10:00:00',
  gracePeriodDays: 30,
};

const NOT_SCHEDULED = {
  scheduledForDeletion: false,
  deactivatedAt: null,
  deletionAt: null,
  gracePeriodDays: 30,
};

function fireBlockedCall() {
  act(() => {
    window.dispatchEvent(new CustomEvent(ACCOUNT_INACTIVE_EVENT));
  });
}

describe('AccountRestoreModal', () => {
  let reload: ReturnType<typeof vi.fn>;

  let originalLocation: Location;

  beforeEach(() => {
    clearBlockedCallLatch();
    originalLocation = window.location;
    reload = vi.fn();
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...window.location, reload },
    });
    mocks.getAccountDeletionStatus.mockResolvedValue(SCHEDULED);
    mocks.restoreAccount.mockResolvedValue({ ...SCHEDULED, restored: true });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    clearBlockedCallLatch();
    Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
  });

  it('stays out of the way until a call is actually blocked', () => {
    render(<AccountRestoreModal />);

    // The dialog itself must be absent, not merely a particular string: asserting on copy alone
    // let the modal default to open (it renders the "blocked" heading before the status loads)
    // without a single test failing, which would pop this screen for every healthy user.
    expect(screen.queryByRole('dialog')).toBeNull();
    // No probe on load: everyone else must not pay for this screen.
    expect(mocks.getAccountDeletionStatus).not.toHaveBeenCalled();
  });

  it('opens for a block that happened before it was mounted', async () => {
    // On /app/* the first refused call is issued while this component is still behind
    // FirstLoginGuard's spinner, so the event reaches no listener. If nothing else is refused
    // afterwards, waiting for another event would leave the person stranded for good.
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 429,
      statusText: 'Too Many Requests',
      headers: new Headers({ 'content-type': 'application/json' }),
      json: () => Promise.resolve({ error: 'Quota exceeded', message: 'Inactive account' }),
      text: () => Promise.resolve(''),
    } as unknown as Response);
    const previousFetch = global.fetch;
    global.fetch = fetchMock as unknown as typeof fetch;
    const client = new ApiClient({ baseUrl: '/api/proxy', timeout: 5000, retries: 1 });
    await client.get('/anything', { skipAuth: true }).catch(() => undefined);
    global.fetch = previousFetch;

    render(<AccountRestoreModal />);

    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument());
    await waitFor(() => expect(mocks.getAccountDeletionStatus).toHaveBeenCalledTimes(1));
  });

  it('opens on a blocked call and names the deadline the purge will act on', async () => {
    render(<AccountRestoreModal />);
    fireBlockedCall();

    await waitFor(() => expect(screen.getByText(/^title/)).toBeInTheDocument());
    const description = await screen.findByText(/^description\|/);
    // The DELETION date (08 Sep), not the deactivation date (09 Aug). Asserting merely that a
    // date appeared let `deletionAt` be swapped for `deactivatedAt` without a single test
    // failing, which would tell someone their account dies on a day already in the past.
    expect(description.textContent).toContain('Sep');
    expect(description.textContent).not.toContain('Aug');
  });

  it('reads the status once even though every blocked call re-fires the event', async () => {
    render(<AccountRestoreModal />);

    fireBlockedCall();
    await waitFor(() => expect(mocks.getAccountDeletionStatus).toHaveBeenCalledTimes(1));

    // A deactivated account blocks EVERY request, so this event arrives in bursts.
    fireBlockedCall();
    fireBlockedCall();

    expect(mocks.getAccountDeletionStatus).toHaveBeenCalledTimes(1);
  });

  it('reactivates and reloads, since the whole app was rendered from refused calls', async () => {
    render(<AccountRestoreModal />);
    fireBlockedCall();

    const button = await screen.findByText('reactivate');
    fireEvent.click(button);

    await waitFor(() => expect(mocks.restoreAccount).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
  });

  it('surfaces a failed restore instead of reloading into the same blocked app', async () => {
    mocks.restoreAccount.mockRejectedValue(new Error('boom'));
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<AccountRestoreModal />);
    fireBlockedCall();

    fireEvent.click(await screen.findByText('reactivate'));

    await waitFor(() => expect(screen.getByText('restoreFailed')).toBeInTheDocument());
    expect(reload).not.toHaveBeenCalled();
    // The button must come back, otherwise a transient failure strands the account.
    expect(screen.getByText('reactivate')).toBeInTheDocument();
  });

  it('offers no reactivate button when the block is not a pending deletion', async () => {
    mocks.getAccountDeletionStatus.mockResolvedValue(NOT_SCHEDULED);
    render(<AccountRestoreModal />);
    fireBlockedCall();

    await waitFor(() => expect(screen.getByText('blockedTitle')).toBeInTheDocument());
    expect(screen.getByText('blockedDescription')).toBeInTheDocument();
    // Restore would return restored:false here; offering it would promise a fix it cannot make.
    expect(screen.queryByText('reactivate')).toBeNull();
  });

  it('still offers a way out when the status read itself fails', async () => {
    mocks.getAccountDeletionStatus.mockRejectedValue(new Error('gateway refused it too'));
    render(<AccountRestoreModal />);
    fireBlockedCall();

    await waitFor(() => expect(screen.getByText('blockedTitle')).toBeInTheDocument());
    expect(screen.getByText('signOut')).toBeInTheDocument();
  });

  it('clears a failed attempt when dismissed, so reopening does not show a stale error', async () => {
    mocks.restoreAccount.mockRejectedValue(new Error('boom'));
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<AccountRestoreModal />);
    fireBlockedCall();

    fireEvent.click(await screen.findByText('reactivate'));
    await waitFor(() => expect(screen.getByText('restoreFailed')).toBeInTheDocument());

    // Dismiss, then let the next blocked request bring the dialog back. Asserting the dialog is
    // really gone matters: watching only the error text disappear would pass even if the dialog
    // could never be closed at all.
    fireEvent.keyDown(document.activeElement || document.body, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    fireBlockedCall();
    await waitFor(() => expect(screen.getByText('reactivate')).toBeInTheDocument());
    expect(screen.queryByText('restoreFailed')).toBeNull();
  });

  it('says it is checking rather than claiming the account is locked out', async () => {
    // The status read is a round trip. Falling back to the "inactive account, contact support"
    // copy while it is in flight showed an alarming, wrong explanation to the common case.
    let resolveStatus: (v: typeof SCHEDULED) => void = () => {};
    mocks.getAccountDeletionStatus.mockReturnValue(
      new Promise((resolve) => { resolveStatus = resolve; }),
    );
    render(<AccountRestoreModal />);
    fireBlockedCall();

    await waitFor(() => expect(screen.getByText('checkingTitle')).toBeInTheDocument());
    expect(screen.queryByText('blockedTitle')).toBeNull();

    resolveStatus(SCHEDULED);
    await waitFor(() => expect(screen.getByText(/^title/)).toBeInTheDocument());
  });

  it('cannot be asked to restore twice while a restore is in flight', async () => {
    let resolveRestore: () => void = () => {};
    mocks.restoreAccount.mockReturnValue(new Promise((resolve) => { resolveRestore = () => resolve({}); }));
    render(<AccountRestoreModal />);
    fireBlockedCall();

    const button = await screen.findByText('reactivate');
    fireEvent.click(button);
    await waitFor(() => expect(mocks.restoreAccount).toHaveBeenCalledTimes(1));

    // Two fast clicks must not become two POSTs; the second would send a restore for an account
    // the first already restored.
    fireEvent.click(button);
    expect(mocks.restoreAccount).toHaveBeenCalledTimes(1);

    resolveRestore();
  });

  it('lets a later blocked call retry a status read that failed', async () => {
    // This screen is the only route to the restore endpoint. A single 502 must not strip the
    // button for the rest of the page session with no way to ask again.
    mocks.getAccountDeletionStatus.mockRejectedValueOnce(new Error('gateway blip'));
    render(<AccountRestoreModal />);
    fireBlockedCall();

    await waitFor(() => expect(screen.getByText('blockedTitle')).toBeInTheDocument());

    mocks.getAccountDeletionStatus.mockResolvedValue(SCHEDULED);
    fireBlockedCall();

    await waitFor(() => expect(screen.getByText('reactivate')).toBeInTheDocument());
    expect(mocks.getAccountDeletionStatus).toHaveBeenCalledTimes(2);
  });

  it('signs out on request', async () => {
    render(<AccountRestoreModal />);
    fireBlockedCall();

    fireEvent.click(await screen.findByText('signOut'));

    expect(mocks.logout).toHaveBeenCalledTimes(1);
  });
});
