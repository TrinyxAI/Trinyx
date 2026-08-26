// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * The two reads behind the generation history, at the boundary where an HTTP answer becomes a fact
 * the UI acts on.
 *
 * <p>One distinction carries the whole feature: a 404 from the per-asset read means "this file was
 * not generated here" - the ordinary answer for almost every file in a workspace - while any other
 * failure means "we could not ask". Collapsing the second into the first hides a Regenerate control
 * that should be there, and does it silently, on exactly the files it matters for.
 */

const client = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../api-client', async (importOriginal) => {
  // ApiError is the REAL one: the 404 branch is a check on its status, and a stub error class here
  // would certify the test's own idea of the error rather than the client's.
  const actual = await importOriginal<typeof import('../api-client')>();
  return { ...actual, apiClient: client };
});

import { ApiError } from '../api-client';
import { storageApi } from '../storage-api';

beforeEach(() => {
  client.get.mockReset();
});

describe('storageApi.getGenerationHistory', () => {
  it('asks the explorer for the generated assets, paged', async () => {
    client.get.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 12 });

    await storageApi.getGenerationHistory({ page: 2, size: 12 });

    expect(client.get).toHaveBeenCalledWith('/storage/explorer/generations', {
      params: { page: '2', size: '12' },
    });
  });

  it('passes a format filter only when one was chosen', async () => {
    client.get.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 12 });

    await storageApi.getGenerationHistory({ kind: 'voice' });
    expect(client.get).toHaveBeenLastCalledWith('/storage/explorer/generations', {
      params: { kind: 'voice' },
    });

    await storageApi.getGenerationHistory({});
    expect(client.get).toHaveBeenLastCalledWith('/storage/explorer/generations', { params: {} });
  });

  it('hands the page back as the server sent it', async () => {
    const page = {
      content: [{ id: 'a1', provenance: { model: 'flux-1.1-pro' } }],
      totalElements: 1, totalPages: 1, number: 0, size: 12,
    };
    client.get.mockResolvedValue(page);

    await expect(storageApi.getGenerationHistory({})).resolves.toEqual(page);
  });
});

describe('storageApi.getGenerationProvenance', () => {
  it('returns the recipe of a generated asset', async () => {
    client.get.mockResolvedValue({ model: 'flux-1.1-pro', prompt: 'a lighthouse' });

    await expect(storageApi.getGenerationProvenance('f1'))
      .resolves.toEqual({ model: 'flux-1.1-pro', prompt: 'a lighthouse' });
    expect(client.get).toHaveBeenCalledWith('/storage/explorer/f1/generation');
  });

  it('reads a 404 as "this file was not generated here", not as a failure', async () => {
    // The ordinary case: uploads, step outputs, chat attachments. Throwing here would take the
    // file viewer down on the majority of the files it opens.
    client.get.mockRejectedValue(new ApiError('not found', 404));

    await expect(storageApi.getGenerationProvenance('f1')).resolves.toBeNull();
  });

  it('still throws when the question could not be asked', async () => {
    // "We could not ask" is a different fact from "there is nothing". Swallowed, a 500 or a dropped
    // connection would hide a Regenerate control that belongs on that asset, and say nothing.
    client.get.mockRejectedValue(new ApiError('boom', 500));
    await expect(storageApi.getGenerationProvenance('f1')).rejects.toThrow();

    client.get.mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(storageApi.getGenerationProvenance('f1')).rejects.toThrow();
  });
});
