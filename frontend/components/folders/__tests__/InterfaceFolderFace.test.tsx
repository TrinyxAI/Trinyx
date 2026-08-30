// @vitest-environment jsdom
/**
 * The face of a folder of PAGES. It used to draw a grey silhouette per page, so every folder
 * of pages looked like every other one; it now renders the pages themselves, at their own
 * format, and keeps the silhouette only for a page whose template has not arrived.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import type { FolderPreviewItem } from '@/lib/api/orchestrator/resource-folder.service';

// The real thumbnail mounts a sandboxed iframe; here we only care that it is handed the
// page's html and the viewport of the page's own format.
vi.mock('@/app/workflows/builder/components/interface/InterfaceThumbnail', () => ({
  InterfaceThumbnail: ({ htmlTemplate, viewport, className }: { htmlTemplate: string; viewport?: { width: number; height: number }; className?: string }) => (
    <div
      data-testid="thumbnail"
      className={className}
      data-html={htmlTemplate}
      data-viewport={`${viewport?.width}x${viewport?.height}`}
    />
  ),
}));

import { InterfaceFolderFace, INTERFACE_FACE_CELLS } from '../InterfaceFolderFace';

afterEach(() => cleanup());

const item = (id: string, format?: string): FolderPreviewItem => ({
  id,
  name: `Page ${id}`,
  subtitle: format,
});

const templates = (entries: Record<string, string>) =>
  new Map(Object.entries(entries).map(([id, htmlTemplate]) => [id, { htmlTemplate }]));

describe('InterfaceFolderFace', () => {
  it('renders the page itself once its template is loaded', () => {
    render(<InterfaceFolderFace preview={[item('a')]} templates={templates({ a: '<h1>Hello</h1>' })} />);

    expect(screen.getByTestId('thumbnail')).toHaveAttribute('data-html', '<h1>Hello</h1>');
    // The silhouette's name plate is gone: the render itself says which page this is.
    expect(screen.queryByText('Page a')).not.toBeInTheDocument();
  });

  it('renders each page at the viewport of its OWN declared format', () => {
    render(
      <InterfaceFolderFace
        preview={[item('a', 'vertical'), item('b', 'desktop')]}
        templates={templates({ a: '<p>a</p>', b: '<p>b</p>' })}
      />,
    );

    const [first, second] = screen.getAllByTestId('thumbnail');
    expect(first).toHaveAttribute('data-viewport', '1080x1920');
    expect(second).toHaveAttribute('data-viewport', '1440x900');
  });

  it('keeps the named silhouette for a page whose template has not arrived yet', () => {
    render(<InterfaceFolderFace preview={[item('a'), item('b')]} templates={templates({ a: '<p>a</p>' })} />);

    expect(screen.getAllByTestId('thumbnail')).toHaveLength(1);
    expect(screen.getByText('Page b')).toBeInTheDocument();
  });

  it('treats a blank template as not-loaded rather than rendering an empty frame', () => {
    render(<InterfaceFolderFace preview={[item('a')]} templates={templates({ a: '   ' })} />);

    expect(screen.queryByTestId('thumbnail')).not.toBeInTheDocument();
    expect(screen.getByText('Page a')).toBeInTheDocument();
  });

  it('renders nothing at all when no template is known (the list has not loaded any)', () => {
    render(<InterfaceFolderFace preview={[item('a')]} />);

    expect(screen.queryByTestId('thumbnail')).not.toBeInTheDocument();
    expect(screen.getByText('Page a')).toBeInTheDocument();
  });

  it('never draws more than the cells of the face, however many the folder holds', () => {
    const preview = ['a', 'b', 'c', 'd', 'e', 'f'].map((id) => item(id));
    const loaded = templates(Object.fromEntries(preview.map((p) => [p.id, `<p>${p.id}</p>`])));

    render(<InterfaceFolderFace preview={preview} templates={loaded} />);

    expect(screen.getAllByTestId('thumbnail')).toHaveLength(INTERFACE_FACE_CELLS);
  });

  it('lets clicks through the render, so the tile it sits on stays clickable and draggable', () => {
    // The render is an iframe: pointer events inside one never reach this document, so the
    // cell must not receive them at all. jsdom cannot reproduce that swallowing, so the
    // mechanism itself is what is pinned here.
    const { container } = render(
      <InterfaceFolderFace preview={[item('a')]} templates={templates({ a: '<p>a</p>' })} />,
    );

    expect(screen.getByTestId('thumbnail')).toHaveClass('pointer-events-none');
    // ...while the cell around it still gets the hover, so the page's name is still readable.
    expect(container.querySelector('[title="Page a"]')).not.toHaveClass('pointer-events-none');
  });

  it('shows the folder mark instead of an empty grid when the folder is empty', () => {
    const { container } = render(<InterfaceFolderFace preview={[]} />);

    expect(screen.queryByTestId('thumbnail')).not.toBeInTheDocument();
    expect(container.querySelector('svg')).toBeInTheDocument();
  });
});

describe('InterfaceFolderFace - the shape of the face', () => {
  it('lays the pages out two across and two down', () => {
    const { container } = render(
      <InterfaceFolderFace
        preview={[item('a'), item('b'), item('c'), item('d')]}
        templates={templates({ a: '<p>a</p>', b: '<p>b</p>', c: '<p>c</p>', d: '<p>d</p>' })}
      />,
    );

    const grid = container.querySelector('.grid');
    expect(grid).toHaveClass('grid-cols-2', 'grid-rows-2');
  });

  it('keeps the 2x2 rhythm when the folder holds fewer than four', () => {
    const { container } = render(
      <InterfaceFolderFace preview={[item('a')]} templates={templates({ a: '<p>a</p>' })} />,
    );

    // The grid's own two rows and two columns hold the shape, so one page still lands in a
    // quarter of the face - with no filler drawn beside it.
    const grid = container.querySelector('.grid');
    expect(grid).toHaveClass('grid-cols-2', 'grid-rows-2');
    expect(grid?.children).toHaveLength(1);
    expect(screen.getAllByTestId('thumbnail')).toHaveLength(1);
  });
});
