import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * The wire contract of "rename a credential".
 *
 * The component test mocks `orchestratorApi` wholesale, so a wrong verb, path or
 * body shape would ship green there. This is the only test that pins what actually
 * leaves the browser: `PATCH /credentials/{id}` with `{ name }`.
 *
 * It also pins that the call goes through `apiClient` (the single auth path), never
 * a raw `fetch`.
 */

const { patchMock } = vi.hoisted(() => ({ patchMock: vi.fn() }));

vi.mock('@/lib/api/api-client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: patchMock,
    delete: vi.fn(),
  },
}));

import { credentialService } from '../credential.service';

describe('credentialService.renameCredential', () => {
  beforeEach(() => vi.clearAllMocks());

  it('PATCHes /credentials/{id} with the new name', async () => {
    patchMock.mockResolvedValue({ id: 42, name: 'Gmail (work)' });

    const result = await credentialService.renameCredential(42, 'Gmail (work)');

    expect(patchMock).toHaveBeenCalledTimes(1);
    expect(patchMock).toHaveBeenCalledWith('/credentials/42', { name: 'Gmail (work)' });
    expect(result).toEqual({ id: 42, name: 'Gmail (work)' });
  });

  it('lets the transport error through with the code the UI branches on', async () => {
    patchMock.mockRejectedValue(
      Object.assign(new Error('conflict'), { status: 409, code: 'duplicate_name' }),
    );

    // The dialog picks its message from `code`, so swallowing or rewrapping the
    // error here would collapse every refusal into the generic failure.
    await expect(credentialService.renameCredential(42, 'Taken')).rejects.toMatchObject({
      status: 409,
      code: 'duplicate_name',
    });
  });

  it('is exposed on orchestratorApi under the same name the UI calls', async () => {
    patchMock.mockResolvedValue({ id: 42, name: 'Bound' });
    const { orchestratorApi } = await import('../index');

    // The component test mocks orchestratorApi wholesale and this file imports the
    // service directly, so a missing or misspelled bind is invisible to both.
    await orchestratorApi.renameCredential(42, 'Bound');

    expect(patchMock).toHaveBeenCalledWith('/credentials/42', { name: 'Bound' });
  });
});
