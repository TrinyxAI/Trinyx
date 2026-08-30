// @vitest-environment jsdom
/**
 * The face of a folder of APPLICATIONS. It used to draw one letter per app, so a folder of
 * apps said nothing but how many; it now renders each app's live showcase, the same one its
 * card shows, and keeps the cover only for an app that has no showcase or whose render fails.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, render, screen } from '@testing-library/react';
import type { FolderPreviewItem } from '@/lib/api/orchestrator/resource-folder.service';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

const captured = vi.hoisted(() => ({
  calls: [] as Array<Record<string, unknown>>,
}));

// The real preview fetches a render and mounts an iframe; capture the routing props instead.
vi.mock('@/components/marketplace/ShowcasePreview', () => ({
  ShowcasePreview: (props: Record<string, unknown>) => {
    captured.calls.push(props);
    return <div data-testid="showcase" data-run={String(props.runId ?? '')} />;
  },
}));

import { ApplicationFolderFace } from '../ApplicationFolderFace';

afterEach(() => {
  cleanup();
  captured.calls = [];
});

const item = (id: string): FolderPreviewItem => ({ id, name: `App ${id}` });

const publication = (id: string, extra: Partial<WorkflowPublication> = {}): WorkflowPublication => ({
  id,
  title: `App ${id}`,
  showcaseRunId: `run-${id}`,
  showcaseInterfaceId: `iface-${id}`,
  ...extra,
} as WorkflowPublication);

const publishedApp = (id: string) => ({ publication: publication(id), source: 'published' as const });

describe('ApplicationFolderFace', () => {
  it('renders the live showcase of each app the folder holds', () => {
    render(
      <ApplicationFolderFace
        preview={[item('a'), item('b')]}
        resolveApp={(id) => publishedApp(id)}
      />,
    );

    const cells = screen.getAllByTestId('showcase');
    expect(cells).toHaveLength(2);
    // Each cell shows that app's own run, not a letter.
    expect(cells.map((el) => el.getAttribute('data-run'))).toEqual(['run-a', 'run-b']);
    expect(screen.queryByText('A')).not.toBeInTheDocument();
  });

  it('starts every preview muted - a grid of tiles must not speak on load', () => {
    render(<ApplicationFolderFace preview={[item('a')]} resolveApp={(id) => publishedApp(id)} />);

    expect(captured.calls[0].mediaMuted).toBe(true);
  });

  it('routes an ACQUIRED app through the publisher-frozen showcase, not its own run', () => {
    render(
      <ApplicationFolderFace
        preview={[item('a')]}
        resolveApp={(id) => ({ publication: publication(id), source: 'acquired' as const })}
      />,
    );

    // The run + interface belong to the PUBLISHER, so the read goes to the publication.
    expect(captured.calls[0].publicationId).toBe('a');
    expect(captured.calls[0].authenticated).toBe(true);
  });

  it('falls back to the app cover when the page cannot resolve the app', () => {
    render(<ApplicationFolderFace preview={[item('a')]} />);

    expect(screen.queryByTestId('showcase')).not.toBeInTheDocument();
    expect(screen.getByText('A')).toBeInTheDocument();
  });

  it('falls back to the app cover when the app has no captured showcase', () => {
    render(
      <ApplicationFolderFace
        preview={[item('a')]}
        resolveApp={(id) => ({
          publication: { id, title: `App ${id}` } as WorkflowPublication,
          source: 'published' as const,
        })}
      />,
    );

    expect(screen.queryByTestId('showcase')).not.toBeInTheDocument();
    expect(screen.getByText('A')).toBeInTheDocument();
  });

  it('drops to the cover when the render fails, rather than leaving a hole in the face', () => {
    render(<ApplicationFolderFace preview={[item('a')]} resolveApp={(id) => publishedApp(id)} />);
    expect(screen.getByTestId('showcase')).toBeInTheDocument();

    act(() => (captured.calls[0].onError as (error: Error) => void)(new Error('retention expired')));

    expect(screen.queryByTestId('showcase')).not.toBeInTheDocument();
    expect(screen.getByText('A')).toBeInTheDocument();
  });

  it('never draws more than the four cells of the face, however many the folder holds', () => {
    const preview = ['a', 'b', 'c', 'd', 'e', 'f'].map(item);

    render(<ApplicationFolderFace preview={preview} resolveApp={(id) => publishedApp(id)} />);

    expect(screen.getAllByTestId('showcase')).toHaveLength(4);
  });

  it('lets clicks through the showcase, so the tile it sits on stays clickable and draggable', () => {
    // The showcase is an iframe: pointer events inside one never reach this document, so the
    // cell must not receive them at all. jsdom cannot reproduce that swallowing, so the
    // mechanism itself is what is pinned here.
    const { container } = render(
      <ApplicationFolderFace preview={[item('a')]} resolveApp={(id) => publishedApp(id)} />,
    );

    expect(container.querySelector('.pointer-events-none')).toContainElement(screen.getByTestId('showcase'));
    // ...while the cell around it still gets the hover, so the app's name is still readable.
    expect(container.querySelector('[title="App a"]')).not.toHaveClass('pointer-events-none');
  });

  it('shows the folder mark instead of an empty grid when the folder is empty', () => {
    const { container } = render(<ApplicationFolderFace preview={[]} />);

    expect(screen.queryByTestId('showcase')).not.toBeInTheDocument();
    expect(container.querySelector('svg')).toBeInTheDocument();
  });
});

describe('ApplicationFolderFace - the shape of the face', () => {
  it('lays the applications out two across and two down', () => {
    const { container } = render(
      <ApplicationFolderFace
        preview={['a', 'b', 'c', 'd'].map(item)}
        resolveApp={(id) => publishedApp(id)}
      />,
    );

    const grid = container.querySelector('.grid');
    expect(grid).toHaveClass('grid-cols-2', 'grid-rows-2');
  });

  it('keeps the 2x2 rhythm when the folder holds fewer than four', () => {
    const { container } = render(
      <ApplicationFolderFace preview={[item('a')]} resolveApp={(id) => publishedApp(id)} />,
    );

    // The grid's own two rows and two columns hold the shape, so one app still lands in a
    // quarter of the face - with no filler drawn beside it, which would fill its whole cell
    // next to a preview that is letterboxed to keep the app's proportions.
    const grid = container.querySelector('.grid');
    expect(grid).toHaveClass('grid-cols-2', 'grid-rows-2');
    expect(grid?.children).toHaveLength(1);
    expect(screen.getAllByTestId('showcase')).toHaveLength(1);
  });
});
