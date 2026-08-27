// @vitest-environment jsdom
/**
 * The application settings cog and its single entry, "Create an editable copy".
 *
 * Installing used to mint this copy on its own, which cloned the app's whole resource
 * set a SECOND time (two interfaces, two tables, two agents in the user's lists). It is
 * now an explicit action; the server keeps it idempotent, so the menu must reflect "you
 * already had one" rather than pretending it just made another. The action lives behind
 * the cog (it used to sit inline in the Info tab), so the cog itself must not render at
 * all when there is nothing to act on.
 */
import '@testing-library/jest-dom/vitest';
import * as React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: (ns?: string) => (k: string) => `${ns}.${k}` }));
// Radix Popover needs pointer APIs jsdom lacks - render trigger + content inline.
vi.mock('@/components/ui/popover', () => ({
  Popover: ({ children }: any) => <div>{children}</div>,
  PopoverTrigger: ({ children }: any) => <>{children}</>,
  PopoverContent: ({ children }: any) => <div>{children}</div>,
}));
vi.mock('@/i18n/navigation', () => ({
  Link: ({ href, children, ...rest }: any) => <a href={href} {...rest}>{children}</a>,
}));

const createEditableWorkflowCopy = vi.hoisted(() => vi.fn());
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { createEditableWorkflowCopy },
}));

import { ApplicationSettingsMenu } from '../ApplicationSettingsMenu';

const copyButton = () => screen.getByTestId('application-settings-editable-copy');

function renderMenu(props: Partial<React.ComponentProps<typeof ApplicationSettingsMenu>> = {}) {
  return render(
    <ApplicationSettingsMenu publicationId="pub-1" canCreateEditableCopy {...props} />,
  );
}

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe('ApplicationSettingsMenu - create an editable copy', () => {
  it('creates the copy on request and links to it', async () => {
    createEditableWorkflowCopy.mockResolvedValue({ workflowId: 'wf-copy', title: 'Gallery', created: true });
    renderMenu();

    fireEvent.click(copyButton());

    await waitFor(() => expect(createEditableWorkflowCopy).toHaveBeenCalledWith('pub-1', false));
    expect(await screen.findByText('marketplace.editableCopy.created')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /editableCopy\.open/ })).toHaveAttribute('href', '/app/workflow/wf-copy');
  });

  it('says the copy already existed instead of claiming a new one (the action is idempotent)', async () => {
    createEditableWorkflowCopy.mockResolvedValue({ workflowId: 'wf-copy', title: 'Gallery', created: false });
    renderMenu();

    fireEvent.click(copyButton());

    expect(await screen.findByText('marketplace.editableCopy.existing')).toBeInTheDocument();
    expect(screen.queryByText('marketplace.editableCopy.created')).not.toBeInTheDocument();
  });

  it('surfaces a failure instead of leaving the user waiting, and keeps the action retryable', async () => {
    createEditableWorkflowCopy.mockRejectedValue(new Error('boom'));
    renderMenu();

    fireEvent.click(copyButton());

    expect(await screen.findByText('marketplace.editableCopy.error')).toBeInTheDocument();
    expect(copyButton()).toBeEnabled();
  });

  it('stays quiet on a workflow-QUOTA refusal: the global plan-limit toast owns that message', async () => {
    // apiClient raises the platform-wide upgrade toast for 409 PLAN_RESOURCE_LIMIT_EXCEEDED.
    // Adding a second, vaguer local message ("could not be created") on top of it is the
    // house anti-pattern - the menu resets and lets the toast speak.
    const quota = Object.assign(new Error('limit'), { status: 409, code: 'PLAN_RESOURCE_LIMIT_EXCEEDED' });
    createEditableWorkflowCopy.mockRejectedValue(quota);
    renderMenu();

    fireEvent.click(copyButton());

    await waitFor(() => expect(createEditableWorkflowCopy).toHaveBeenCalled());
    await waitFor(() => expect(copyButton()).toBeEnabled());
    expect(screen.queryByText('marketplace.editableCopy.error')).not.toBeInTheDocument();
    expect(screen.getByText('marketplace.editableCopy.description')).toBeInTheDocument();
  });

  it('routes a cloud-linked CE install through the remote endpoint', async () => {
    createEditableWorkflowCopy.mockResolvedValue({ workflowId: 'wf-copy', created: true });
    renderMenu({ remote: true });

    fireEvent.click(copyButton());

    await waitFor(() => expect(createEditableWorkflowCopy).toHaveBeenCalledWith('pub-1', true));
  });

  it('disables the entry while the copy is being created (no double request)', async () => {
    createEditableWorkflowCopy.mockReturnValue(new Promise(() => {})); // never resolves
    renderMenu();

    fireEvent.click(copyButton());

    await waitFor(() => expect(copyButton()).toBeDisabled());
    fireEvent.click(copyButton());
    expect(createEditableWorkflowCopy).toHaveBeenCalledTimes(1);
    expect(screen.getByText('marketplace.editableCopy.creating')).toBeInTheDocument();
  });

});

describe('ApplicationSettingsMenu - the cog only exists when it has something to do', () => {
  it('renders nothing at all when the editable copy is not available (no empty menu, no dead cog)', () => {
    renderMenu({ canCreateEditableCopy: false });

    expect(screen.queryByTestId('application-settings-trigger')).not.toBeInTheDocument();
    expect(screen.queryByTestId('application-settings-editable-copy')).not.toBeInTheDocument();
  });

  it('renders the cog when the copy is available', () => {
    renderMenu();

    expect(screen.getByTestId('application-settings-trigger')).toBeInTheDocument();
  });
});
