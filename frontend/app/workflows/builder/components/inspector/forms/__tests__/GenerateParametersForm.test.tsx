// @vitest-environment jsdom
import * as React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

const apiMocks = vi.hoisted(() => ({
  getGenerationModels: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api/orchestrator')>(
    '@/lib/api/orchestrator',
  );
  return {
    ...actual,
    orchestratorApi: {
      ...actual.orchestratorApi,
      getGenerationModels: apiMocks.getGenerationModels,
    },
  };
});

// The i18n key IS the rendered text, so an assertion names the key it depends on.
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

vi.mock('@/components/ui/expression-editor', () => ({
  ExpressionEditor: ({ value, onChange, placeholder, handleId }: any) => (
    <textarea
      data-testid={handleId}
      placeholder={placeholder}
      value={value}
      onChange={(e) => onChange(e.target.value)}
    />
  ),
}));

vi.mock('@/components/ui/input', () => ({
  Input: (props: any) => <input {...props} />,
}));

vi.mock('@/components/ui/select', () => ({
  Select: ({ value, onValueChange, children, disabled }: any) => (
    <select
      data-testid="select"
      value={value ?? ''}
      disabled={disabled}
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
}));

vi.mock('@/components/ui/popover', () => ({
  Popover: ({ children }: any) => <div>{children}</div>,
  PopoverTrigger: ({ children }: any) => <div>{children}</div>,
  PopoverContent: ({ children }: any) => <div>{children}</div>,
}));

// The credential section owns the price rendering (tested on its own); here we
// only need to see WHAT the form quotes it with.
const credentialSectionProps = vi.hoisted(() => ({ last: null as any }));
vi.mock('../../CredentialSection', () => ({
  CredentialSection: (props: any) => {
    credentialSectionProps.last = props;
    return <div data-testid="credential-section" />;
  },
}));

import { GenerateParametersForm } from '../GenerateParametersForm';

const VIDEO_MODEL = {
  model: 'seedance-2.0-fast',
  kind: 'video',
  label: 'Seedance 2.0 Fast',
  provider: 'Seedance',
  iconSlug: 'seedance',
  apiToolId: 'tool-uuid',
  integrationName: 'seedance',
  accepts: ['prompt', 'duration_seconds', 'aspect_ratio'],
  required: ['prompt'],
  limits: {
    duration_seconds: { min: 1, max: 12 },
    aspect_ratio: { allowed: ['16:9', '9:16'] },
  },
  price: { unit: 'second', baseCredits: '0', unitCredits: '60' },
  async: true,
};

const VOICE_MODEL = {
  model: 'eleven-v3',
  kind: 'voice',
  label: 'Eleven v3',
  provider: 'ElevenLabs',
  iconSlug: 'elevenlabs',
  apiToolId: 'tool-uuid-2',
  integrationName: 'elevenlabs',
  accepts: ['prompt', 'voice'],
  required: ['prompt', 'voice'],
  limits: {},
  price: { unit: 'character', baseCredits: '0', unitCredits: '2' },
  async: false,
};

/** Listed per minute, still measured in seconds like every other duration. */
const MUSIC_PER_MINUTE_MODEL = {
  model: 'music-per-minute',
  kind: 'music',
  label: 'Music per minute',
  provider: 'ElevenLabs',
  iconSlug: 'elevenlabs',
  apiToolId: 'tool-uuid-3',
  integrationName: 'elevenlabs',
  accepts: ['prompt', 'duration_seconds'],
  required: ['prompt'],
  limits: {},
  price: { unit: 'minute', baseCredits: '0', unitCredits: '480' },
  async: false,
};

const connectionProps = {
  connections: [],
  draggingFromHandle: null,
  handleHandleClick: vi.fn(),
  handleHandleMouseDown: vi.fn(),
  handleHandleMouseUp: vi.fn(),
} as any;

function renderForm(data: any, onUpdate = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <GenerateParametersForm
        node={{ id: 'generate-1', data } as any}
        data={data}
        onUpdate={onUpdate}
        connectionProps={connectionProps}
        findUnknownVariables={() => []}
      />
    </QueryClientProvider>,
  );
  return { ...utils, onUpdate };
}

/**
 * The generate inspector.
 *
 * <p>Two properties are load bearing. Only the parameters the SELECTED model
 * accepts may be offered, because anything else is refused at run time. And the
 * price quoted must be for the request currently typed: a generation is charged
 * per run and a per-second model costs ten times more for a ten second clip, so
 * a rate alone is not what the user needs to see.
 */
describe('GenerateParametersForm', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    credentialSectionProps.last = null;
    apiMocks.getGenerationModels.mockResolvedValue({
      models: [VIDEO_MODEL, VOICE_MODEL],
      count: 2,
      kinds: ['video', 'voice'],
    });
  });

  afterEach(cleanup);

  it('offers every model the platform has, labelled with the format it produces', async () => {
    renderForm({});

    expect(await screen.findByText('Seedance 2.0 Fast (video)')).toBeTruthy();
    expect(screen.getByText('Eleven v3 (voice)')).toBeTruthy();
  });

  it('shows ONLY the parameters the selected model accepts', async () => {
    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });

    await waitFor(() => expect(screen.getByText('generate.params.prompt')).toBeTruthy());
    expect(screen.getByText('generate.params.duration_seconds')).toBeTruthy();
    expect(screen.getByText('generate.params.aspect_ratio')).toBeTruthy();
    // `voice` belongs to the other model; offering it here would produce a call
    // the provider refuses.
    expect(screen.queryByText('generate.params.voice')).toBeNull();
  });

  it('renders an enumerated limit as a closed list, so an unaccepted value cannot be typed', async () => {
    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });

    await waitFor(() => expect(screen.getByText('16:9')).toBeTruthy());
    expect(screen.getByText('9:16')).toBeTruthy();
  });

  it('drops the parameters the new model does not accept when the model changes', async () => {
    const { onUpdate } = renderForm({
      generateModel: 'seedance-2.0-fast',
      generateParams: { prompt: 'a boat', duration_seconds: 10, aspect_ratio: '16:9' },
    });

    // Wait for the model list to arrive: an option that is not there yet cannot be picked.
    await waitFor(() => expect(screen.getByText('Eleven v3 (voice)')).toBeTruthy());
    fireEvent.change(screen.getAllByTestId('select')[0], { target: { value: 'eleven-v3' } });

    expect(onUpdate).toHaveBeenCalled();
    const patch = onUpdate.mock.calls[0][0];
    expect(patch.generateModel).toBe('eleven-v3');
    // Carrying these over would leave the form showing values the new model refuses.
    expect(patch.generateParams).toEqual({ prompt: 'a boat' });
  });

  it('drops the pinned key when the model changes, since a key belongs to ONE provider', async () => {
    // Two models of the same format routinely come from two providers. A pin
    // carried across names a key that can never apply: the run refuses it and
    // falls back, so the node fails for a missing key rather than for the stale
    // pin. The picker cannot recover it either, because it only re-picks when
    // the new provider already has a key configured.
    const { onUpdate } = renderForm({
      generateModel: 'seedance-2.0-fast',
      generateCredentialSource: 'user',
      selectedCredentialId: 42,
      generateParams: { prompt: 'a boat' },
    });

    await waitFor(() => expect(screen.getByText('Eleven v3 (voice)')).toBeTruthy());
    fireEvent.change(screen.getAllByTestId('select')[0], { target: { value: 'eleven-v3' } });

    expect(onUpdate.mock.calls[0][0].selectedCredentialId).toBeNull();
  });

  it('quotes the price for the SELECTED MODEL and the size currently entered', async () => {
    renderForm({
      generateModel: 'seedance-2.0-fast',
      generateParams: { prompt: 'a boat', duration_seconds: 10 },
    });

    await waitFor(() => expect(credentialSectionProps.last).toBeTruthy());
    expect(credentialSectionProps.last.integration).toBe('seedance');
    expect(credentialSectionProps.last.apiToolId).toBe('tool-uuid');
    expect(credentialSectionProps.last.modelId).toBe('seedance-2.0-fast');
    // 60 credits per second x 10 seconds is the number the user must see.
    expect(credentialSectionProps.last.quantity).toBe(10);
  });

  it('quotes a per-MINUTE model with the duration in seconds, and lets the server convert it', async () => {
    // Dividing by 60 here quoted a size the billing path never sends, so the
    // estimate and the invoice were two different sums over the same call. The
    // note prints the unit and quantity the quote answers with, not this value.
    apiMocks.getGenerationModels.mockResolvedValue({
      models: [MUSIC_PER_MINUTE_MODEL],
      count: 1,
      kinds: ['music'],
    });
    renderForm({
      generateModel: 'music-per-minute',
      generateParams: { prompt: 'lofi', duration_seconds: 60 },
    });

    await waitFor(() => expect(credentialSectionProps.last).toBeTruthy());
    expect(credentialSectionProps.last.quantity).toBe(60);
  });

  it('quotes a per-character model on the length of the prompt', async () => {
    renderForm({
      generateModel: 'eleven-v3',
      generateParams: { prompt: 'hello', voice: 'rachel' },
    });

    await waitFor(() => expect(credentialSectionProps.last).toBeTruthy());
    expect(credentialSectionProps.last.modelId).toBe('eleven-v3');
    expect(credentialSectionProps.last.quantity).toBe(5);
  });

  it('passes no size when the driving parameter is empty, so no wrong total is quoted', async () => {
    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: { prompt: 'a boat' } });

    await waitFor(() => expect(credentialSectionProps.last).toBeTruthy());
    expect(credentialSectionProps.last.quantity).toBeNull();
  });

  it('defaults to the platform key, which is the arrangement the quoted price describes', async () => {
    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });

    await waitFor(() => expect(credentialSectionProps.last).toBeTruthy());
    expect(credentialSectionProps.last.credentialSource).toBe('platform');
  });

  it('shows no price section before a model is chosen: there is nothing to price yet', async () => {
    renderForm({});

    await waitFor(() => expect(screen.getByText('Seedance 2.0 Fast (video)')).toBeTruthy());
    expect(screen.queryByTestId('credential-section')).toBeNull();
  });

  it('writes a numeric parameter back as a number, so the size billed is not a string', async () => {
    const { onUpdate } = renderForm({
      generateModel: 'seedance-2.0-fast',
      generateParams: {},
    });

    await waitFor(() => expect(screen.getByText('generate.params.duration_seconds')).toBeTruthy());
    const numberInput = document.querySelector('input[type="number"]') as HTMLInputElement;
    fireEvent.change(numberInput, { target: { value: '8' } });

    expect(onUpdate).toHaveBeenCalled();
    expect(onUpdate.mock.calls[0][0].generateParams.duration_seconds).toBe(8);
  });

  it('reports when the platform offers no generation models at all', async () => {
    apiMocks.getGenerationModels.mockResolvedValue({ models: [], count: 0, kinds: [] });

    renderForm({});

    expect(await screen.findByText('generate.noModels')).toBeTruthy();
  });
});
