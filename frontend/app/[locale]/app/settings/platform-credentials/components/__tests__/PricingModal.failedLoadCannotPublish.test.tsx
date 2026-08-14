// @vitest-environment jsdom
import * as React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { PricingModal } from '../PricingModal';
import type { PlatformCredential, PricingVersion } from '@/lib/api/orchestrator/types';

/**
 * Publishing sends `replace: true`, so the form is not an addition to the live
 * price list: it IS the new list, and every published row it does not carry is
 * deleted. Two ways of ending up with the wrong rows in the form therefore
 * delete real prices, and both are silent:
 *
 * 1. the version read fails, the pane reads like "nothing published yet", and
 *    the next publish wipes every per-model price;
 * 2. a second credential is opened while the first is still loading, and the
 *    first credential's rows land in the second's version.
 */

const listPricingVersions = vi.fn();
const publishPricingVersion = vi.fn();
const getIntegrations = vi.fn();
const getApiTools = vi.fn();
const getModels = vi.fn();

vi.mock('@/lib/api/orchestrator/credential.service', () => ({
  credentialService: {
    listPricingVersions: (...args: unknown[]) => listPricingVersions(...args),
    publishPricingVersion: (...args: unknown[]) => publishPricingVersion(...args),
  },
}));

vi.mock('@/lib/api/services/catalog-visibility.service', () => ({
  catalogVisibilityService: {
    getIntegrations: (...args: unknown[]) => getIntegrations(...args),
    getApiTools: (...args: unknown[]) => getApiTools(...args),
  },
}));

vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: {
    getModels: (...args: unknown[]) => getModels(...args),
  },
}));

const messages = {
  settings: { common: { loading: 'Loading...' } },
  platformCredentials: {
    pricing: {
      title: 'Markup Pricing',
      subtitle: 'Publish a new pricing version.',
      history: 'Version history',
      noVersions: 'No pricing published yet.',
      defaultMarkupLabel: 'default',
      overridesLabel: '{count} overrides',
      createdBy: 'Published by',
      noOverrides: 'No per-tool overrides.',
      noOverridesInForm: 'No overrides added.',
      unknownTool: 'Unknown tool',
      copyToForm: 'Copy into form',
      copiedFromVersion: 'Copied v{version} into the form',
      publishNew: 'Publish new version',
      warningInFlightRuns: 'Publishing creates version N+1.',
      defaultMarkup: 'Default markup (credits / call)',
      overrides: 'Per-tool overrides',
      addOverride: 'Add override',
      selectTool: 'Select a tool',
      publish: 'Publish',
      publishing: 'Publishing',
      publishedVersion: 'Version v{version} published',
      retry: 'Retry',
      success: 'Saved',
      model: 'Model',
      modelAny: 'All models (endpoint-wide)',
      modelHint: 'Leave the model empty to price the whole endpoint.',
      unit: 'Billed per',
      baseCredits: 'Base',
      unitCredits: 'Per unit',
      minCredits: 'Floor',
      maxCredits: 'Ceiling',
      removePrice: 'Remove this price',
      units: { call: 'call', second: 'second', minute: 'minute', image: 'image', character: 'character' },
      summaryFlat: '{amount} credits per call',
      summaryPerUnit: '{rate} credits per {unit}',
      summaryBasePlusUnit: '{base} credits + {rate} credits per {unit}',
      floorNote: 'minimum {min} credits',
      ceilingNote: 'maximum {max} credits',
      errors: {
        fetchFailed: 'Failed to load pricing',
        loadFailed: 'The published prices could not be loaded.',
        publishFailed: 'Failed to publish pricing',
        credentialNotFound: 'Credential not found',
        invalidDefault: 'Default markup must be a non-negative number',
        invalidOverride: 'Invalid markup for tool {tool}',
        duplicateTool: 'Each endpoint and model can appear only once',
      },
    },
  },
};

const TOOL_A = '11111111-1111-1111-1111-111111111111';
const TOOL_B = '22222222-2222-2222-2222-222222222222';

const CREDENTIAL_A = {
  id: 42, integrationName: 'bytedance', displayName: 'ByteDance',
} as PlatformCredential;
const CREDENTIAL_B = {
  id: 43, integrationName: 'openai', displayName: 'OpenAI',
} as PlatformCredential;

function version(id: number, credentialId: number, apiToolId: string, rate: string): PricingVersion {
  return {
    id,
    credentialId,
    version: 3,
    defaultMarkupCredits: null,
    createdAt: '2026-01-01T00:00:00Z',
    createdBy: 'admin',
    overrides: {},
    prices: [{
      apiToolId,
      modelId: 'model-x',
      priceUnit: 'second',
      baseCredits: '0',
      unitCredits: rate,
      minCredits: null,
      maxCredits: null,
    }],
  } as PricingVersion;
}

function renderModal(credential: PlatformCredential) {
  const onNotify = vi.fn();
  const view = render(
    <NextIntlClientProvider locale="en" messages={messages as never}>
      <PricingModal open onOpenChange={() => {}} credential={credential} onNotify={onNotify} />
    </NextIntlClientProvider>,
  );
  const rerenderWith = (next: PlatformCredential) => view.rerender(
    <NextIntlClientProvider locale="en" messages={messages as never}>
      <PricingModal open onOpenChange={() => {}} credential={next} onNotify={onNotify} />
    </NextIntlClientProvider>,
  );
  return { onNotify, rerenderWith };
}

beforeEach(() => {
  vi.clearAllMocks();
  getIntegrations.mockResolvedValue([
    { apiId: 'api-bytedance', apiName: 'ByteDance', credentialName: 'bytedance' },
    { apiId: 'api-openai', apiName: 'OpenAI', credentialName: 'openai' },
  ]);
  getApiTools.mockImplementation(async (apiId: string) =>
    apiId === 'api-bytedance'
      ? [{ toolId: TOOL_A, toolName: 'Submit video job', toolSlug: 'submit', isActive: true }]
      : [{ toolId: TOOL_B, toolName: 'Create image', toolSlug: 'image', isActive: true }]);
  getModels.mockResolvedValue({
    models: [
      { model: 'model-x', label: 'Model X', kind: 'video', apiToolId: TOOL_A },
      { model: 'model-x', label: 'Model X', kind: 'image', apiToolId: TOOL_B },
    ],
    count: 2,
    kinds: ['video', 'image'],
  });
  publishPricingVersion.mockResolvedValue({ ...version(1, 43, TOOL_B, '11'), version: 4 });
});

describe('PricingModal: a failed price load can never publish', () => {
  it('shows the read failure instead of the empty "nothing published yet" pane', async () => {
    listPricingVersions.mockRejectedValue(new Error('500 Internal Server Error'));

    const { onNotify } = renderModal(CREDENTIAL_A);

    // The distinction that matters: a failed read must not be readable as
    // "this credential has no prices", which is what invites the wipe.
    await screen.findByText('The published prices could not be loaded.');
    expect(screen.queryByText('No pricing published yet.')).toBeNull();
    expect(onNotify).toHaveBeenCalledWith('error', 'Failed to load pricing', '500 Internal Server Error');
  });

  it('offers no publish control at all while the live prices are unknown', async () => {
    listPricingVersions.mockRejectedValue(new Error('boom'));

    renderModal(CREDENTIAL_A);

    await screen.findByText('The published prices could not be loaded.');
    // No Publish button means the destructive replace cannot be reached, not
    // even by an admin who types a default markup out of habit.
    expect(screen.queryByRole('button', { name: 'Publish' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
    expect(publishPricingVersion).not.toHaveBeenCalled();
  });

  it('recovers on retry once the read succeeds, so the failure is not a dead end', async () => {
    listPricingVersions
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValue([version(900, 42, TOOL_A, '60')]);

    renderModal(CREDENTIAL_A);

    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));

    expect(await screen.findByDisplayValue('60')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Publish' })).toBeTruthy();
  });
});

describe('PricingModal: switching credential mid-load cannot cross-write', () => {
  it('discards the first credential rows when a second one is opened while it loads', async () => {
    let releaseSlowLoad: (v: PricingVersion[]) => void = () => {};
    const slow = new Promise<PricingVersion[]>((resolve) => { releaseSlowLoad = resolve; });
    listPricingVersions.mockImplementation((id: number) =>
      id === CREDENTIAL_A.id ? slow : Promise.resolve([version(901, 43, TOOL_B, '11')]));

    const { rerenderWith } = renderModal(CREDENTIAL_A);
    rerenderWith(CREDENTIAL_B);

    expect(await screen.findByDisplayValue('11')).toBeTruthy();

    // The first credential answers late. Its rows belong to another credential
    // and another endpoint; landing them here would publish them under B.
    await act(async () => {
      releaseSlowLoad([version(900, 42, TOOL_A, '60')]);
      await slow;
    });

    expect(screen.queryByDisplayValue('60')).toBeNull();
    expect(screen.getByDisplayValue('11')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Publish' }));
    await waitFor(() => expect(publishPricingVersion).toHaveBeenCalledTimes(1));
    expect(publishPricingVersion.mock.calls[0][0]).toBe(CREDENTIAL_B.id);
    expect(publishPricingVersion.mock.calls[0][1].prices).toEqual([
      {
        apiToolId: TOOL_B,
        modelId: 'model-x',
        priceUnit: 'second',
        baseCredits: '0',
        unitCredits: '11',
      },
    ]);
  });

  it('keeps a late FAILURE of the abandoned load from blocking the credential now open', async () => {
    let failSlowLoad: (e: Error) => void = () => {};
    const slow = new Promise<PricingVersion[]>((_, reject) => { failSlowLoad = reject; });
    listPricingVersions.mockImplementation((id: number) =>
      id === CREDENTIAL_A.id ? slow : Promise.resolve([version(901, 43, TOOL_B, '11')]));

    const { onNotify, rerenderWith } = renderModal(CREDENTIAL_A);
    // Reopen on the second credential before the first load settles.
    rerenderWith(CREDENTIAL_B);

    expect(await screen.findByDisplayValue('11')).toBeTruthy();

    await act(async () => {
      failSlowLoad(new Error('too late'));
      await slow.catch(() => undefined);
    });

    // The abandoned load must not raise an error banner over a pane that loaded
    // fine, which would block publishing a credential nothing went wrong with.
    expect(screen.getByRole('button', { name: 'Publish' })).toBeTruthy();
    expect(screen.queryByText('The published prices could not be loaded.')).toBeNull();
    expect(onNotify).not.toHaveBeenCalledWith('error', 'Failed to load pricing', 'too late');
  });
});
