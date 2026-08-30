import { describe, it, expect } from 'vitest';
import { isTableAsset, assetDisplayUrl, cellDisplayText, parseAsset } from '../assetValue';

/**
 * What these predicates must NOT claim.
 *
 * Three separate defects in this change came from the same place: a predicate was written for one
 * shape, tested with that shape plus the single counter-example it was told to refuse, and then
 * over-claimed something nobody had thought to try. The worst of them accepted any object with a
 * UUID `id`, so `assetDisplayUrl` replaced whole customer and run records with a file URL inside
 * `window.__RESOLVED_DATA__` - silently, and invisibly to every other test in the suite.
 *
 * So this file is deliberately organised the other way round: a corpus of values that occur in real
 * run data, all asserted NOT to be files. New recognition rules must keep it green.
 */

const UUID = '9a443915-a594-48a1-9760-e7a1b4b2eaf7';

const NOT_FILES: Array<[string, unknown]> = [
  ['a workflow run record', { id: UUID, status: 'COMPLETED', epoch: 2 }],
  ['a customer row', { id: UUID, name: 'Acme Corp', email: 'a@acme.test' }],
  ['a marketplace publication', { id: UUID, title: 'My App', price: 10 }],
  ['a Supabase-style row', { id: UUID, created_at: '2026-01-01', body: 'hello' }],
  ['a GitHub repository', { name: 'repo', url: 'https://api.github.com/repos/a/b' }],
  ['an API object with a url and an id', { id: UUID, url: 'https://api.example.com/things/1', name: 'Thing' }],
  ['a step envelope', { _status: 'COMPLETED', _duration_ms: 12, id: UUID }],
  // A download-file node's output puts _status FLAT beside the node's own fields, so the envelope
  // has a file_url at top level without being a file. isFileRef guards this for the same reason:
  // claiming it collapses the whole step output to one URL and stops descent into its other leaves.
  ['a download-file step envelope', {
    _status: 'COMPLETED', _duration_ms: 120,
    file_url: 'https://cdn.example.test/x.pdf', file_name: 'x.pdf',
    content_type: 'application/pdf', file_size: 5, source_url: 'https://origin.test/x.pdf',
  }],
  ['a plain object', { foo: 'bar' }],
  ['an array', [1, 2, 3]],
  ['a number', 42],
  ['a bare string', 'hello world'],
  ['an empty object', {}],
];

describe('isTableAsset refuses everything that is not one of our files', () => {
  it.each(NOT_FILES)('refuses %s', (_label, value) => {
    expect(isTableAsset(value)).toBe(false);
    expect(assetDisplayUrl(value)).toBeNull();
  });

  it('a UUID id is never evidence, with or without a url beside it', () => {
    // Run records, customers and publications are all keyed by UUID, and many carry a url of their
    // own. Only a URL addressing OUR storage, or the discriminator, makes a value a file.
    expect(isTableAsset({ id: UUID })).toBe(false);
    expect(isTableAsset({ id: UUID, url: 'https://api.example.com/things/1' })).toBe(false);
    expect(isTableAsset({ id: UUID, url: `/api/proxy/files/by-id/${UUID}/raw` })).toBe(true);
    expect(isTableAsset({ _type: 'file', id: UUID })).toBe(true);
  });

  it('reads the JSON-string form, which is how the agent write path persists a media cell', () => {
    expect(isTableAsset(JSON.stringify({ _type: 'file', id: UUID, url: '/api/x' }))).toBe(true);
    expect(assetDisplayUrl(JSON.stringify({ _type: 'file', url: 'https://cdn.example.com/a.png' })))
      .toBe('https://cdn.example.com/a.png');
  });

  it('does not treat an arbitrary JSON string as a file', () => {
    expect(isTableAsset(JSON.stringify({ id: UUID, name: 'Acme Corp' }))).toBe(false);
    expect(isTableAsset('{ not json')).toBe(false);
  });
});

describe('cellDisplayText only reclassifies text that IS a file reference', () => {
  it('keeps prose that merely mentions a file link', () => {
    // The URL matcher is an extractor, not a classifier: using it on arbitrary cell text made a
    // note collapse to the link it mentioned, losing the prose from exports, sorting and search.
    const note = `See /api/proxy/files/by-id/${UUID}/raw for details`;

    expect(cellDisplayText(note)).toBe(note);
  });

  it('keeps a markdown link intact', () => {
    const markdown = `[invoice](/api/proxy/files/by-id/${UUID}/raw)`;

    expect(cellDisplayText(markdown)).toBe(markdown);
  });

  it('still resolves a cell that IS a bare by-id URL', () => {
    const url = `/api/proxy/files/by-id/${UUID}/raw?disposition=inline`;

    // No name was ever stored for this shape, so the URL is the most useful thing to show. It is
    // what the repair migration writes, and exporting the word "file" would be worse.
    expect(cellDisplayText(url)).toContain(`/files/by-id/${UUID}/raw`);
  });

  it('keeps an ordinary url column verbatim', () => {
    expect(cellDisplayText('https://acme.com/products/widget?ref=1'))
      .toBe('https://acme.com/products/widget?ref=1');
  });
});

describe('the permissive reader and the strict classifier have different jobs', () => {
  it('parseAsset reads the legacy cell shape the strict predicate also accepts', () => {
    const legacy = { url: `/api/proxy/files/by-id/${UUID}/raw`, id: UUID, name: 'a.pdf', mimeType: 'application/pdf', size: 4 };

    expect(parseAsset(legacy)?.id).toBe(UUID);
    expect(isTableAsset(legacy)).toBe(true);
  });

  it('parseAsset stays permissive, because its caller already knows the column holds media', () => {
    // The cell editor calls it on a value from a `file` column, where a bare id IS a file. That is
    // safe only as long as nothing calls it on an arbitrary column - see the next assertion.
    expect(parseAsset({ id: UUID, name: 'Acme Corp' })).not.toBeNull();
  });

  it('but cellDisplayText, which runs on EVERY column, does not reclassify that record', () => {
    expect(cellDisplayText({ id: UUID, name: 'Acme Corp' }))
      .toBe(JSON.stringify({ id: UUID, name: 'Acme Corp' }));
  });
});

/**
 * The mirror of the corpus above, and just as load-bearing.
 *
 * Tightening a predicate to stop it over-claiming is how it starts UNDER-claiming, and that is
 * exactly what happened once here: requiring a `url` or an `id` alongside our own discriminator
 * refused the classic workflow FileRef, so a column of workflow-produced files exported as JSON.
 * Asking only "what does it wrongly accept?" cannot catch that. Both directions live here.
 */
describe('every shape our own writers produce is still recognised', () => {
  const OURS: Array<[string, unknown, string]> = [
    ['the canonical asset',
      { _type: 'file', id: UUID, url: `/api/proxy/files/by-id/${UUID}/raw`, name: 'photo.png', mimeType: 'image/png', size: 9 },
      'photo.png'],
    ['a classic workflow FileRef, which carries no url and no id',
      { _type: 'file', path: 't/run/x.png', name: 'x.png', mimeType: 'image/png', size: 1 },
      'x.png'],
    ['a CE workflow FileRef',
      { _type: 'file', path: '1/general/general/ab_report.pdf', name: 'report.pdf', mimeType: 'application/pdf', size: 3 },
      'report.pdf'],
    ['the legacy cell shape with no discriminator',
      { url: `/api/proxy/files/by-id/${UUID}/raw`, id: UUID, name: 'invoice.pdf', mimeType: 'application/pdf', size: 4 },
      'invoice.pdf'],
    ['an external link stored as an asset',
      { _type: 'file', url: 'https://cdn.example.com/a.png', name: 'a.png' },
      'a.png'],
    ['the DB-flattened shape',
      { file_url: 'https://cdn.example.com/b.png', file_name: 'b.png', content_type: 'image/png' },
      'b.png'],
    ['the JSON-string form the agent write path persists',
      JSON.stringify({ _type: 'file', id: UUID, url: `/api/proxy/files/by-id/${UUID}/raw`, name: 'agent.png' }),
      'agent.png'],
  ];

  it.each(OURS)('reads %s as its file name', (_label, value, expected) => {
    expect(cellDisplayText(value)).toBe(expected);
  });

  it.each(OURS)('classifies %s as one of ours', (_label, value) => {
    expect(isTableAsset(value)).toBe(true);
  });

  it('a path-only ref is recognised but still resolves to no URL for a template', () => {
    // Recognising it must not make a template substitute something unrenderable: it has no URL
    // that a browser could load, so the rewriters must fall through exactly as they did before.
    const pathOnly = { _type: 'file', path: 't/run/x.png', name: 'x.png', mimeType: 'image/png', size: 1 };

    expect(isTableAsset(pathOnly)).toBe(true);
    expect(assetDisplayUrl(pathOnly)).toBeNull();
  });
});
