// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import en from '@/messages/en.json';

/**
 * Renaming a credential from Settings > Credentials.
 *
 * What these tests pin:
 *   1) the pencil opens a dialog prefilled with the current name,
 *   2) saving calls `renameCredential(id, trimmedName)`, updates the row in place
 *      (no refetch) and refreshes the credential caches every picker reads,
 *   3) a name that is blank, unchanged or over the 255-char column limit never
 *      reaches the API,
 *   4) each server refusal gets its OWN message: a name already taken
 *      (`duplicate_name`) and a credential whose name is its identity
 *      (`name_is_identity`), never the generic failure,
 *   5) a credential with no `integration` offers no rename affordance at all,
 *      because the server would refuse it.
 *
 * Messages come from the REAL `en.json`, not a hand-written subset: a key renamed
 * in the component and in a local fixture but not in the locale files would
 * otherwise ship green (the i18n parity test compares locales to each other, never
 * to component usage).
 */

vi.mock('@/components/ui/service-icon', () => ({
  ServiceIcon: () => null,
}));

vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ open, children }: { open: boolean; children: React.ReactNode }) =>
    open ? <div role="dialog">{children}</div> : null,
  DialogContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogHeader: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogFooter: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogTitle: ({ children }: { children: React.ReactNode }) => <h2>{children}</h2>,
  DialogDescription: ({ children }: { children: React.ReactNode }) => <p>{children}</p>,
}));

// Radix Select needs ResizeObserver / pointer APIs jsdom lacks; the default
// filter is irrelevant to renaming.
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectValue: ({ placeholder }: { placeholder?: string }) => <span>{placeholder}</span>,
}));

const { getCredentialsMock, renameCredentialMock, invalidateCachesMock } = vi.hoisted(() => ({
  getCredentialsMock: vi.fn(),
  renameCredentialMock: vi.fn(),
  invalidateCachesMock: vi.fn().mockResolvedValue(undefined),
}));

vi.mock('@/lib/credentials/invalidateCredentialCaches', () => ({
  invalidateCredentialCaches: invalidateCachesMock,
}));

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getCredentials: getCredentialsMock,
    renameCredential: renameCredentialMock,
  },
}));

import { MyCredentialsList } from '../MyCredentialsList';

function credential(overrides: Record<string, unknown> = {}) {
  return {
    id: 42,
    tenant_id: 'tenant-1',
    name: 'gmail Credential',
    integration: 'gmail',
    type: 'OAuth2',
    environment: 'Production',
    status: 'active',
    credential_data: {},
    scopes: [],
    tags: [],
    is_default: true,
    last_used: null,
    created_at: '2026-05-04T10:00:00Z',
    updated_at: '2026-05-04T10:00:00Z',
    ...overrides,
  };
}

const toasts: Array<{ type?: string; title?: string; message?: string }> = [];

function renderList() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={en as any}>
        <MyCredentialsList addToast={(toast) => { toasts.push(toast); }} />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

/** Reads a message straight from en.json so a copy change never silently unpins a test. */
function msg(key: string): string {
  return key
    .split('.')
    .reduce<any>((node, part) => node?.[part], (en as any).credentials.myCredentials);
}

async function openRenameDialog() {
  renderList();
  const pencil = await screen.findByRole('button', { name: msg('renameAriaLabel').replace('{name}', 'gmail Credential') });
  fireEvent.click(pencil);
  return screen.getByRole('dialog');
}

describe('MyCredentialsList - rename', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    toasts.length = 0;
    getCredentialsMock.mockResolvedValue({
      credentials: [credential()],
      page: 1,
      pageSize: 10,
      totalItems: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    });
  });
  afterEach(() => cleanup());

  it('opens a dialog prefilled with the current name', async () => {
    await openRenameDialog();

    expect(screen.getByText(msg('renameDialog.title'))).toBeTruthy();
    expect((screen.getByLabelText(msg('renameDialog.label')) as HTMLInputElement).value).toBe('gmail Credential');
  });

  it('saves the trimmed name and updates the row in place without refetching', async () => {
    renameCredentialMock.mockResolvedValue(credential({ name: 'Gmail (work)' }));
    await openRenameDialog();

    fireEvent.change(screen.getByLabelText(msg('renameDialog.label')), { target: { value: '  Gmail (work)  ' } });
    fireEvent.click(screen.getByRole('button', { name: msg('renameDialog.save') }));

    await waitFor(() => expect(renameCredentialMock).toHaveBeenCalledWith(42, 'Gmail (work)'));
    // Row shows the new name, and the list was NOT refetched (one initial load only).
    expect(await screen.findByText('Gmail (work)')).toBeTruthy();
    expect(getCredentialsMock).toHaveBeenCalledTimes(1);
    // Dialog closed on success, and the toast names the new label.
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(toasts).toContainEqual(
      expect.objectContaining({
        type: 'success',
        title: msg('renameDialog.successTitle'),
        message: msg('renameDialog.success').replace('{name}', 'Gmail (work)'),
      }),
    );
  });

  it('disables Save on a blank name', async () => {
    await openRenameDialog();

    fireEvent.change(screen.getByLabelText(msg('renameDialog.label')), { target: { value: '   ' } });

    expect((screen.getByRole('button', { name: msg('renameDialog.save') }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('refuses a blank name submitted with Enter, which bypasses the disabled Save button', async () => {
    await openRenameDialog();

    const input = screen.getByLabelText(msg('renameDialog.label'));
    fireEvent.change(input, { target: { value: '   ' } });
    // onKeyDown calls handleRename directly, so this is the one path that reaches
    // the blank guard. Clicking Save cannot: React does not fire onClick on a
    // disabled button, which would make this test pass with the guard deleted.
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(renameCredentialMock).not.toHaveBeenCalled();
    expect(screen.getByText(msg('renameDialog.emptyName'))).toBeTruthy();
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByText('gmail Credential')).toBeTruthy();
  });

  it('submits with Enter on a valid name', async () => {
    renameCredentialMock.mockResolvedValue(credential({ name: 'Gmail (work)' }));
    await openRenameDialog();

    const input = screen.getByLabelText(msg('renameDialog.label'));
    fireEvent.change(input, { target: { value: 'Gmail (work)' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    await waitFor(() => expect(renameCredentialMock).toHaveBeenCalledWith(42, 'Gmail (work)'));
  });

  it('tells the user the name is taken when the server answers 409', async () => {
    renameCredentialMock.mockRejectedValue(
      Object.assign(new Error('conflict'), { status: 409, code: 'duplicate_name' }),
    );
    await openRenameDialog();

    fireEvent.change(screen.getByLabelText(msg('renameDialog.label')), { target: { value: 'Gmail B' } });
    fireEvent.click(screen.getByRole('button', { name: msg('renameDialog.save') }));

    // A duplicate must not read as a generic failure: the user has to know to pick
    // another name.
    expect(
      await screen.findByText(msg('renameDialog.duplicateName')),
    ).toBeTruthy();
    expect(screen.queryByText(msg('renameDialog.failed'))).toBeNull();
  });

  it('re-enables Save after a failed rename', async () => {
    renameCredentialMock.mockRejectedValue(new Error('boom'));
    await openRenameDialog();

    fireEvent.change(screen.getByLabelText(msg('renameDialog.label')), { target: { value: 'Gmail (work)' } });
    fireEvent.click(screen.getByRole('button', { name: msg('renameDialog.save') }));

    // A missing `finally` would leave the button stuck on "Saving..." forever.
    await waitFor(() =>
      expect((screen.getByRole('button', { name: msg('renameDialog.save') }) as HTMLButtonElement).disabled).toBe(false),
    );
  });

  it('refuses a name longer than the 255-char column and explains why', async () => {
    await openRenameDialog();

    fireEvent.change(screen.getByLabelText(msg('renameDialog.label')), { target: { value: 'x'.repeat(256) } });
    fireEvent.click(screen.getByRole('button', { name: msg('renameDialog.save') }));

    expect(renameCredentialMock).not.toHaveBeenCalled();
    expect(screen.getByText(msg('renameDialog.tooLong').replace('{max}', '255'))).toBeTruthy();
  });

  it('closes without calling the API when the name is unchanged', async () => {
    await openRenameDialog();

    fireEvent.click(screen.getByRole('button', { name: msg('renameDialog.save') }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(renameCredentialMock).not.toHaveBeenCalled();
  });

  it('keeps the dialog open with an error and the old name when the API fails', async () => {
    renameCredentialMock.mockRejectedValue(new Error('boom'));
    await openRenameDialog();

    fireEvent.change(screen.getByLabelText(msg('renameDialog.label')), { target: { value: 'Gmail (work)' } });
    fireEvent.click(screen.getByRole('button', { name: msg('renameDialog.save') }));

    expect(
      await screen.findByText(msg('renameDialog.failed')),
    ).toBeTruthy();
    expect(screen.getByRole('dialog')).toBeTruthy();
    // No optimistic write: the row still shows the old name.
    expect(screen.getByText('gmail Credential')).toBeTruthy();
  });

  it('explains that a name-identified connection cannot be renamed (422)', async () => {
    renameCredentialMock.mockRejectedValue(
      Object.assign(new Error('unprocessable'), { status: 422, code: 'name_is_identity' }),
    );
    await openRenameDialog();

    fireEvent.change(screen.getByLabelText(msg('renameDialog.label')), { target: { value: 'Company SMTP' } });
    fireEvent.click(screen.getByRole('button', { name: msg('renameDialog.save') }));

    // The refusal has its own cause and its own remedy, so it must not collapse
    // into the duplicate-name message or the generic failure.
    expect(
      await screen.findByText(msg('renameDialog.cannotRename')),
    ).toBeTruthy();
    expect(screen.queryByText(msg('renameDialog.failed'))).toBeNull();
    expect(screen.getByText('gmail Credential')).toBeTruthy();
  });

  it('falls back to the generic message for an error with no code', async () => {
    renameCredentialMock.mockRejectedValue(new Error('boom'));
    await openRenameDialog();

    fireEvent.change(screen.getByLabelText(msg('renameDialog.label')), { target: { value: 'Gmail (work)' } });
    fireEvent.click(screen.getByRole('button', { name: msg('renameDialog.save') }));

    expect(
      await screen.findByText(msg('renameDialog.failed')),
    ).toBeTruthy();
  });

  it('fires no success toast when the rename fails', async () => {
    renameCredentialMock.mockRejectedValue(new Error('boom'));
    await openRenameDialog();

    fireEvent.change(screen.getByLabelText(msg('renameDialog.label')), { target: { value: 'Gmail (work)' } });
    fireEvent.click(screen.getByRole('button', { name: msg('renameDialog.save') }));

    await screen.findByText(msg('renameDialog.failed'));
    // A "Credential renamed" toast over a failed rename is worse than no toast.
    expect(toasts).toHaveLength(0);
  });

  it('closes without renaming when Cancel is pressed', async () => {
    await openRenameDialog();

    fireEvent.change(screen.getByLabelText(msg('renameDialog.label')), { target: { value: 'Discarded' } });
    fireEvent.click(screen.getByRole('button', { name: msg('renameDialog.cancel') }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(renameCredentialMock).not.toHaveBeenCalled();
    expect(screen.getByText('gmail Credential')).toBeTruthy();
  });

  it('reopens the dialog on the current name after a cancelled edit', async () => {
    await openRenameDialog();
    fireEvent.change(screen.getByLabelText(msg('renameDialog.label')), { target: { value: 'Discarded' } });
    fireEvent.click(screen.getByRole('button', { name: msg('renameDialog.cancel') }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    fireEvent.click(screen.getByRole('button', { name: msg('renameAriaLabel').replace('{name}', 'gmail Credential') }));

    // The abandoned draft must not survive into the next open.
    expect((screen.getByLabelText(msg('renameDialog.label')) as HTMLInputElement).value).toBe('gmail Credential');
  });

  it('offers no rename on a credential whose name is its identity', async () => {
    getCredentialsMock.mockResolvedValue({
      credentials: [credential({ name: 'smtp', integration: '' })],
      page: 1, pageSize: 10, totalItems: 1, totalPages: 1, hasNext: false, hasPrevious: false,
    });
    renderList();

    const pencil = await screen.findByRole('button', {
      name: msg('renameAriaLabel').replace('{name}', 'smtp'),
    });
    // The server refuses this rename (422 name_is_identity), so the affordance must
    // not invite the user to type a name that can only be rejected.
    expect((pencil as HTMLButtonElement).disabled).toBe(true);
    expect(pencil.getAttribute('title')).toBe(msg('renameDialog.cannotRename'));

    fireEvent.click(pencil);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('refreshes the credential caches after a successful rename', async () => {
    renameCredentialMock.mockResolvedValue(credential({ name: 'Gmail (work)' }));
    await openRenameDialog();

    fireEvent.change(screen.getByLabelText(msg('renameDialog.label')), {
      target: { value: 'Gmail (work)' },
    });
    fireEvent.click(screen.getByRole('button', { name: msg('renameDialog.save') }));

    // Without this the builder inspector dropdown, the chat service cards and the
    // missing-credential badges keep showing the old label until a hard reload.
    await waitFor(() => expect(invalidateCachesMock).toHaveBeenCalledTimes(1));
  });
});
