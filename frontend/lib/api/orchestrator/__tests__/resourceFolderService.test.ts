/**
 * The folder calls of a list page. Pins the paths (each kind's folders belong to the
 * service that owns that list) and the two payload shapes that are easy to get wrong:
 * `null` really means "the top level" on both create and file, and the ids are sent under
 * the name the owning list uses.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

vi.mock('../../api-client', () => ({
  apiClient: {
    get: mocks.get,
    post: mocks.post,
    put: mocks.put,
    delete: mocks.del,
  },
}));

import { resourceFolderService } from '../resource-folder.service';

afterEach(() => {
  vi.clearAllMocks();
});

describe('resourceFolderService', () => {
  it('reads the workspace tree from the workflow list owner', async () => {
    mocks.get.mockResolvedValue({ folders: [{ id: 'f1', name: 'A', parentFolderId: null }] });

    const folders = await resourceFolderService.list('workflow');

    expect(mocks.get).toHaveBeenCalledWith('/workflow-folders');
    expect(folders).toHaveLength(1);
  });

  it('treats a response without folders as an empty tree, not a crash', async () => {
    mocks.get.mockResolvedValue({});

    await expect(resourceFolderService.list('workflow')).resolves.toEqual([]);
  });

  it('creates at the top level by default', async () => {
    mocks.post.mockResolvedValue({ id: 'f1' });

    await resourceFolderService.create('workflow', 'Marketing');

    expect(mocks.post).toHaveBeenCalledWith('/workflow-folders', {
      name: 'Marketing',
      parentFolderId: null,
    });
  });

  it('creates inside a parent when one is given', async () => {
    mocks.post.mockResolvedValue({ id: 'f2' });

    await resourceFolderService.create('workflow', 'Campaigns', 'f1');

    expect(mocks.post).toHaveBeenCalledWith('/workflow-folders', {
      name: 'Campaigns',
      parentFolderId: 'f1',
    });
  });

  it('renames and re-parents through the folder itself', async () => {
    mocks.put.mockResolvedValue({ id: 'f1' });

    await resourceFolderService.rename('workflow', 'f1', 'Growth');
    await resourceFolderService.move('workflow', 'f1', null);

    expect(mocks.put).toHaveBeenNthCalledWith(1, '/workflow-folders/f1', { name: 'Growth' });
    expect(mocks.put).toHaveBeenNthCalledWith(2, '/workflow-folders/f1/move', { parentFolderId: null });
  });

  it('reports every folder a delete removed, and falls back to the one asked for', async () => {
    mocks.del.mockResolvedValueOnce({ deletedFolderIds: ['f1', 'f2'] });
    await expect(resourceFolderService.remove('workflow', 'f1')).resolves.toEqual(['f1', 'f2']);

    mocks.del.mockResolvedValueOnce(undefined);
    await expect(resourceFolderService.remove('workflow', 'f1')).resolves.toEqual(['f1']);
  });

  it('files workflows under the name the workflow list uses', async () => {
    mocks.post.mockResolvedValue({ moved: 2 });

    const moved = await resourceFolderService.assign('workflow', 'f1', ['w1', 'w2']);

    expect(mocks.post).toHaveBeenCalledWith('/workflow-folders/items', {
      folderId: 'f1',
      workflowIds: ['w1', 'w2'],
    });
    expect(moved).toBe(2);
  });

  it('sends folderId null to file back at the top level', async () => {
    mocks.post.mockResolvedValue({ moved: 1 });

    await resourceFolderService.assign('workflow', null, ['w1']);

    expect(mocks.post).toHaveBeenCalledWith('/workflow-folders/items', {
      folderId: null,
      workflowIds: ['w1'],
    });
  });

  it('reports nothing moved when the server does not say', async () => {
    mocks.post.mockResolvedValue({});

    await expect(resourceFolderService.assign('workflow', 'f1', ['w1'])).resolves.toBe(0);
  });
});
