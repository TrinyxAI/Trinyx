// @vitest-environment jsdom
/**
 * The header of a list while the user is inside a folder. Pins that it stays out of the way
 * at the top level, that every level of the trail navigates, that the back arrow goes to the
 * PARENT (not blindly to the top), which is the difference between "up one" and "out", and
 * that the crumb of the level being shown is the page rather than a link to itself.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ResourceFolder } from '@/lib/api/orchestrator/resource-folder.service';
import { FolderBreadcrumb } from '../FolderBreadcrumb';

vi.mock('@dnd-kit/core', () => ({
  useDroppable: () => ({ setNodeRef: () => {}, isOver: false }),
}));

afterEach(() => cleanup());

const folder = (id: string, name: string, parentFolderId: string | null = null): ResourceFolder => ({
  id,
  name,
  parentFolderId,
});

function renderTrail(trail: ResourceFolder[]) {
  const onNavigate = vi.fn();
  render(
    <FolderBreadcrumb
      trail={trail}
      rootLabel="All workflows"
      backLabel="Back"
      onNavigate={onNavigate}
    />,
  );
  return { onNavigate };
}

describe('FolderBreadcrumb', () => {
  it('shows nothing at the top level', () => {
    const { container } = render(
      <FolderBreadcrumb trail={[]} rootLabel="All workflows" backLabel="Back" onNavigate={vi.fn()} />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('shows the root crumb followed by every level', () => {
    renderTrail([folder('a', 'Marketing'), folder('b', 'Campaigns', 'a')]);

    expect(screen.getByRole('button', { name: 'All workflows' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Marketing' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Campaigns' })).toBeInTheDocument();
  });

  it('marks the folder being shown as the current page', () => {
    renderTrail([folder('a', 'Marketing'), folder('b', 'Campaigns', 'a')]);

    expect(screen.getByRole('button', { name: 'Campaigns' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: 'Marketing' })).not.toHaveAttribute('aria-current');
  });

  it('navigates to the level whose crumb is clicked', () => {
    const { onNavigate } = renderTrail([folder('a', 'Marketing'), folder('b', 'Campaigns', 'a')]);

    fireEvent.click(screen.getByRole('button', { name: 'Marketing' }));

    expect(onNavigate).toHaveBeenCalledWith('a');
  });

  it('goes back to the top level from the root crumb', () => {
    const { onNavigate } = renderTrail([folder('a', 'Marketing')]);

    fireEvent.click(screen.getByRole('button', { name: 'All workflows' }));

    expect(onNavigate).toHaveBeenCalledWith(null);
  });

  it('the back button goes UP ONE level, not to the top', () => {
    const { onNavigate } = renderTrail([folder('a', 'Marketing'), folder('b', 'Campaigns', 'a')]);

    fireEvent.click(screen.getByRole('button', { name: 'Back' }));

    expect(onNavigate).toHaveBeenCalledWith('a');
  });

  it('the back button leaves the folders entirely when there is only one level', () => {
    const { onNavigate } = renderTrail([folder('a', 'Marketing')]);

    fireEvent.click(screen.getByRole('button', { name: 'Back' }));

    expect(onNavigate).toHaveBeenCalledWith(null);
  });
  it('the crumb of the level being shown is not a link to itself', () => {
    renderTrail([folder('a', 'Marketing'), folder('b', 'Campaigns', 'a')]);

    expect(screen.getByRole('button', { name: 'Campaigns' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Marketing' })).toBeEnabled();
  });

  it('says what this level holds, under the path', () => {
    render(
      <FolderBreadcrumb
        trail={[folder('a', 'Marketing')]}
        rootLabel="All workflows"
        backLabel="Back"
        subtitle="6 workflows"
        onNavigate={vi.fn()}
      />,
    );

    expect(screen.getByText('6 workflows')).toBeInTheDocument();
  });
});
