// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * The wire key `monthlyCreditsAreWorkflowOnly`, read through the REAL hook.
 *
 * <p>A previous version of this file defined its own copy of the hook's
 * mapping and asserted against that. It looked like a contract test and was
 * one line of fiction: flipping the real default from `?? false` to `?? true`
 * left every test in the repository green and TypeScript silent, while the
 * modal started telling every paying subscriber that their credits could not
 * pay. A test anchored to a mirror measures the mirror.
 *
 * <p>So this renders the hook. The pieces it needs are stubbed at their own
 * boundaries (the session, the HTTP call), and nothing between the response
 * and the returned value is faked, because that mapping is the thing under
 * test.
 */

const mocks = vi.hoisted(() => ({
  getBalance: vi.fn(),
  useAuthGuard: vi.fn(),
}));

vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: mocks.useAuthGuard }));
vi.mock('../../api/services/quota-api.service', () => ({
  quotaApi: { getBalance: mocks.getBalance },
}));

import { useCreditBalance } from '../smart-hooks-complete';

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

/** Signed in and ready, which is what makes the query run at all. */
function givenASession() {
  mocks.useAuthGuard.mockReturnValue({
    user: { sub: 'user-1' }, isAuthenticated: true, isReady: true,
  });
}

beforeEach(() => {
  givenASession();
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('useCreditBalance and the workflow-scoped flag', () => {
  it('reads the flag the server sent, under the name the server sends it', async () => {
    mocks.getBalance.mockResolvedValue({
      balance: 800, subBalance: 800, paygBalance: 0,
      delinquent: false, monthlyCreditsAreWorkflowOnly: true,
    });

    const { result } = renderHook(() => useCreditBalance(), { wrapper });

    await waitFor(() => expect(result.current.monthlyCreditsAreWorkflowOnly).toBe(true));
  });

  it('reads FALSE for an account whose monthly credits pay for everything', async () => {
    mocks.getBalance.mockResolvedValue({
      balance: 800, subBalance: 800, paygBalance: 0,
      delinquent: false, monthlyCreditsAreWorkflowOnly: false,
    });

    const { result } = renderHook(() => useCreditBalance(), { wrapper });

    await waitFor(() => expect(result.current.balance).toBe(800));
    expect(result.current.monthlyCreditsAreWorkflowOnly).toBe(false);
  });

  it('answers FALSE when the server said nothing, so no surface warns on a guess', async () => {
    // The CE shape and the pre-V250 shape both omit it. Defaulting the other
    // way puts a cloud-only billing rule in front of accounts it never applied
    // to, which is the failure this flag exists to prevent.
    mocks.getBalance.mockResolvedValue({
      balance: 800, subBalance: 800, paygBalance: 0, delinquent: false,
    });

    const { result } = renderHook(() => useCreditBalance(), { wrapper });

    await waitFor(() => expect(result.current.balance).toBe(800));
    expect(result.current.monthlyCreditsAreWorkflowOnly).toBe(false);
  });

  it('answers FALSE before any answer has arrived', async () => {
    // The query is in flight and `data` is undefined. A surface reading this
    // during the first paint must not flash a warning it will retract.
    mocks.getBalance.mockReturnValue(new Promise(() => {}));

    const { result } = renderHook(() => useCreditBalance(), { wrapper });

    expect(result.current.monthlyCreditsAreWorkflowOnly).toBe(false);
    expect(result.current.balance).toBeNull();
  });
});
