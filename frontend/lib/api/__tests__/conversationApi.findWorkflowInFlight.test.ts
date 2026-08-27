import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * Opening a workflow asks "is there a conversation for it?" from two places at once,
 * `useWorkflowChat` and the trigger panel, each behind its own already-loaded guard, and
 * `WorkflowPanelContent` mounts from more than one surface. They all miss and they all ask.
 *
 * <p>Measured in prod on 2026-08-26: 146 calls a day on `route=conversation`, 31 of 56 gaps under
 * 2 seconds, one workflow asked 57 times, arriving in bursts at ~0.05 s apart. The answer is a 404
 * in the normal case, because the conversation is only created on the first message, so a page
 * load put several identical failed requests in the network log for a state that is not an error.
 *
 * <p>Each test builds its OWN service instance. The in-flight map is a private field, and sharing
 * one instance across tests makes a release regression report the wrong test: a stale entry from
 * test A surfaces as "expected 1 call, got 0" inside test B, which sends a maintainer to the wrong
 * place entirely.
 */

const { get, post, put, del } = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(),
}));

// No ApiError export mocked: conversationApi never imports it, and a stand-in whose shape differs
// from the real one (status/code/data) is a trap for whoever extends this file next.
vi.mock('@/lib/api/api-client', () => ({
  apiClient: { get, post, put, delete: del },
}));

import { ConversationApiService } from '../conversationApi';

const WF = 'wf-06b56444-ff28-47ba-af7e-abad56de5682';

let api: ConversationApiService;

beforeEach(() => {
  vi.resetAllMocks();
  // The real apiClient.get is async and can never hand back a non-promise; the default mock
  // returns undefined, a shape the real object cannot produce. Fail loudly on an unplanned call
  // instead of letting `await undefined` quietly stand in for a response.
  get.mockImplementation(async () => { throw new Error('unexpected apiClient.get call'); });
  api = new ConversationApiService();
});

afterEach(() => vi.restoreAllMocks());

describe('findWorkflowConversation shares its in-flight request', () => {
  it('five simultaneous callers for one workflow cost ONE request', async () => {
    // The prod burst shape: several surfaces mounting together, ~0.05 s apart.
    let release: (v: unknown) => void = () => {};
    get.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));

    const calls = [1, 2, 3, 4, 5].map(() => api.findWorkflowConversation(WF));

    expect(get, 'a burst must collapse onto one request').toHaveBeenCalledTimes(1);

    // The URL is asserted, not assumed: sharing by workflow id means nothing if the request does
    // not carry that id. (The different-workflows test below asserts its own two paths, so this is
    // not the only thing standing between a constant endpoint and a green suite.)
    expect(get).toHaveBeenCalledWith(`/conversations/workflow/${WF}`);

    release({ id: 'conv-1' });
    const results = await Promise.all(calls);
    expect(results.every((r) => r?.id === 'conv-1')).toBe(true);
  });

  it('shares the 404 answer too, which is the case that actually happens', async () => {
    let reject: (e: unknown) => void = () => {};
    get.mockReturnValueOnce(new Promise((_, r) => { reject = r; }));

    const calls = [1, 2, 3].map(() => api.findWorkflowConversation(WF));
    expect(get).toHaveBeenCalledTimes(1);

    reject(Object.assign(new Error('Not Found'), { status: 404 }));
    expect(await Promise.all(calls)).toEqual([null, null, null]);
  });

  it('logs a real failure ONCE for a burst, which is the noise this exists to remove', async () => {
    // The stated purpose of the change, asserted rather than assumed: N callers, one console line.
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    let reject: (e: unknown) => void = () => {};
    get.mockReturnValueOnce(new Promise((_, r) => { reject = r; }));

    const calls = [1, 2, 3, 4].map(() => api.findWorkflowConversation(WF).catch((e) => e));
    reject(Object.assign(new Error('upstream exploded'), { status: 500 }));
    const results = await Promise.all(calls);

    expect(consoleError, 'one shared failure must log once, not once per caller').toHaveBeenCalledTimes(1);
    // ...and every caller still learns it failed, sharing the one error instance.
    expect(results.every((r) => r instanceof Error && r.message === 'Failed to find workflow conversation')).toBe(true);
    expect(new Set(results).size).toBe(1);
  });

  it('releases when settled, so a conversation created since becomes visible', async () => {
    // The release guard must not pin the first answer for the life of the page - the exact bug
    // found in useModels, where the compare was against the promise from before `.finally`.
    get.mockResolvedValueOnce(null);
    await api.findWorkflowConversation(WF);

    get.mockResolvedValueOnce({ id: 'conv-created-since' });
    const second = await api.findWorkflowConversation(WF);

    expect(get).toHaveBeenCalledTimes(2);
    expect(second?.id).toBe('conv-created-since');
  });

  it('releases after a REJECTION too, so the workflow can be asked about again', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    get.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 }));

    await expect(api.findWorkflowConversation(WF)).rejects.toThrow('Failed to find workflow conversation');

    get.mockResolvedValueOnce({ id: 'conv-later' });
    await expect(api.findWorkflowConversation(WF)).resolves.toMatchObject({ id: 'conv-later' });
  });

  it('serves a caller that arrives after the inner response but before the entry is released', async () => {
    // The ordering between the inner request settling and the `.finally` delete. Reaching it needs
    // the microtask queue drained enough for the inner await to resume, but not so far that the
    // `.finally` has run - calling straight through instead would just be the burst test with
    // N=2, which is what an earlier version of this test actually did while claiming otherwise.
    let release: (v: unknown) => void = () => {};
    get.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));

    const first = api.findWorkflowConversation(WF);
    release({ id: 'conv-mid' });
    await Promise.resolve();

    const second = api.findWorkflowConversation(WF);

    expect(get, 'the entry must still be shared while it is being torn down').toHaveBeenCalledTimes(1);
    expect(await first).toMatchObject({ id: 'conv-mid' });
    expect(await second).toMatchObject({ id: 'conv-mid' });
  });

  it('hands a piggybacking caller the SAME stale null, the one behaviour delta here', async () => {
    // A refresh (loadConversation(force)) that lands while a find is open no longer gets its own
    // fresh answer: it receives whatever that in-flight request returns. If a conversation was
    // created in between, this caller still sees null and the panel shows no messages until the
    // next run-status tick. Accepted, argued at the call site - pinned here so it stays a decision
    // rather than becoming a surprise.
    let release: (v: unknown) => void = () => {};
    get.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));

    const displayRead = api.findWorkflowConversation(WF);
    const refreshRead = api.findWorkflowConversation(WF);

    // The conversation is created elsewhere while both reads are in flight; the open request
    // predates it and still answers null.
    release(null);

    expect(await displayRead).toBeNull();
    expect(await refreshRead, 'the piggybacking caller shares the stale answer').toBeNull();
    expect(get).toHaveBeenCalledTimes(1);

    // ...and the NEXT read, after release, does see it.
    get.mockResolvedValueOnce({ id: 'conv-created-meanwhile' });
    await expect(api.findWorkflowConversation(WF)).resolves.toMatchObject({ id: 'conv-created-meanwhile' });
  });

  it('does not share between DIFFERENT workflows', async () => {
    // Once-per-call, so the throwing default from beforeEach stays armed: a third request would
    // fail loudly instead of quietly resolving null.
    get.mockResolvedValueOnce(null).mockResolvedValueOnce(null);

    await Promise.all([
      api.findWorkflowConversation('wf-a'),
      api.findWorkflowConversation('wf-b'),
    ]);

    // Keyed by workflow id: two workflows are two questions - and each really asked about ITS id.
    expect(get).toHaveBeenCalledTimes(2);
    expect(get).toHaveBeenCalledWith('/conversations/workflow/wf-a');
    expect(get).toHaveBeenCalledWith('/conversations/workflow/wf-b');
  });
});

describe('findWorkflowConversation 404 detection', () => {
  // Each disjunct on its own. A single fixture carrying status AND a "Not Found" message satisfies
  // all three at once, so any one of them could be deleted with the suite still green.
  it.each([
    ['status only', Object.assign(new Error('Request failed'), { status: 404 })],
    ['message says not found', new Error('Not Found')],
    ['message says 404', new Error('HTTP 404 while fetching')],
  ])('treats "%s" as no-conversation, not an error', async (_label, thrown) => {
    get.mockRejectedValueOnce(thrown);
    await expect(api.findWorkflowConversation(WF)).resolves.toBeNull();
  });

  it('stays SILENT on a 404: the normal case must not log', async () => {
    // The whole point of the change is that opening a workflow without a conversation is not an
    // error. Adding a console.error to the 404 branch would recreate the reported symptom on every
    // workflow open, and nothing else here would notice.
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    get.mockRejectedValueOnce(Object.assign(new Error('Not Found'), { status: 404 }));

    await expect(api.findWorkflowConversation(WF)).resolves.toBeNull();

    expect(consoleError, 'a missing conversation is not an error worth logging').not.toHaveBeenCalled();
  });

  it('does NOT swallow a non-404 failure', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    get.mockRejectedValueOnce(Object.assign(new Error('Internal Server Error'), { status: 500 }));

    await expect(api.findWorkflowConversation(WF)).rejects.toThrow('Failed to find workflow conversation');
  });
});
