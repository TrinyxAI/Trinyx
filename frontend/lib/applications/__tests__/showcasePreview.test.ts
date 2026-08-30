/**
 * How an application is previewed. The rules were inlined in the application card until the
 * folder face needed the same decision; getting one wrong is invisible in code review and
 * shows up as a card that silently falls back to its cover tile.
 */
import { describe, expect, it } from 'vitest';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import { showcaseBindingFor } from '../showcasePreview';

const pub = (extra: Partial<WorkflowPublication> = {}): WorkflowPublication => ({
  id: 'pub-1',
  title: 'Demo App',
  showcaseRunId: 'showcase-run',
  showcaseInterfaceId: 'iface-1',
  ...extra,
} as WorkflowPublication);

describe('showcaseBindingFor', () => {
  it('renders an OWN published app from its own run, with no publication scope', () => {
    const binding = showcaseBindingFor({ publication: pub(), source: 'published' });

    expect(binding.canPreview).toBe(true);
    expect(binding.runId).toBe('showcase-run');
    expect(binding.interfaceId).toBe('iface-1');
    expect(binding.publicationId).toBeUndefined();
    expect(binding.authenticated).toBe(false);
    expect(binding.remote).toBe(false);
  });

  it('prefers the application-dedicated run over the published showcase run', () => {
    const binding = showcaseBindingFor({
      publication: pub(),
      source: 'published',
      applicationRunId: 'live-run',
    });

    expect(binding.runId).toBe('live-run');
  });

  it('reads a LOCAL acquired app through the authenticated publication showcase', () => {
    // The run + interface belong to the publisher, so a per-run render would be cross-tenant.
    // Authenticated, so the acquirer's receipt still admits it once the publisher unpublishes.
    const binding = showcaseBindingFor({ publication: pub(), source: 'acquired' });

    expect(binding.publicationId).toBe('pub-1');
    expect(binding.authenticated).toBe(true);
    expect(binding.remote).toBe(false);
  });

  it('routes a CLOUD-acquired app through the remote proxy, never the authenticated path', () => {
    // Its publication id is a cloud id, absent from the local DB.
    const binding = showcaseBindingFor({ publication: pub({ remote: true }), source: 'acquired' });

    expect(binding.remote).toBe(true);
    expect(binding.authenticated).toBe(false);
    expect(binding.publicationId).toBe('pub-1');
  });

  it('cannot preview an app with no captured showcase', () => {
    expect(showcaseBindingFor({
      publication: pub({ showcaseRunId: undefined }), source: 'published',
    }).canPreview).toBe(false);

    expect(showcaseBindingFor({
      publication: pub({ showcaseInterfaceId: undefined }), source: 'published',
    }).canPreview).toBe(false);
  });
});
