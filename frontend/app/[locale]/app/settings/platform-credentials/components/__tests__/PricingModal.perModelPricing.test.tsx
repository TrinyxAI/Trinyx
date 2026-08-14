// @vitest-environment jsdom
import * as React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { PricingModal, describePrice, versionPrices } from '../PricingModal';
import type { PlatformCredential, PricingVersion } from '@/lib/api/orchestrator/types';

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
        publishFailed: 'Failed to publish pricing',
        credentialNotFound: 'Credential not found',
        invalidDefault: 'Default markup must be a non-negative number',
        invalidOverride: 'Invalid markup for tool {tool}',
        duplicateTool: 'Each endpoint and model can appear only once',
      },
    },
  },
};

const VIDEO_TOOL = '11111111-1111-1111-1111-111111111111';

const credential = { id: 42, integrationName: 'bytedance', displayName: 'ByteDance' } as PlatformCredential;

/** A live version pricing two models of ONE endpoint at different per-second rates. */
const publishedVersion: PricingVersion = {
  id: 900,
  credentialId: 42,
  version: 3,
  defaultMarkupCredits: null,
  createdAt: '2026-01-01T00:00:00Z',
  createdBy: 'admin',
  overrides: {},
  prices: [
    {
      apiToolId: VIDEO_TOOL,
      modelId: 'seedance-2.0',
      priceUnit: 'second',
      baseCredits: '0',
      unitCredits: '60',
      minCredits: null,
      maxCredits: null,
    },
    {
      apiToolId: VIDEO_TOOL,
      modelId: 'seedance-2.0-fast',
      priceUnit: 'second',
      baseCredits: '0',
      unitCredits: '30',
      minCredits: null,
      maxCredits: null,
    },
  ],
};

function renderModal() {
  const onNotify = vi.fn();
  render(
    <NextIntlClientProvider locale="en" messages={messages as never}>
      <PricingModal open onOpenChange={() => {}} credential={credential} onNotify={onNotify} />
    </NextIntlClientProvider>,
  );
  return onNotify;
}

beforeEach(() => {
  vi.clearAllMocks();
  listPricingVersions.mockResolvedValue([publishedVersion]);
  getIntegrations.mockResolvedValue([
    { apiId: 'api-bytedance', apiName: 'ByteDance', credentialName: 'bytedance' },
  ]);
  getApiTools.mockResolvedValue([
    { toolId: VIDEO_TOOL, toolName: 'Submit video job', toolSlug: 'submit', isActive: true },
  ]);
  getModels.mockResolvedValue({
    models: [
      { model: 'seedance-2.0', label: 'Seedance 2.0', kind: 'video', apiToolId: VIDEO_TOOL },
      { model: 'seedance-2.0-fast', label: 'Seedance 2.0 Fast', kind: 'video', apiToolId: VIDEO_TOOL },
    ],
    count: 2,
    kinds: ['video'],
  });
  publishPricingVersion.mockResolvedValue({ ...publishedVersion, version: 4 });
});

describe('PricingModal per-model, per-unit prices', () => {
  it('opens on the published rows, keeping each model of one endpoint at its own rate', async () => {
    renderModal();

    // Both rows are loaded for editing, each with its own rate. Reading the two
    // back as one number is exactly the bug this screen exists to expose.
    const rates = await screen.findAllByDisplayValue('60');
    expect(rates).toHaveLength(1);
    expect(screen.getAllByDisplayValue('30')).toHaveLength(1);
    // And the rate is stated the way a human reads it, not as a bare number.
    expect(screen.getAllByText('60 credits per second').length).toBeGreaterThan(0);
    expect(screen.getAllByText('30 credits per second').length).toBeGreaterThan(0);
  });

  it('republishes every row it loaded, so editing one rate does not drop the other', async () => {
    renderModal();

    const rate = await screen.findByDisplayValue('60');
    fireEvent.change(rate, { target: { value: '75' } });
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }));

    await waitFor(() => expect(publishPricingVersion).toHaveBeenCalledTimes(1));
    expect(publishPricingVersion.mock.calls[0][1]).toEqual({
      defaultMarkupCredits: null,
      replace: true,
      prices: [
        {
          apiToolId: VIDEO_TOOL,
          modelId: 'seedance-2.0',
          priceUnit: 'second',
          baseCredits: '0',
          unitCredits: '75',
        },
        {
          apiToolId: VIDEO_TOOL,
          modelId: 'seedance-2.0-fast',
          priceUnit: 'second',
          baseCredits: '0',
          unitCredits: '30',
        },
      ],
    });
  });

  it('ignores a row whose endpoint was never chosen rather than publishing a price for nothing', async () => {
    renderModal();

    await screen.findByDisplayValue('60');
    fireEvent.click(screen.getByRole('button', { name: /Add override/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }));

    await waitFor(() => expect(publishPricingVersion).toHaveBeenCalledTimes(1));
    expect(publishPricingVersion.mock.calls[0][1].prices).toHaveLength(2);
  });

  it('rejects a negative amount before publishing anything', async () => {
    const onNotify = renderModal();

    const rate = await screen.findByDisplayValue('60');
    fireEvent.change(rate, { target: { value: '-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }));

    await waitFor(() =>
      expect(onNotify).toHaveBeenCalledWith('error', 'Invalid markup for tool Submit video job'));
    expect(publishPricingVersion).not.toHaveBeenCalled();
  });
});

describe('describePrice', () => {
  const t = (key: string, values?: Record<string, string>) => {
    const raw = (messages.platformCredentials.pricing as Record<string, unknown>);
    const flat = key.startsWith('units.')
      ? (raw.units as Record<string, string>)[key.slice('units.'.length)]
      : (raw[key] as string);
    return Object.entries(values ?? {}).reduce(
      (acc, [k, v]) => acc.replace(`{${k}}`, v),
      flat,
    );
  };

  it('states a per-second rate as a rate, never as a flat amount', () => {
    expect(describePrice(
      { priceUnit: 'second', baseCredits: '0', unitCredits: '60', minCredits: null, maxCredits: null },
      t,
    )).toBe('60 credits per second');
  });

  it('states a flat price as a per-call amount', () => {
    expect(describePrice(
      { priceUnit: 'call', baseCredits: '133', unitCredits: '0', minCredits: null, maxCredits: null },
      t,
    )).toBe('133 credits per call');
  });

  it('keeps the fixed part visible when a price has both a base and a rate', () => {
    expect(describePrice(
      { priceUnit: 'image', baseCredits: '5', unitCredits: '2', minCredits: null, maxCredits: null },
      t,
    )).toBe('5 credits + 2 credits per image');
  });

  it('reports the clamps, because they are what a tiny or huge call actually bills', () => {
    expect(describePrice(
      { priceUnit: 'character', baseCredits: '0', unitCredits: '0.3', minCredits: '1', maxCredits: '900' },
      t,
    )).toBe('0.3 credits per character, minimum 1 credits, maximum 900 credits');
  });
});

describe('versionPrices', () => {
  it('reads a backend that only sends the flat overrides map as flat per-call rows', () => {
    const legacy = {
      id: 1, credentialId: 42, version: 1, defaultMarkupCredits: '0.1',
      overrides: { [VIDEO_TOOL]: '0.35' },
    } as PricingVersion;

    expect(versionPrices(legacy)).toEqual([
      {
        apiToolId: VIDEO_TOOL,
        modelId: null,
        priceUnit: 'call',
        baseCredits: '0.35',
        unitCredits: '0',
        minCredits: null,
        maxCredits: null,
      },
    ]);
  });

  it('prefers the full price list when the backend sends one', () => {
    expect(versionPrices(publishedVersion)).toHaveLength(2);
  });
});
