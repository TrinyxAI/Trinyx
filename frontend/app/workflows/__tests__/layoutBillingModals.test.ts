/**
 * The standalone builder route mounts its own copy of every app-wide dialog.
 *
 * <p>`/workflows` is a sibling of `/[locale]/app`, not a child, so nothing under
 * the app layout renders here. Each dialog that is opened by dispatching a
 * window event therefore needs its listener mounted on this route too, or the
 * dispatch lands on no one and the feature simply does nothing.
 *
 * <p>That is not hypothetical: `useWorkflowExecution` has been calling
 * `showInsufficientCreditsModal()` when a test-run is refused for want of
 * credits, on a route with no listener, so the run stopped with no explanation.
 *
 * <p>Read as SOURCE rather than rendered: the layout is an async server
 * component that awaits the request locale and the message catalogue, which a
 * jsdom render cannot provide. What matters here is the mount, and the mount is
 * a fact about the file.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const layout = readFileSync(join(process.cwd(), 'app/workflows/layout.tsx'), 'utf8');

describe('workflows layout - the dialogs a dispatch needs', () => {
  it('mounts the insufficient-credits dialog, which the builder already dispatches', () => {
    expect(layout).toContain("from '@/components/billing/InsufficientCreditsModal'");
    expect(layout).toContain('<InsufficientCreditsModal />');
  });

  it('keeps the other event-driven dialogs it already mounted', () => {
    // Named one by one: the point of this file is that the list must not lose
    // an entry, and a count would pass while an entry was swapped out.
    for (const mounted of [
      '<CeCloudCreditModal />',
      '<ModelNotManagedModal />',
      '<AgentErrorModal />',
      '<AccountRestoreModal />',
    ]) {
      expect(layout).toContain(mounted);
    }
  });
});
