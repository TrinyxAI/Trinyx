import { describe, it, expect } from 'vitest';
import { serializeEditValue, parseEditValue } from '../dataTableUtils';
import { parseAsset, toStoredAsset } from '@/lib/datatable/assetValue';

/**
 * The grid's inline editor is the ONE path every cell edit takes: a cell hands its new value to
 * `saveAndExit`, which serializes it, and `handleSaveEdit` runs it back through `parseEditValue`
 * before the JSONB write. A cell editor returning a structured value therefore only survives if
 * these two are exact inverses.
 *
 * They were not: the grid called `String(value)`, so a media cell's asset map was persisted as the
 * literal "[object Object]" and the file was lost on every upload, pick and paste. A test that
 * stopped at the cell's `onSaveAndExit` callback could not see it, which is why this one starts
 * where that one stops.
 */
describe('serializeEditValue / parseEditValue are exact inverses', () => {
  it('round-trips a media asset without losing a field', () => {
    const asset = toStoredAsset(parseAsset({
      _type: 'file',
      id: '44444444-4444-4444-4444-444444444444',
      path: 't/general/datatable/ab_photo.png',
      name: 'photo.png',
      mimeType: 'image/png',
      size: 1234,
    })!);

    const persisted = parseEditValue(serializeEditValue(asset));

    expect(persisted).toEqual(asset);
    expect(persisted).not.toBe('[object Object]');
  });

  it('the round-tripped value is still readable as an asset', () => {
    const asset = toStoredAsset(parseAsset('https://cdn.example.com/a.png')!);

    const persisted = parseEditValue(serializeEditValue(asset));

    expect(parseAsset(persisted)?.url).toBe('https://cdn.example.com/a.png');
  });

  it('String() on the same value produces the unrecoverable form the grid used to write', () => {
    // Pins WHY the helper exists, so a future simplification back to String() fails here.
    const asset = { _type: 'file', id: 'x', name: 'a.png' };

    expect(String(asset)).toBe('[object Object]');
    expect(parseEditValue(String(asset))).toBe('[object Object]');
  });

  it('leaves the scalar values every other cell type returns untouched', () => {
    expect(parseEditValue(serializeEditValue('hello'))).toBe('hello');
    expect(parseEditValue(serializeEditValue(42))).toBe(42);
    expect(parseEditValue(serializeEditValue(true))).toBe(true);
    expect(parseEditValue(serializeEditValue(''))).toBe('');
  });

  it('round-trips an array, which is what a multi_select cell returns', () => {
    expect(parseEditValue(serializeEditValue(['a', 'b']))).toEqual(['a', 'b']);
  });

  it('keeps a date string a string rather than parsing it as a number', () => {
    expect(parseEditValue(serializeEditValue('2026-03-19'))).toBe('2026-03-19');
  });
});
