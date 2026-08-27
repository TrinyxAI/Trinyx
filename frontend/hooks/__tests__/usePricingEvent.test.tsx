// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, waitFor } from '@testing-library/react';
import { usePricingEvent, __resetPricingEventStoreForTests } from '../usePricingEvent';

function Probe({ label = 'a' }: { label?: string }) {
  const { event, serverTime, isLoading } = usePricingEvent();
  return (
    <div data-testid={label}>
      {`${isLoading ? 'loading' : 'ready'}|${event?.id ?? 'none'}|${serverTime?.toISOString() ?? 'no-time'}`}
    </div>
  );
}

const PAYLOAD = {
  event: {
    id: 'founding-2026',
    startsAt: '2026-08-25T00:00:00.000Z',
    endsAt: '2026-10-31T23:59:59.999Z',
    windowEndsAt: '2026-09-01T00:00:00.000Z',
    announcedBasePrices: { pro: 45 },
    locksPrice: true,
  },
  serverTime: '2026-09-15T12:00:00.000Z',
};

describe('usePricingEvent', () => {
  beforeEach(() => {
    __resetPricingEventStoreForTests();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('publishes the window and the server time once the fetch resolves', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => PAYLOAD }));

    render(<Probe />);

    await waitFor(() =>
      expect(screen.getByTestId('a').textContent).toBe(
        'ready|founding-2026|2026-09-15T12:00:00.000Z'
      )
    );
  });

  it('reports no window rather than an error when the request fails', async () => {
    // The announcement is additive: a price card must still render its real price.
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));

    render(<Probe />);

    await waitFor(() => expect(screen.getByTestId('a').textContent).toBe('ready|none|no-time'));
  });

  it('reports no window on a non-OK response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));

    render(<Probe />);

    await waitFor(() => expect(screen.getByTestId('a').textContent).toBe('ready|none|no-time'));
  });

  it('fetches once no matter how many surfaces read the window', async () => {
    // Four price surfaces mount this. One shared request is the whole reason the
    // store exists instead of a per-component query.
    const fetchSpy = vi.fn().mockResolvedValue({ ok: true, json: async () => PAYLOAD });
    vi.stubGlobal('fetch', fetchSpy);

    render(
      <>
        <Probe label="a" />
        <Probe label="b" />
        <Probe label="c" />
      </>
    );

    await waitFor(() => expect(screen.getByTestId('c').textContent).toContain('founding-2026'));
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it('gives every consumer the same window', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => PAYLOAD }));

    render(
      <>
        <Probe label="a" />
        <Probe label="b" />
      </>
    );

    await waitFor(() => expect(screen.getByTestId('a').textContent).toContain('founding-2026'));
    expect(screen.getByTestId('b').textContent).toBe(screen.getByTestId('a').textContent);
  });
});
