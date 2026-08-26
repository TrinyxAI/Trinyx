/**
 * The client-side twin of the server's tile builder, used by the applications list (the one
 * page that holds its whole set itself). The two must agree on the rules that are easy to
 * get subtly wrong: counts cover the WHOLE subtree, and an empty folder sinks instead of
 * floating to the top of a time-ordered list.
 */
import { describe, expect, it } from 'vitest';
import { buildFolderTiles, buildFolderTrail, type FolderTileSource } from '../buildFolderTiles';
import type { ResourceFolder } from '@/lib/api/orchestrator/resource-folder.service';

const folder = (id: string, name: string, parentFolderId: string | null = null): ResourceFolder => ({
  id,
  name,
  parentFolderId,
});

const item = (id: string, name: string, lastModifiedAt: string | null, lastActivityAt: string | null = null):
  FolderTileSource => ({ id, name, lastModifiedAt, lastActivityAt });

const OLD = '2026-01-01T00:00:00Z';
const RECENT = '2026-08-01T00:00:00Z';

describe('buildFolderTiles', () => {
  it('builds one tile per folder of the level asked for', () => {
    const folders = [folder('a', 'Alpha'), folder('b', 'Beta', 'a')];

    expect(buildFolderTiles({
      folders, parentFolderId: null, items: [], memberships: new Map(), sort: 'name',
    }).map((t) => t.name)).toEqual(['Alpha']);

    expect(buildFolderTiles({
      folders, parentFolderId: 'a', items: [], memberships: new Map(), sort: 'name',
    }).map((t) => t.name)).toEqual(['Beta']);
  });

  it('counts the whole subtree, so a folder of subfolders never reads as empty', () => {
    const folders = [folder('a', 'Alpha'), folder('b', 'Beta', 'a')];
    const items = [item('1', 'direct', OLD), item('2', 'nested', OLD), item('3', 'loose', OLD)];
    const memberships = new Map([['1', 'a'], ['2', 'b']]);

    const [tile] = buildFolderTiles({ folders, parentFolderId: null, items, memberships, sort: 'name' });

    expect(tile.itemCount).toBe(2);
    expect(tile.subfolderCount).toBe(1);
  });

  it('borrows the freshest dates found inside it', () => {
    const folders = [folder('a', 'Alpha')];
    const items = [item('1', 'a', OLD, OLD), item('2', 'b', RECENT, RECENT)];
    const memberships = new Map([['1', 'a'], ['2', 'a']]);

    const [tile] = buildFolderTiles({ folders, parentFolderId: null, items, memberships, sort: 'name' });

    expect(tile.lastModifiedAt).toBe(RECENT);
    expect(tile.lastActivityAt).toBe(RECENT);
  });

  it('an empty folder carries no dates at all', () => {
    const [tile] = buildFolderTiles({
      folders: [folder('a', 'Alpha')], parentFolderId: null, items: [], memberships: new Map(), sort: 'name',
    });

    expect(tile.itemCount).toBe(0);
    expect(tile.lastModifiedAt).toBeNull();
    expect(tile.preview).toEqual([]);
  });

  it('draws at most six items, most recently changed first', () => {
    const folders = [folder('a', 'Alpha')];
    const items = Array.from({ length: 8 }, (_, i) =>
      item(String(i + 1), `item-${i + 1}`, `2026-0${(i % 9) + 1}-01T00:00:00Z`));
    const memberships = new Map(items.map((i) => [i.id, 'a']));

    const [tile] = buildFolderTiles({ folders, parentFolderId: null, items, memberships, sort: 'name' });

    expect(tile.preview).toHaveLength(6);
    expect(tile.preview[0].name).toBe('item-8');
  });

  it('an empty folder sorts LAST on a time key, not first', () => {
    const folders = [folder('empty', 'Empty'), folder('used', 'Used')];
    const memberships = new Map([['1', 'used']]);

    const tiles = buildFolderTiles({
      folders, parentFolderId: null, items: [item('1', 'a', OLD)], memberships, sort: 'lastModified',
    });

    expect(tiles.map((t) => t.name)).toEqual(['Used', 'Empty']);
  });

  it('orders by the freshest content on lastModified, and by name case-insensitively', () => {
    const folders = [folder('stale', 'stale'), folder('fresh', 'Fresh')];
    const memberships = new Map([['1', 'stale'], ['2', 'fresh']]);
    const items = [item('1', 'a', OLD), item('2', 'b', RECENT)];

    expect(buildFolderTiles({ folders, parentFolderId: null, items, memberships, sort: 'lastModified' })
      .map((t) => t.name)).toEqual(['Fresh', 'stale']);
    expect(buildFolderTiles({ folders, parentFolderId: null, items, memberships, sort: 'name' })
      .map((t) => t.name)).toEqual(['Fresh', 'stale']);
  });

  it('ignores an item filed in a folder of another level', () => {
    const folders = [folder('a', 'Alpha')];
    const memberships = new Map([['1', 'somewhere-else']]);

    const [tile] = buildFolderTiles({
      folders, parentFolderId: null, items: [item('1', 'a', OLD)], memberships, sort: 'name',
    });

    expect(tile.itemCount).toBe(0);
  });
});

describe('buildFolderTrail', () => {
  it('runs root -> folder', () => {
    const folders = [folder('a', 'Alpha'), folder('b', 'Beta', 'a'), folder('c', 'Gamma', 'b')];

    expect(buildFolderTrail(folders, 'c').map((f) => f.name)).toEqual(['Alpha', 'Beta', 'Gamma']);
  });

  it('is empty at the top level', () => {
    expect(buildFolderTrail([folder('a', 'Alpha')], null)).toEqual([]);
  });

  it('a parent deleted from under a folder ends the trail instead of failing', () => {
    const folders = [folder('b', 'Beta', 'a')];

    expect(buildFolderTrail(folders, 'b').map((f) => f.name)).toEqual(['Beta']);
  });
});
