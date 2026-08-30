/**
 * @vitest-environment jsdom
 *
 * An application opened in the right side panel is a full application surface.
 *
 * It used to render ONE interface with no sub-tabs: no way to watch the workflow
 * run, no run history, no other page of a multi-page app, and the navigate link
 * between pages had nowhere to go (see WorkflowPanelContent.navigateAction).
 * It now hands the resolved publication to the same composition the sub-workflow
 * tab uses, with the application as its opening tab.
 *
 * This file pins the hand-off, not the panel it hands to: what the resolver
 * produces (every interface, the run it resolved, the template actions) and the
 * flags that make the Application the tab it opens on.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

/** Props the composition was mounted with. */
const mountedWith = vi.hoisted(() => ({ current: null as Record<string, any> | null }));
/** Marketplace-preview context, off by default. */
const previewCtx = vi.hoisted(() => ({ current: null as Record<string, unknown> | null }));
const snapshotCtx = vi.hoisted(() => ({ current: null as Record<string, unknown> | null }));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getPublicationById: vi.fn().mockResolvedValue({
      id: 'pub-1', workflowId: 'wf-1', title: 'App', showcaseInterfaceId: 'iface-1', ownedByMe: false,
    }),
    getAcquiredApplications: vi.fn().mockResolvedValue({
      applications: [{ sourcePublicationId: 'pub-1', workflowId: 'wf-clone' }],
    }),
    // The preview path reads the sanitized public record, never the tenant one.
    getPublicationByIdPublic: vi.fn().mockResolvedValue({
      id: 'pub-1', workflowId: 'wf-1', title: 'App', showcaseInterfaceId: 'iface-1',
      showcaseRunId: 'run-showcase',
      planSnapshot: { interfaces: [{ id: 'iface-1', label: 'Frozen', actionMapping: {} }] },
    }),
  },
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: {
    getApplicationRun: vi.fn().mockResolvedValue({ runId: 'run_abc' }),
    executeWorkflow: vi.fn(),
  },
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflow: vi.fn().mockResolvedValue({
      plan: {
        interfaces: [
          { id: 'iface-1', label: 'Home', actionMapping: { go: 'x' } },
          { id: 'iface-2', label: 'Details Page', isEntryInterface: true, actionMapping: {} },
        ],
      },
    }),
  },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => previewCtx.current,
  usePublicationSnapshot: () => snapshotCtx.current,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span /> }));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({
  normalizeLabel: (s: string) => s.toLowerCase().replace(/\s+/g, '_'),
}));
vi.mock('@/components/app/WorkflowBuilderPanelContent', () => ({
  WorkflowBuilderPanelContent: (props: Record<string, any>) => {
    mountedWith.current = props;
    return <div data-testid="workflow-panel" />;
  },
}));

import { ApplicationPanelContent } from '../ApplicationSidePanel';

describe('ApplicationSidePanel - composes the shared workflow panel', () => {
  beforeEach(() => {
    mountedWith.current = null;
    previewCtx.current = null;
    snapshotCtx.current = null;
  });
  afterEach(cleanup);

  it('opens on the application, on the run it resolved, for the acquired clone', async () => {
    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    expect(mountedWith.current!.workflowId).toBe('wf-clone');
    expect(mountedWith.current!.runId).toBe('run_abc');
    // Without this the panel opens on the canvas: the user asked for the app.
    expect(mountedWith.current!.applicationFirst).toBe(true);
    expect(mountedWith.current!.readOnly).toBe(false);
    // The acquired clone IS the caller's: it keeps the edit toggle and the
    // Share / Save / Run bar.
    expect(mountedWith.current!.canEditWorkflow).toBe(true);
  });

  it('offers no edit toggle and no actions when the publication resolves to someone else workflow', async () => {
    const { publicationService } = await import('@/lib/api/orchestrator/publication.service');
    vi.mocked(publicationService.getAcquiredApplications).mockResolvedValueOnce({ applications: [] } as never);
    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    // Falls back to the publisher's workflow: readable, but every save, run and
    // publish there would be refused.
    expect(mountedWith.current!.workflowId).toBe('wf-1');
    expect(mountedWith.current!.canEditWorkflow).toBe(false);
  });

  it('seeds EVERY page of the application, flagging the declared entry', async () => {
    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    // The single-interface panel could not show a second page at all.
    expect(mountedWith.current!.initialApplicationConfigs).toEqual([
      { interfaceId: 'iface-1', label: 'Home', actionMapping: { go: 'x' }, nodeId: 'interface:home', isEntryInterface: false },
      { interfaceId: 'iface-2', label: 'Details Page', actionMapping: {}, nodeId: 'interface:details_page', isEntryInterface: true },
    ]);
  });

  it('keeps the template actions of an installed application', async () => {
    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    expect(mountedWith.current!.applicationTemplateSource).toEqual({
      publicationId: 'pub-1', remote: false, canReset: true,
    });
    // Live surface: the canvas reads the tenant's own plan, never a frozen one.
    expect(mountedWith.current!.planOverride).toBeUndefined();
  });

  it('renders a preview read-only, off the frozen snapshot rather than the tenant plan', async () => {
    previewCtx.current = { remote: false };
    snapshotCtx.current = {
      planSnapshot: { interfaces: [{ id: 'iface-1', label: 'Frozen', actionMapping: {} }] },
    };

    render(<ApplicationPanelContent publicationId="pub-1" />);
    await waitFor(() => expect(mountedWith.current).not.toBeNull());

    expect(mountedWith.current!.readOnly, 'someone else frozen showcase').toBe(true);
    // The canvas must render the snapshot, never the tenant's live plan: a
    // preview visitor has no access to it.
    expect(mountedWith.current!.planOverride).toEqual({
      interfaces: [{ id: 'iface-1', label: 'Frozen', actionMapping: {} }],
    });
    // No template actions on a showcase clone.
    expect(mountedWith.current!.applicationTemplateSource).toBeUndefined();
  });
});
