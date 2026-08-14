// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import * as React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * An API-key integration used to open straight on "paste your API key",
 * whether or not the platform had a key of its own. For the integrations most
 * likely to be resold (every generation provider is key-based) that quietly
 * made BYOK the only path the user was ever shown, and the choice it hid is
 * not cosmetic: it decides who pays. The platform's key bills credits; the
 * user's key bills the provider directly and the platform charges nothing.
 *
 * OAuth2 has always had this conversation, through the Standard / Custom
 * toggle. These tests are about the other half.
 */

const ICON_SLUG = 'seedance';

const mocks = vi.hoisted(() => ({
  getPlatformCredentialsAvailability: vi.fn(),
  getPlatformCredentialPublicInfo: vi.fn(),
}));

const TEMPLATE = {
  id: 'tmpl-seedance',
  credential_name: 'seedance',
  display_name: 'Seedance',
  auth_type: 'api_key',
  icon_slug: ICON_SLUG,
  properties: [{ name: 'api_key', displayName: 'API Key', type: 'string', required: true }],
};

vi.mock('@/lib/api/orchestrator', async () => ({
  orchestratorApi: {
    getCredentialTemplateByName: vi.fn(() => Promise.resolve(TEMPLATE)),
    getCredentialTemplates: vi.fn(() => Promise.resolve({ credentials: [TEMPLATE] })),
    getPlatformCredentialsAvailability: mocks.getPlatformCredentialsAvailability,
    getPlatformCredentialPublicInfo: mocks.getPlatformCredentialPublicInfo,
    hasPlatformCredentials: vi.fn(() => Promise.resolve(true)),
    getCredentialVariants: vi.fn(() => Promise.resolve([])),
    initiateOAuth2: vi.fn(() => Promise.resolve({ authUrl: 'https://oauth/redirect' })),
    createCredential: vi.fn(() => Promise.resolve({ id: 1 })),
    saveTenantPlatformCredential: vi.fn(() => Promise.resolve({})),
  },
}));

import { CredentialWizard } from '../CredentialWizard';

const messages = {
  credentials: {
    wizard: {
      title: 'Connect',
      description: 'Connect your account',
      saving: 'Saving...',
      close: 'Close',
      done: 'Done',
      nextButton: 'Next',
      connected: 'Connected',
      nextCredential: '{remaining} more',
      multiWizard: { title: 'Connect {name}' },
      platformKey: {
        title: 'You do not have to add a key',
        explanation: 'Available on the platform key, billed in credits per call.',
        explanationPriced: 'Available on the platform key at {credits} credits per call.',
        use: 'Use the platform key',
        orOwn: 'Or add your own key below.',
      },
      oauthConfig: {
        description: 'Enter your OAuth2 credentials.',
        authUrl: 'Authorization URL', authUrlPlaceholder: 'https://...',
        tokenUrl: 'Token URL', tokenUrlPlaceholder: 'https://...',
        scopes: 'Scopes', scopesPlaceholder: 'e.g. read write',
        clientId: 'Client ID', clientIdPlaceholder: 'Enter your client ID',
        clientSecret: 'Client Secret', clientSecretPlaceholder: 'Enter your client secret',
        saveAndConnect: 'Save & Connect',
      },
      errors: { templateNotFound: 'Template not found', oauthUrlsRequired: 'URLs required' },
    },
    configureDialog: {
      cancel: 'Cancel', optional: 'optional', credential: 'Credential',
      credentialName: 'Credential Name', credentialNamePlaceholder: 'e.g. {name}',
      credentialNameHint: 'A friendly name',
      unverifiedNotice: 'Some providers have not finished verification.',
      modeToggle: {
        label: 'Connection mode', standard: 'Standard', advanced: 'Custom OAuth',
        ariaSwitchToStandard: 'Switch to standard connection',
        ariaSwitchToAdvanced: 'Switch to custom OAuth connection',
        needCustomOAuth: 'Need a custom OAuth app?', backToStandard: 'Use standard connection',
      },
      setupGuide: { title: 'Setup guide', copy: 'Copy', openConsole: 'Open developer portal' },
      errors: { clientIdRequired: 'Client ID is required', clientSecretRequired: 'Client Secret is required' },
    },
  },
};

function renderWizard(props: Record<string, unknown> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <CredentialWizard
          requirements={[{ iconSlug: ICON_SLUG, serviceName: 'Seedance' }]}
          open
          onOpenChange={() => {}}
          {...props}
        />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  mocks.getPlatformCredentialsAvailability.mockResolvedValue({ available: true });
  mocks.getPlatformCredentialPublicInfo.mockResolvedValue({
    integrationName: 'seedance', available: true, hasPricing: true, markupCredits: '300',
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('CredentialWizard - an API key is a choice, not an obligation', () => {
  it('tells the user a platform key exists instead of only asking for theirs', async () => {
    renderWizard();

    expect(await screen.findByText('You do not have to add a key')).toBeInTheDocument();
  });

  it('does NOT quote a number, because this screen has no endpoint to quote one for', async () => {
    // This test used to assert the opposite, against a fixture it had written
    // itself: a response carrying `markupCredits` with no `apiToolId` asked
    // for. The server never emits that shape, so the sentence could not
    // render, and the payload that WOULD render it is the credential-wide
    // default, which is not what any endpoint charges and which the biller
    // refuses outright for a generation. The test certified a number the
    // product must never show.
    //
    // The fixture deliberately still carries markupCredits, so this fails if
    // the priced branch comes back.
    renderWizard();

    expect(await screen.findByText('You do not have to add a key')).toBeInTheDocument();
    // The generic sentence, which says the key is billed in credits without
    // naming an amount, is exactly what should be here.
    expect(screen.getByText('Available on the platform key, billed in credits per call.'))
      .toBeInTheDocument();
    // No AMOUNT, which is the part the server cannot stand behind on a screen
    // that names no endpoint.
    expect(screen.queryByText(/\d+ credits per call/)).not.toBeInTheDocument();
  });

  it('asks whether a platform key exists rather than assuming one does', async () => {
    // The old code asserted `true` for every non-OAuth2 template without a
    // single request, which is how an integration with no platform key at all
    // would have been offered one.
    renderWizard();

    await waitFor(() => expect(mocks.getPlatformCredentialsAvailability)
      .toHaveBeenCalledWith('seedance'));
  });

  it('says nothing about a platform key when there is none', async () => {
    mocks.getPlatformCredentialsAvailability.mockResolvedValue({ available: false });

    renderWizard();

    // The form still renders; only the offer is absent.
    await waitFor(() => expect(mocks.getPlatformCredentialsAvailability).toHaveBeenCalled());
    expect(screen.queryByText('You do not have to add a key')).not.toBeInTheDocument();
  });

  it('treats a failed lookup as no offer, rather than promising a key it could not confirm', async () => {
    mocks.getPlatformCredentialsAvailability.mockRejectedValue(new Error('auth down'));

    renderWizard();

    await waitFor(() => expect(mocks.getPlatformCredentialsAvailability).toHaveBeenCalled());
    expect(screen.queryByText('You do not have to add a key')).not.toBeInTheDocument();
  });

  it('hands the choice back to the caller and creates NO credential', async () => {
    // Choosing the platform's key is the ABSENCE of a credential, not a
    // different kind of one. Writing a row here would invent a second place
    // where "who pays" is recorded.
    const onUse = vi.fn();
    const onOpenChange = vi.fn();
    renderWizard({ onUsePlatformCredential: onUse, onOpenChange });

    fireEvent.click(await screen.findByText('Use the platform key'));

    expect(onUse).toHaveBeenCalledWith('seedance');
    expect(onOpenChange).toHaveBeenCalledWith(false);
    const { orchestratorApi } = await import('@/lib/api/orchestrator');
    expect(orchestratorApi.createCredential).not.toHaveBeenCalled();
  });

  it('explains the choice but offers no action when the caller has nowhere to record it', async () => {
    // The settings list can say "you need not add a key"; it cannot decide
    // which pool a workflow step runs on. An action that led nowhere would be
    // worse than none.
    renderWizard();

    expect(await screen.findByText('You do not have to add a key')).toBeInTheDocument();
    expect(screen.queryByText('Use the platform key')).not.toBeInTheDocument();
  });
});
