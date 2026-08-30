/**
 * The workflow builder panel must never be imported STATICALLY.
 *
 * Why this is a test and not a style preference: `WorkflowBuilderPanelContent` pulls in the
 * entire workflow canvas (builder + inspector + node registry). When the app shell or a chat
 * message block imports it statically, the module graph gains an edge
 *
 *     app shell / chat  ->  builder  ->  inspector  ->  InputColumn
 *
 * while the builder already reaches the chat stack through EmptyCanvasChat. No single source
 * file forms a cycle, but Turbopack MERGES many source modules into one factory, and the
 * merged factories DO form one. When that happens the runtime's `u.i` can hand a consumer an
 * empty namespace snapshot of a module whose factory is still in flight, and it caches that
 * snapshot forever - the import silently resolves to `undefined`.
 *
 * That is exactly how the advanced inspector broke in production (2026-08-28): double-clicking
 * a node mounted `<InputColumn />` with `InputColumn === undefined`, i.e. React error #130.
 * It only ever happened in a production build, so neither `next dev` nor any unit test could
 * see it. This guard keeps the edge from coming back.
 *
 * The supported way to open such a tab is `openWorkflowBuilderTab` (lib/sidePanel), or a local
 * `import('@/components/app/WorkflowBuilderPanelContent')` inside the click handler.
 */
import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(__dirname, '..');
const SCAN_DIRS = ['app', 'components', 'lib', 'hooks', 'contexts'];
const STATIC_IMPORT = /^[ \t]*import\s+(?!type\b)[^;]*?from\s*['"][^'"]*WorkflowBuilderPanelContent['"]/m;

function sourceFiles(dir: string, acc: string[] = []): string[] {
  const abs = path.join(ROOT, dir);
  if (!fs.existsSync(abs)) return acc;
  for (const entry of fs.readdirSync(abs, { withFileTypes: true })) {
    const rel = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === '.next') continue;
      sourceFiles(rel, acc);
    } else if (/\.(ts|tsx)$/.test(entry.name)) {
      acc.push(rel);
    }
  }
  return acc;
}

describe('WorkflowBuilderPanelContent import discipline', () => {
  it('is never imported with a static import statement', () => {
    const offenders = sourceFiles('.')
      .filter((f) => SCAN_DIRS.some((d) => f.startsWith(d + path.sep)))
      .filter((f) => !f.includes('__tests__'))
      .filter((f) => STATIC_IMPORT.test(fs.readFileSync(path.join(ROOT, f), 'utf8')));

    expect(
      offenders,
      'Import it lazily instead - openWorkflowBuilderTab(), or '
        + "import('@/components/app/WorkflowBuilderPanelContent') inside the handler. "
        + 'A static import re-creates the merged-factory cycle that made InputColumn '
        + 'resolve to undefined in production (React error #130).',
    ).toEqual([]);
  });
});
