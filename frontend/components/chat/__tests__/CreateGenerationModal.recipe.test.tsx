// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * Running a generation AGAIN, with one thing changed.
 *
 * <p>Before the recipe existed, the only way to vary a generation was to retype the prompt from
 * memory and re-pick every parameter - which produces a different asset for reasons nobody can
 * name. What these pin is that a recipe handed to the dialog comes back out of it unchanged: the
 * same model, the same parameters, the same input file, so the ONE thing the reader edits is the
 * only thing that differs.
 *
 * <p>They also pin the two ways a recipe gets in: from a caller (the Files page, on an asset being
 * looked at) and from the dialog's own history of past generations.
 */

const mocks = vi.hoisted(() => ({
  getModels: vi.fn(),
  execute: vi.fn(),
  getAllCredentials: vi.fn(),
  getPlatformCredentialPublicInfo: vi.fn(),
  getCredentialTemplates: vi.fn(),
  getCredentialTemplateByName: vi.fn(),
  useCreditBalance: vi.fn(),
  uploadGeneric: vi.fn(),
  downloadStoredFile: vi.fn(),
  getModelOptions: vi.fn(),
  invalidateHistory: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator/file.service', async (importOriginal) => {
  // isFileRef / normalizeFileRef are the REAL ones on purpose: deciding what in a recipe is a file
  // handle is exactly what is under test here, and a stub would certify the test's own guess.
  const actual = await importOriginal<typeof import('@/lib/api/orchestrator/file.service')>();
  return {
    ...actual,
    fileService: { uploadGeneric: mocks.uploadGeneric },
    downloadStoredFile: mocks.downloadStoredFile,
  };
});

vi.mock('@/components/ui/select', () => ({
  Select: ({ value, onValueChange, children }: any) => (
    <select
      data-testid={(React.Children.toArray(children)
        .find((c: any) => c?.props?.id) as any)?.props?.id ?? 'model-select'}
      value={value ?? ''}
      onChange={(e) => onValueChange(e.target.value)}
    >
      <option value="" />
      {children}
    </select>
  ),
  SelectTrigger: ({ children }: any) => <>{children}</>,
  SelectValue: () => null,
  SelectContent: ({ children }: any) => <>{children}</>,
  SelectItem: ({ value, children }: any) => <option value={value}>{children}</option>,
  SelectSeparator: () => null,
}));

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

vi.mock('@/components/app/FileDetailView', () => ({
  FileDetailView: () => <div data-testid="file-detail-view" />,
}));

/**
 * The past generations. Reduced to a marker that hands back ONE fixed recipe: what this file has to
 * prove is that the dialog can be reached from the history and that reusing an entry fills the
 * form - the list itself has its own tests, and rendering it for real would fetch a page of
 * storage rows jsdom cannot serve.
 */
const HISTORY_RECIPE = {
  model: 'seedance-2.0',
  kind: 'video',
  prompt: 'a lighthouse at dusk',
  params: { duration_seconds: 10 },
};
vi.mock('@/components/generation/GenerationHistoryList', () => ({
  GenerationHistoryList: ({ onReuse }: any) => (
    <div data-testid="generation-history">
      <button type="button" onClick={() => onReuse({ id: 'h1', provenance: HISTORY_RECIPE })}>
        reuse-entry
      </button>
    </div>
  ),
}));

vi.mock('@/hooks/useGenerationHistory', () => ({
  useInvalidateGenerationHistory: () => mocks.invalidateHistory,
}));

const nav = vi.hoisted(() => ({ push: vi.fn() }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: nav.push }) }));

vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: {
    getModels: mocks.getModels,
    execute: mocks.execute,
    getModelOptions: mocks.getModelOptions,
  },
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getAllCredentials: mocks.getAllCredentials,
    getPlatformCredentialPublicInfo: mocks.getPlatformCredentialPublicInfo,
  },
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${Object.values(values).join(',')}` : key,
  useLocale: () => 'en',
}));

vi.mock('next/link', () => ({
  default: ({ href, children }: any) => <a href={href}>{children}</a>,
}));

import { CreateGenerationModal } from '../CreateGenerationModal';

/** A model that takes a prompt, a size and a source image - one of each kind a recipe can carry. */
function model(over: Record<string, unknown> = {}) {
  return {
    model: 'seedance-2.0',
    kind: 'video',
    label: 'Seedance 2.0',
    provider: 'Seedance',
    iconSlug: null,
    apiToolId: 't-1',
    integrationName: 'seedance',
    accepts: ['prompt', 'duration_seconds', 'input_image', 'guidance_scale', 'raw'],
    required: ['prompt'],
    inputs: { input_image: { role: 'first_frame', maxItems: 1 } },
    // The catalogue describes guidance_scale as numeric. Nothing in this dialog knows that name,
    // so this is the only thing that can say the field carries a number.
    limits: { guidance_scale: { min: 1, max: 20 } },
    billedOn: 'duration_seconds',
    measuredUnit: 'second',
    defaultQuantity: '5',
    price: { unit: 'second', baseCredits: '0', unitCredits: '60' },
    async: true,
    ...over,
  };
}

const SOURCE_FILE = {
  _type: 'file' as const,
  path: 'tenant/general/still.png',
  name: 'still.png',
  mimeType: 'image/png',
  size: 1024,
  id: 'file-1',
};

function renderModal(props: Record<string, unknown> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <CreateGenerationModal isOpen onClose={() => {}} {...props} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
  mocks.getAllCredentials.mockResolvedValue([]);
  mocks.getCredentialTemplates.mockResolvedValue({ credentials: [] });
  mocks.getCredentialTemplateByName.mockResolvedValue(null);
  mocks.getPlatformCredentialPublicInfo.mockResolvedValue({
    integrationName: 'seedance',
    available: true,
    platformCredentialId: 42,
    hasPricing: true,
    priceUnit: 'second',
    baseCredits: '0',
    unitCredits: '60',
    markupCredits: '300',
    quantity: '5',
  });
  mocks.useCreditBalance.mockReturnValue({
    subBalance: 500, paygBalance: 100, monthlyCreditsAreWorkflowOnly: false,
  });
  mocks.execute.mockResolvedValue({ success: true, data: { model: 'seedance-2.0', kind: 'video' } });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('CreateGenerationModal - opening on a past recipe', () => {
  const recipe = {
    model: 'seedance-2.0',
    kind: 'video',
    provider: 'Seedance',
    prompt: 'a lighthouse at dusk',
    params: { duration_seconds: 10, input_image: SOURCE_FILE },
    credentialSource: 'platform',
  };

  it('opens on the form with the prompt and the parameters already filled in', async () => {
    renderModal({ initialRecipe: recipe });

    // Straight to step 2: the format was decided by the recipe, and there is nothing to choose.
    const prompt = await screen.findByLabelText(/fields\.prompt/);
    expect(prompt).toHaveValue('a lighthouse at dusk');
    expect(screen.getByLabelText(/params\.duration_seconds/)).toHaveValue(10);
  });

  it('re-attaches the input file the first run used', async () => {
    // A file parameter cannot be retyped: what identifies it is the handle the platform stored.
    // Dropped here, the reader would be sent to re-upload a file they already own, and a "variant"
    // would be built from a different source image without anything saying so.
    renderModal({ initialRecipe: recipe });

    expect(await screen.findByText('still.png')).toBeInTheDocument();
  });

  it('sends the recipe back unchanged when nothing is edited', async () => {
    renderModal({ initialRecipe: recipe });

    // Wait for a field the MODEL owns: the button is disabled until the catalogue has landed and
    // the chosen model is resolved, and a click on a disabled button asserts nothing.
    await screen.findByLabelText(/params\.duration_seconds/);
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    expect(mocks.execute.mock.calls[0][0]).toMatchObject({
      model: 'seedance-2.0',
      params: {
        prompt: 'a lighthouse at dusk',
        // A number, still: stringified, it would change the size the run is billed on.
        duration_seconds: 10,
        input_image: SOURCE_FILE,
      },
      credential_source: 'platform',
    });
  });

  it('sends the EDITED value, which is the whole point of reopening it', async () => {
    renderModal({ initialRecipe: recipe });

    const prompt = await screen.findByLabelText(/fields\.prompt/);
    fireEvent.change(prompt, { target: { value: 'a lighthouse at dawn' } });
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    const sent = mocks.execute.mock.calls[0][0];
    expect(sent.params.prompt).toBe('a lighthouse at dawn');
    // Everything else survives the edit, or this is a new generation rather than a variation.
    expect(sent.params.duration_seconds).toBe(10);
    expect(sent.params.input_image).toEqual(SOURCE_FILE);
  });

  it('replays a parameter this dialog has no field type for, with its own type', async () => {
    // The form holds text; the recipe holds what was actually sent. A decimal and a boolean sent
    // back as "7.5" and "true" are refused by the provider AFTER the call is billed, or coerced
    // into something else - and the promise that only the edited field differs would be false for
    // every parameter outside the three this dialog types by name.
    renderModal({
      initialRecipe: { ...recipe, params: { guidance_scale: 7.5, raw: true } },
    });

    await screen.findByLabelText(/params\.duration_seconds/);
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    const sent = mocks.execute.mock.calls[0][0];
    expect(sent.params.guidance_scale).toBe(7.5);
    expect(sent.params.raw).toBe(true);
  });

  it('sends an EDITED parameter as a number when the model says it is one', async () => {
    // Once the reader types in the field, the original value is gone and the type has to come from
    // somewhere: the catalogue's own description of the parameter.
    renderModal({ initialRecipe: { ...recipe, params: { guidance_scale: 7.5 } } });

    // A parameter no build knows by name is labelled by its contract name, verbatim.
    const field = await screen.findByLabelText('guidance_scale');
    fireEvent.change(field, { target: { value: '9' } });
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    expect(mocks.execute.mock.calls[0][0].params.guidance_scale).toBe(9);
  });

  it('follows a model the catalogue has re-filed, instead of emptying the form', async () => {
    // A model can be re-categorised between the generation and the replay (audio becoming voice is
    // a real move here). Read as "this model is not in this format", the drift used to land the
    // reader on a DIFFERENT model with an empty prompt - a variant of something else, silently.
    mocks.getModels.mockResolvedValue({
      models: [model({ kind: 'voice' }), model({ model: 'other-video', kind: 'video' })],
      count: 2,
      kinds: ['voice', 'video'],
    });

    renderModal({ initialRecipe: recipe });

    const prompt = await screen.findByLabelText(/fields\.prompt/);
    expect(prompt).toHaveValue('a lighthouse at dusk');
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    // Same model, same parameters: the format followed the model, not the other way round.
    expect(mocks.execute.mock.calls[0][0].model).toBe('seedance-2.0');
    expect(mocks.execute.mock.calls[0][0].params.duration_seconds).toBe(10);
  });

  it('drops a parameter that is neither a file nor a value, rather than stringifying it', async () => {
    // A field reading "[object Object]" is worse than an empty one: the empty field at least says
    // what has to be filled in, while the other reaches the provider as literal text. The
    // parameter is left OUT of the request entirely - asserted on what is sent, because what the
    // screen shows would pass whether the value were dropped, stringified or sent correctly.
    renderModal({
      initialRecipe: { ...recipe, params: { duration_seconds: 10, style: { weird: true } } },
    });

    await screen.findByLabelText(/params\.duration_seconds/);
    expect(screen.queryByDisplayValue('[object Object]')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('generate'));
    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    const sent = mocks.execute.mock.calls[0][0];
    expect(sent.params).not.toHaveProperty('style');
    expect(sent.params.duration_seconds).toBe(10);
  });

  it('follows a model whose old format is now empty, instead of stranding the form', async () => {
    // The model was the ONLY one of its old format, so that format now holds nothing. Read as
    // "this model is not in this format", the reader was left on a stale format with empty
    // provider and model dropdowns over a form that was otherwise correct.
    mocks.getModels.mockResolvedValue({
      models: [model({ kind: 'voice' })],
      count: 1,
      kinds: ['voice'],
    });

    renderModal({ initialRecipe: recipe });

    await screen.findByLabelText(/params\.duration_seconds/);
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    expect(mocks.execute.mock.calls[0][0].model).toBe('seedance-2.0');
    expect(mocks.execute.mock.calls[0][0].params.duration_seconds).toBe(10);
  });

  it('does not put the previous run\'s asset on screen as if it were this run\'s result', async () => {
    // The recipe describes an asset that already exists. Landing on the result step would claim
    // something had just been generated - and charged for - when nothing had run.
    renderModal({ initialRecipe: recipe });

    await screen.findByLabelText(/fields\.prompt/);
    expect(screen.queryByTestId('file-detail-view')).not.toBeInTheDocument();
    expect(mocks.execute).not.toHaveBeenCalled();
  });
});

describe('CreateGenerationModal - the history inside the dialog', () => {
  it('swaps the form for the past generations and back, keeping what was typed', async () => {
    renderModal();

    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });

    fireEvent.click(screen.getByLabelText('history.toggle'));
    expect(screen.getByTestId('generation-history')).toBeInTheDocument();
    expect(screen.queryByLabelText(/fields\.prompt/)).not.toBeInTheDocument();

    // Back out of the history, NOT back a step: the form is where the reader was.
    fireEvent.click(screen.getByText('back'));
    expect(screen.getByLabelText(/fields\.prompt/)).toHaveValue('a boat');
  });

  it('fills the form from a past generation and brings the form back', async () => {
    renderModal();

    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.click(screen.getByLabelText('history.toggle'));
    fireEvent.click(screen.getByText('reuse-entry'));

    expect(screen.queryByTestId('generation-history')).not.toBeInTheDocument();
    expect(screen.getByLabelText(/fields\.prompt/)).toHaveValue('a lighthouse at dusk');
    expect(screen.getByLabelText(/params\.duration_seconds/)).toHaveValue(10);
  });

  it('refreshes the history once an asset has actually been generated', async () => {
    // The new asset belongs at the top of the list, here and on the Files page behind the dialog.
    renderModal();

    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.invalidateHistory).toHaveBeenCalled());
  });

  it('does not refresh the history when the generation was refused', async () => {
    mocks.execute.mockResolvedValue({ success: false, error: 'Not enough credits.' });
    renderModal();

    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    expect(mocks.invalidateHistory).not.toHaveBeenCalled();
  });
});
