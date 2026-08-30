// @vitest-environment jsdom
/**
 * The marketplace canvas: a node that produced a file must show its pill.
 *
 * On a publication preview every live read is disabled - the visitor is anonymous and the run
 * endpoints are tenant-scoped - so `useRunOutputData` resolves nothing and the canvas of a
 * PUBLISHED workflow showed no file under any node, however many the run produced. The
 * publication carries its own frozen copy instead, and this suite pins that FileNodePreview
 * actually falls back to it (the hook's own suite pins the fetch and the epoch/alias lookup).
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';

let showcaseMap: Record<string, Record<string, { name: string; mimeType: string; size: number; url: string }>>;
let liveOutput: Record<string, unknown> | null;

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: true, workflowId: 'wf-1', runId: 'showcase_1', viewingEpoch: 1 }),
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [{ runStatus: 'completed', completedSteps: new Set(['a']), failedSteps: new Set(), skippedSteps: new Set() }],
}));
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
  useQuery: () => ({ data: undefined }),
}));
// In a preview this hook's queries are all disabled: no items, and nothing to lazily load.
vi.mock('../../../hooks/useRunOutputData', () => ({
  useRunOutputData: () => ({
    totalItems: liveOutput ? 1 : 0,
    currentIndex: 0,
    currentItem: liveOutput ? { id: 'item-1', metadata: null } : null,
    goToIndex: vi.fn(),
    getObjectAtPath: () => Promise.resolve(liveOutput),
  }),
}));
vi.mock('../../../hooks/useShowcaseStepFiles', async (importOriginal) => {
  // Keep the real selector - the wiring under test is "the right node reads the right entry".
  const actual = await importOriginal<typeof import('../../../hooks/useShowcaseStepFiles')>();
  return { ...actual, useShowcaseStepFiles: () => showcaseMap };
});
vi.mock('@/hooks/useAuthedObjectUrl', () => ({
  useAuthedObjectUrl: () => ({ url: null, loading: false, error: false }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => ({ openTab: vi.fn() }) }));
vi.mock('@/lib/sidePanel/openFilesPanel', () => ({ openFilesPanel: vi.fn() }));
vi.mock('reactflow', () => ({ useNodeId: () => 'n1' }));
vi.mock('@/lib/api/orchestrator/file.service', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api/orchestrator/file.service')>();
  return { ...actual, fileRefToUrl: () => '/api/proxy/files/by-id/live/raw' };
});

import { FileNodePreview } from '../FileNodePreview';

const SIGNED_URL = '/api/files/proxy-signed?key=_publications%2Fp%2Fclip.mp4&exp=1&sig=abc';
const showcaseFile = { name: 'clip.mp4', mimeType: 'video/mp4', size: 2048, url: SIGNED_URL };

const preview = (label = 'Download File', setCurrentFile: (f: unknown) => void = vi.fn()) => (
  <FileNodePreview
    data={{ id: 'n1', label, kind: 'media', status: 'completed' } as never}
    setCurrentFile={setCurrentFile as never}
    selected={false}
    isStaticFileProducingNode
  />
);

beforeEach(() => {
  liveOutput = null;
  showcaseMap = { '1': { download_file: showcaseFile } };
});

describe('FileNodePreview - showcase fallback', () => {
  it('shows the frozen file under the node when no live output can be read', async () => {
    const c = render(preview());
    await waitFor(() => expect(c.queryByText('clip.mp4')).not.toBeNull());
    // The pill is LABELLED, not a bare thumbnail: it carries the name and the human size.
    expect(c.getByText('2.0 KB')).not.toBeNull();
  });

  it('matches the node by its normalized label, the way the step alias was stored', async () => {
    // The node's label is "Download File"; the alias frozen in the snapshot is "download_file".
    // Looking up the raw label would find nothing and the pill would silently not render.
    const c = render(preview('Download File'));
    await waitFor(() => expect(c.queryByText('clip.mp4')).not.toBeNull());
  });

  it('shows nothing for a node the run produced no file for', () => {
    const c = render(preview('Fetch Json'));
    expect(c.container.firstChild).toBeNull();
  });

  it('shows nothing when the publication carries no frozen files at all', () => {
    showcaseMap = {};
    const c = render(preview());
    expect(c.container.firstChild).toBeNull();
  });

  it('the expanded preview loads the signed URL directly, with no authenticated fetch', async () => {
    const c = render(preview());
    await waitFor(() => expect(c.queryByText('clip.mp4')).not.toBeNull());
    c.getByLabelText('expand').click();
    await waitFor(() => expect(c.container.querySelector('video')).not.toBeNull());
    expect(c.container.querySelector('video')!.getAttribute('src')).toBe(SIGNED_URL);
  });

  it('publishes a file with no storage path to the node, so nothing offers a Files panel', async () => {
    // FlowNode keys the bottom bar off this value; the strip itself drops its open-in-Files
    // control for a file that has no storage row behind it.
    const setCurrentFile = vi.fn();
    const c = render(preview('Download File', setCurrentFile));
    await waitFor(() => expect(c.queryByText('clip.mp4')).not.toBeNull());
    expect(setCurrentFile).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'clip.mp4', path: '', id: undefined }),
    );
    expect(c.queryByLabelText('openInPanel')).toBeNull();
  });

  it('a live output still wins over the frozen copy - the fallback never masks the real file', async () => {
    liveOutput = {
      _status: 'COMPLETED',
      file: {
        _type: 'file',
        path: '1/wf/run/live.mp4',
        name: 'live.mp4',
        mimeType: 'video/mp4',
        size: 99,
        id: 'file-live',
      },
    };
    const c = render(preview());
    await waitFor(() => expect(c.queryByText('live.mp4')).not.toBeNull());
    expect(c.queryByText('clip.mp4')).toBeNull();
    // And it keeps its way into the Files panel, which the frozen copy has no handle for.
    expect(c.getByLabelText('openInPanel')).not.toBeNull();
  });
});
