// @vitest-environment jsdom
/**
 * The folder tile on a resource list: what it says about itself (name, how much it holds,
 * how many folders are nested in it), that it opens by click AND by keyboard, and that its
 * rename/delete affordances are absent - not merely hidden - on a read-only surface.
 *
 * dnd-kit is stubbed to inert refs so the tile renders without a DndContext; the actual
 * drag-to-file behaviour is exercised in the WorkflowTable test.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { ResourceFolderTile as FolderTileData } from '@/lib/api/orchestrator/resource-folder.service';
import { ResourceFolderTile } from '../ResourceFolderTile';

vi.mock('@dnd-kit/core', () => ({
  useDroppable: () => ({ setNodeRef: () => {}, isOver: false }),
  useDraggable: () => ({ setNodeRef: () => {}, attributes: {}, listeners: {}, isDragging: false }),
}));

afterEach(() => cleanup());

function folder(overrides: Partial<FolderTileData> = {}): FolderTileData {
  return {
    id: 'f1',
    name: 'Marketing',
    parentFolderId: null,
    itemCount: 3,
    subfolderCount: 0,
    lastModifiedAt: '2026-06-01T00:00:00Z',
    lastActivityAt: null,
    activityCount: null,
    preview: [],
    ...overrides,
  };
}

function renderTile(props: Partial<React.ComponentProps<typeof ResourceFolderTile>> = {}) {
  const onOpen = props.onOpen ?? vi.fn();
  render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <ResourceFolderTile
        folder={props.folder ?? folder()}
        face={props.face ?? <div data-testid="face" />}
        countLabel={props.countLabel ?? '3 workflows'}
        onOpen={onOpen}
        onRename={props.onRename}
        onDelete={props.onDelete}
      />
    </NextIntlClientProvider>,
  );
  return { onOpen };
}

describe('ResourceFolderTile', () => {
  it('shows the folder name and what it holds', () => {
    renderTile();

    expect(screen.getByText('Marketing')).toBeInTheDocument();
    expect(screen.getByText(/3 workflows/)).toBeInTheDocument();
  });

  it('adds the nested-folder count only when there are subfolders', () => {
    renderTile({ folder: folder({ subfolderCount: 2 }) });
    expect(screen.getByText('3 workflows · 2 folders')).toBeInTheDocument();

    cleanup();
    renderTile();
    expect(screen.queryByText(/folders$/)).not.toBeInTheDocument();
  });

  it('renders the face it was given, so each list draws its own style', () => {
    renderTile({ face: <div data-testid="workflow-face" /> });

    expect(screen.getByTestId('workflow-face')).toBeInTheDocument();
  });

  it('opens on click', () => {
    const { onOpen } = renderTile();

    fireEvent.click(screen.getByRole('button', { name: 'Marketing' }));

    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ id: 'f1' }));
  });

  it('opens on Enter, so the grid is reachable without a pointer', () => {
    const { onOpen } = renderTile();

    fireEvent.keyDown(screen.getByRole('button', { name: 'Marketing' }), { key: 'Enter' });

    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('offers rename and delete when the caller can write', () => {
    const onRename = vi.fn();
    const onDelete = vi.fn();
    renderTile({ onRename, onDelete });

    fireEvent.click(screen.getByRole('button', { name: 'Rename folder' }));
    fireEvent.click(screen.getByRole('button', { name: 'Delete folder' }));

    expect(onRename).toHaveBeenCalledWith(expect.objectContaining({ id: 'f1' }));
    expect(onDelete).toHaveBeenCalledWith(expect.objectContaining({ id: 'f1' }));
  });

  it('has no rename or delete affordance at all on a read-only surface', () => {
    renderTile();

    expect(screen.queryByRole('button', { name: 'Rename folder' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete folder' })).not.toBeInTheDocument();
  });

  it('does not open the folder when the rename button is used', () => {
    const onOpen = vi.fn();
    renderTile({ onOpen, onRename: vi.fn() });

    fireEvent.click(screen.getByRole('button', { name: 'Rename folder' }));

    expect(onOpen).not.toHaveBeenCalled();
  });
});
