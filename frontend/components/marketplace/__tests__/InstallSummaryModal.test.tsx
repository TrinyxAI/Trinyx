// @vitest-environment jsdom
/**
 * Post-install summary: the screen that finally tells the user what an install put in
 * their workspace (the app plus the interfaces / tables / agents it brought).
 *
 * It renders the counts the BACKEND reported creating - not the publication's advertised
 * metadata - so the list matches what actually exists after the clone.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

// Translations echo "key" or "key:{json args}" so assertions can see the interpolations.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, args?: Record<string, unknown>) =>
    args ? `${key}:${JSON.stringify(args)}` : key,
}));

import { InstallSummaryModal, InstalledResourcesList } from '../InstallSummaryModal';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'Gallery',
    displayMode: 'APPLICATION',
    creditsPerUse: 0,
    publisherId: 'pub-user',
    ...overrides,
  } as WorkflowPublication;
}

beforeEach(() => {
  vi.clearAllMocks();
  cleanup();
});

describe('InstalledResourcesList', () => {
  it('lists the primary item first, then every resource the install created', () => {
    render(<InstalledResourcesList publication={pub()} resources={{ interfaces: 2, tables: 1 }} />);

    const items = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(items).toEqual([
      'installedPrimaryApplication',
      'installedInterfaces:{"count":2}',
      'installedTables:{"count":1}',
    ]);
  });

  it('names the primary item after the publication type (an agent is not an application)', () => {
    render(
      <InstalledResourcesList
        publication={pub({ publicationType: 'AGENT', displayMode: 'AGENT' })}
        resources={{ agents: 3 }}
      />
    );

    const items = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(items).toEqual(['installedPrimaryAgent', 'installedAgents:{"count":3}']);
  });

  it('shows only the primary item when the install created nothing else', () => {
    render(<InstalledResourcesList publication={pub()} resources={{}} />);

    expect(screen.getAllByRole('listitem')).toHaveLength(1);
  });

  it('ignores a resource type it has no label for rather than showing a raw key', () => {
    render(<InstalledResourcesList publication={pub()} resources={{ interfaces: 1, gadgets: 4 } as never} />);

    const items = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(items).toEqual(['installedPrimaryApplication', 'installedInterfaces:{"count":1}']);
  });
});

describe('InstallSummaryModal', () => {
  it('names the installed publication and lists what it created', () => {
    render(<InstallSummaryModal publication={pub()} resources={{ tables: 2 }} onClose={() => {}} />);

    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('installedTitle')).toBeInTheDocument();
    expect(screen.getByText('installedIntro:{"title":"Gallery"}')).toBeInTheDocument();
    expect(screen.getByText('installedTables:{"count":2}')).toBeInTheDocument();
  });

  it('closes on the Close button', () => {
    const onClose = vi.fn();
    render(<InstallSummaryModal publication={pub()} resources={{}} onClose={onClose} />);

    fireEvent.click(screen.getByRole('button', { name: 'close' }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on Escape and on a backdrop click, so it can never trap the user', () => {
    const onClose = vi.fn();
    const { rerender } = render(
      <InstallSummaryModal publication={pub()} resources={{}} onClose={onClose} />
    );

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);

    // The backdrop is the dialog's parent; clicking the dialog itself must NOT close it.
    fireEvent.click(screen.getByRole('dialog'));
    expect(onClose).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('dialog').parentElement!);
    expect(onClose).toHaveBeenCalledTimes(2);

    // The listener is removed with the modal.
    rerender(<></>);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('renders the open action only when the caller supplies a destination', () => {
    const onOpen = vi.fn();
    const { rerender } = render(
      <InstallSummaryModal publication={pub()} resources={{}} onClose={() => {}} />
    );
    expect(screen.queryByRole('button', { name: 'goToApplications' })).not.toBeInTheDocument();

    rerender(
      <InstallSummaryModal publication={pub()} resources={{}} onClose={() => {}} onOpen={onOpen} />
    );
    fireEvent.click(screen.getByRole('button', { name: 'goToApplications' }));
    expect(onOpen).toHaveBeenCalledTimes(1);
  });
});

/**
 * The editable WORKFLOW copy the install-time opt-in can create.
 *
 * It is NOT one of the app's own resources: it is a second workflow of the user's own,
 * so it gets its own line, below the app's list, with its own open action. And because
 * the copy is best-effort, a failure is reported as a note here rather than anywhere
 * that would suggest the install itself failed.
 */
describe('InstalledResourcesList - the install-time editable copy', () => {
  it('says nothing at all when no copy was requested', () => {
    render(<InstalledResourcesList publication={pub()} resources={{}} />);

    expect(screen.queryByTestId('installed-open-editable-copy')).not.toBeInTheDocument();
    expect(screen.queryByTestId('installed-editable-copy-failed')).not.toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
  });

  it('adds the copy AFTER the app resources, with an open action when the caller can navigate', () => {
    const onOpenEditableCopy = vi.fn();
    render(
      <InstalledResourcesList
        publication={pub()}
        resources={{ interfaces: 1 }}
        editableCopy={{ workflowId: 'wf-copy', failed: false }}
        onOpenEditableCopy={onOpenEditableCopy}
      />,
    );

    const items = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(items).toEqual([
      'installedPrimaryApplication',
      'installedInterfaces:{"count":1}',
      'editableCopy.openCreated',
    ]);
    fireEvent.click(screen.getByTestId('installed-open-editable-copy'));
    expect(onOpenEditableCopy).toHaveBeenCalledTimes(1);
  });

  it('still names the copy when the caller offers no destination (no dead button)', () => {
    render(
      <InstalledResourcesList
        publication={pub()}
        resources={{}}
        editableCopy={{ workflowId: 'wf-copy', failed: false }}
      />,
    );

    expect(screen.getByText('editableCopy.created')).toBeInTheDocument();
    expect(screen.queryByTestId('installed-open-editable-copy')).not.toBeInTheDocument();
  });

  it('reports a FAILED copy as a note, with no open action and no extra list item', () => {
    render(
      <InstalledResourcesList
        publication={pub()}
        resources={{}}
        editableCopy={{ workflowId: null, failed: true }}
        onOpenEditableCopy={vi.fn()}
      />,
    );

    expect(screen.getByTestId('installed-editable-copy-failed')).toBeInTheDocument();
    expect(screen.queryByTestId('installed-open-editable-copy')).not.toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(1); // the application alone
  });

  it('the summary modal forwards the copy through to the list', () => {
    const onOpenEditableCopy = vi.fn();
    render(
      <InstallSummaryModal
        publication={pub()}
        resources={{}}
        editableCopy={{ workflowId: 'wf-copy', failed: false }}
        onOpenEditableCopy={onOpenEditableCopy}
        onClose={() => {}}
      />,
    );

    fireEvent.click(screen.getByTestId('installed-open-editable-copy'));
    expect(onOpenEditableCopy).toHaveBeenCalledTimes(1);
  });
});
