// @vitest-environment jsdom
/**
 * The face of a folder of WORKFLOWS: a white sheet holding small workflow cards, each on its
 * own dotted canvas. Pins that it draws at most one per cell of the 3x2 face, that a
 * workflow with no icons still shows something (its name), and that an empty folder shows
 * the folder mark rather than a grid of blanks.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import type { FolderPreviewItem } from '@/lib/api/orchestrator/resource-folder.service';
import { WorkflowFolderFace } from '../WorkflowFolderFace';

// The real icon row resolves node types against the builder registry; here we only care
// that it is handed the workflow's icons.
vi.mock('@/components/WorkflowNodeIcons', () => ({
  WorkflowNodeIcons: ({ nodeIcons }: { nodeIcons?: unknown[] }) => (
    <div data-testid="node-icons">{nodeIcons?.length ?? 0}</div>
  ),
}));

afterEach(() => cleanup());

const item = (id: string, icons?: Array<Record<string, unknown>>): FolderPreviewItem => ({
  id,
  name: `Workflow ${id}`,
  icons,
});

describe('WorkflowFolderFace', () => {
  it('draws one mini card per previewed workflow', () => {
    render(<WorkflowFolderFace preview={[item('a', [{}]), item('b', [{}, {}])]} />);

    expect(screen.getAllByTestId('node-icons')).toHaveLength(2);
  });

  it('never draws more than the six cells of the face, however many the folder holds', () => {
    const preview = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'].map((id) => item(id, [{}]));

    render(<WorkflowFolderFace preview={preview} />);

    expect(screen.getAllByTestId('node-icons')).toHaveLength(6);
  });

  it('falls back to the workflow name when it has no node icons', () => {
    render(<WorkflowFolderFace preview={[item('a')]} />);

    expect(screen.getByText('Workflow a')).toBeInTheDocument();
    expect(screen.queryByTestId('node-icons')).not.toBeInTheDocument();
  });

  it('shows the folder mark instead of an empty grid when the folder is empty', () => {
    const { container } = render(<WorkflowFolderFace preview={[]} />);

    expect(screen.queryByTestId('node-icons')).not.toBeInTheDocument();
    expect(container.querySelector('svg')).toBeInTheDocument();
  });
});
