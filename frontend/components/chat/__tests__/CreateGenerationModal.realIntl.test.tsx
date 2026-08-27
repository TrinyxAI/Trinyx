// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';

/**
 * The sibling suite mocks the translator so its assertions read raw keys, which
 * keeps them independent of the wording. That independence has a cost: a
 * translator bound to the wrong namespace returns the key path, and a test that
 * asserts key paths cannot tell the two apart. It shipped exactly that bug,
 * twice: the price unit rendered as
 * "credentials.source.source.priceUnits.second" on screen, in every locale.
 *
 * <p>So this renders with the REAL next-intl and the REAL dictionaries, and
 * asserts words a reader would recognise. It is deliberately small: one thing
 * that has to survive translation, checked in two languages.
 */

const mocks = vi.hoisted(() => ({
  getModels: vi.fn(),
  execute: vi.fn(),
  getAllCredentials: vi.fn(),
  getPlatformCredentialPublicInfo: vi.fn(),
  getCredentialTemplates: vi.fn(),
  getCredentialTemplateByName: vi.fn(),
  useCreditBalance: vi.fn(),
}));

/**
 * Radix's Select never opens under jsdom, so its options are absent from the
 * DOM and the price sentence they carry cannot be read. Reduced to a native
 * select, exactly as the sibling suite does.
 *
 * <p>This does NOT weaken what the file is for: the option's text is still
 * produced by the real component through the real dictionaries, which is the
 * whole subject here. Only the control around it is simplified.
 */
vi.mock('@/components/ui/select', () => ({
  Select: ({ value, onValueChange, children }: any) => (
    <select value={value ?? ''} onChange={(e) => onValueChange(e.target.value)}>
      <option value="" />
      {children}
    </select>
  ),
  SelectTrigger: ({ children }: any) => <>{children}</>,
  SelectValue: () => null,
  SelectContent: ({ children }: any) => <>{children}</>,
  SelectItem: ({ value, children }: any) => <option value={value}>{children}</option>,
  // Used by the credential picker the modal shares with the workflow
  // inspector. Absent, it renders as undefined and the dialog throws.
  SelectSeparator: () => null,
  // Read at MODULE level by InterfaceFormatSelect, which the inspector pulls in
  // through the shared credential picker. A mock that omits it fails the whole
  // suite at import time, before any test runs.
  SELECT_EMPTY_VALUE_SENTINEL: '__select_empty_value__',
}));

/**
 * The credential picker reads the account through a different module from the
 * one the modal itself uses, so both are mocked. Left real, it fetches, fails
 * under jsdom, and the price sentence this file exists for never renders.
 */
vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getAllCredentials: mocks.getAllCredentials,
    getPlatformCredentialPublicInfo: mocks.getPlatformCredentialPublicInfo,
    getCredentialTemplates: mocks.getCredentialTemplates,
    getCredentialTemplateByName: mocks.getCredentialTemplateByName,
  },
}));

vi.mock('@/components/credentials/CredentialWizard', () => ({
  CredentialWizard: () => null,
  resolveByokConfig: () => ({ surface: 'hidden' }),
  resolveByokOnlyScopeList: () => [],
  resolvePlatformScopeList: () => [],
}));

vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useCreditBalance: mocks.useCreditBalance,
}));
vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: { getModels: mocks.getModels, execute: mocks.execute },
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getAllCredentials: mocks.getAllCredentials,
    getPlatformCredentialPublicInfo: mocks.getPlatformCredentialPublicInfo,
  },
}));

/**
 * The locale-aware router the result step routes to Files with.
 *
 * <p>Mocked because it cannot be imported for real here: `next-intl/navigation`
 * reaches `next/navigation`, an extensionless specifier that vitest cannot
 * resolve from the externalized ESM build, so the whole suite fails to LOAD -
 * silently taking down the two cases this file exists for. Every other suite in
 * the repo that renders a component importing it mocks it the same way.
 */
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

import { CreateGenerationModal } from '../CreateGenerationModal';

const MODEL = {
  model: 'seedance-2.0',
  kind: 'video',
  label: 'Seedance 2.0',
  provider: 'Seedance',
  iconSlug: null,
  apiToolId: 't-1',
  integrationName: 'seedance',
  accepts: ['prompt', 'duration_seconds'],
  required: ['prompt'],
  limits: {},
  billedOn: 'duration_seconds',
  defaultQuantity: '5',
  price: { unit: 'second', baseCredits: '0', unitCredits: '60' },
  async: true,
};

function renderIn(locale: 'en' | 'fr') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale={locale} messages={(locale === 'en' ? en : fr) as any}>
        <CreateGenerationModal isOpen onClose={() => {}} initialKind="video" />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  mocks.getModels.mockResolvedValue({ models: [MODEL], count: 1, kinds: ['video'] });
  mocks.getAllCredentials.mockResolvedValue([]);
  mocks.getCredentialTemplates.mockResolvedValue({ credentials: [] });
  mocks.getCredentialTemplateByName.mockResolvedValue(null);
  mocks.getPlatformCredentialPublicInfo.mockResolvedValue({
    // The key the price is published against. The server always sends it with
    // an endpoint quote, and the surface needs it to tell "the platform sells
    // this" from "a price exists somewhere".
    integrationName: 'seedance', available: true, platformCredentialId: 42, hasPricing: true,
    priceUnit: 'second', baseCredits: '0', unitCredits: '60',
    markupCredits: '300', quantity: '5',
  });
  mocks.useCreditBalance.mockReturnValue({
    subBalance: 500, paygBalance: 100, monthlyCreditsAreWorkflowOnly: false,
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('CreateGenerationModal under real translations', () => {
  it('names the price unit in words, never as the key path that a wrong namespace returns', async () => {
    renderIn('en');

    const price = await screen.findByText(/credits per/);
    expect(price.textContent).toContain('second');
    // The shape of the failure this exists for: next-intl answers a missing
    // key with the key itself, so the bug is visible only as a key on screen.
    expect(price.textContent).not.toContain('priceUnits');
    expect(price.textContent).not.toContain('credentials.');
  });

  it('translates that unit, which is the whole reason it goes through a dictionary', async () => {
    renderIn('fr');

    const price = await screen.findByText(/crédits par/);
    expect(price.textContent).toContain('seconde');
    expect(price.textContent).not.toContain('priceUnits');
  });

  it('says which format it opened on, in the reader’s language', async () => {
    // Guards the whole namespace wiring, not only the unit: a modal rendered
    // under a provider it does not match shows key paths everywhere.
    renderIn('fr');

    expect(await screen.findByText(/Seedance 2.0/)).toBeInTheDocument();
    // A real translated sentence, not the absence of a prefix: binding to
    // 'generationModals' would render 'generationModals.title', which does not
    // contain 'generationModal.' and would have slipped through.
    expect(screen.getByText('Choisissez un modèle et décrivez')).toBeInTheDocument();
  });
});
