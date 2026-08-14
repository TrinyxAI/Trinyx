import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../api-client';
import { generationService } from '../generation.service';

/**
 * Counted against the REAL client, not against the option it was passed.
 *
 * <p>Passing `retries: 0` and asserting that it was passed proves the caller's
 * intention, not the outcome. What matters is the number of times a generation
 * is SUBMITTED, because each submission is a separate paid job: the first one
 * keeps running when the connection drops, so a retry does not replace it, it
 * adds to it. This drives an edge timeout through the client and counts the
 * requests that actually leave.
 */

const originalFetch = globalThis.fetch;

function edgeTimeout() {
  return new Response('', { status: 504, statusText: '' });
}

beforeEach(() => {
  apiClient.setTokenProvider(async () => 'test-token');
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
});

describe('generation execute over a transport that gives up', () => {
  it('submits the generation EXACTLY ONCE, so a dropped connection cannot double the bill', async () => {
    const fetchMock = vi.fn(async () => edgeTimeout());
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await expect(generationService.execute({
      model: 'seedance-2.0',
      params: { prompt: 'a boat' },
    })).rejects.toMatchObject({ status: 504 });

    const submissions = (fetchMock.mock.calls as unknown as unknown[][])
      .filter((call) => String(call[0]).includes('/generation/execute'));
    expect(submissions).toHaveLength(1);
  }, 30_000);

  it('reports the edge status, which is what the caller has to classify on', async () => {
    // Over HTTP/2 there is no reason phrase, so the message is "HTTP 504: " and
    // carries no word to match on. Anything deciding what happened has to read
    // the status.
    globalThis.fetch = (vi.fn(async () => edgeTimeout())) as unknown as typeof fetch;

    await generationService.execute({ model: 'seedance-2.0', params: { prompt: 'a boat' } })
      .then(
        () => { throw new Error('expected the call to reject'); },
        (e) => { expect(e.status).toBe(504); },
      );
  }, 30_000);
});
