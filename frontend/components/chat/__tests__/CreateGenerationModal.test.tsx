// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * A generation is a PURCHASE, so the things this modal must get right are not
 * cosmetic: the price has to be visible before the button that spends it, a
 * refusal has to reach the reader in the words that tell them what to do, and a
 * number typed in a box has to arrive as a number, because a stringified
 * duration changes the size the run is billed on.
 *
 * The step order is the decision order: the format decides which models exist,
 * and the model decides which parameters do.
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
}));

/**
 * The upload the file picker performs. Mocked because jsdom has no network and
 * the point under test is what the modal KEEPS from the answer, not the wire
 * call: the whole file handle, which is the only shape the backend can read
 * bytes from.
 */
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileService: { uploadGeneric: mocks.uploadGeneric },
  fileRefToUrl: ({ id }: { id?: string }) => `/api/proxy/files/by-id/${id}/raw`,
  // The shared authenticated download. Stubbed because jsdom serves no bytes;
  // what this file asserts is that the button reaches it with the right handle.
  downloadStoredFile: mocks.downloadStoredFile,
}));

/**
 * Radix's Select renders its options into a portal that jsdom's missing layout
 * APIs never open, so a test cannot reach an option through it. Reduced here to
 * a native select, which is the same substitution the workflow inspector's own
 * form test makes for the same control.
 *
 * <p>Nothing below asserts on this markup: the tests read the price, the payer
 * choice and what reaches the server, all of which the real component renders
 * itself. The mock is a way to CHANGE the model, not a thing to verify.
 */
vi.mock('@/components/ui/select', () => ({
  // The testid comes from the TRIGGER's id, so each select is addressed by the
  // field it is: the dialog now has two of them, provider and model, and one
  // shared testid made "change the model" ambiguous.
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
  SelectTrigger: ({ children, id }: any) => <>{children}</>,
  SelectValue: () => null,
  SelectContent: ({ children }: any) => <>{children}</>,
  SelectItem: ({ value, children }: any) => <option value={value}>{children}</option>,
  // Rendered by the credential picker this modal now shares with the workflow
  // inspector. Left out, it resolves to undefined and the whole section throws.
  SelectSeparator: () => null,
}));

/**
 * The credential picker reaches the account through `@/lib/api/orchestrator`,
 * which is a DIFFERENT module from the `@/lib/api` the modal itself uses. Both
 * are mocked, or the section falls through to a real network call that jsdom
 * answers with a rejection, and every assertion about the keys on offer would
 * be measuring a failed fetch.
 */
vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getAllCredentials: mocks.getAllCredentials,
    getPlatformCredentialPublicInfo: mocks.getPlatformCredentialPublicInfo,
    getCredentialTemplates: mocks.getCredentialTemplates,
    getCredentialTemplateByName: mocks.getCredentialTemplateByName,
  },
}));

/**
 * The "add a key" wizard is a whole flow of its own with its own tests. What
 * this file has to prove is that the modal OFFERS it and that the key chosen
 * reaches the server, so the wizard is reduced to a marker.
 */
vi.mock('@/components/credentials/CredentialWizard', () => ({
  CredentialWizard: ({ open }: any) => (open ? <div data-testid="credential-wizard" /> : null),
  resolveByokConfig: () => ({ surface: 'hidden' }),
  resolveByokOnlyScopeList: () => [],
  resolvePlatformScopeList: () => [],
}));

vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useCreditBalance: mocks.useCreditBalance,
}));

/**
 * The Files viewer. Reduced to a marker that REPORTS what it was handed: what
 * matters here is that the result step mounts the app's one file viewer and
 * addresses it by the opaque id, not that the viewer works - it has its own
 * tests, and rendering it for real would fetch bytes jsdom cannot serve.
 */
vi.mock('@/components/app/FileDetailView', () => ({
  FileDetailView: (props: {
    entryId?: string; s3Key?: string; fileName?: string; mimeType?: string;
    sizeBytes?: number; fitMediaToHost?: boolean; chromeless?: boolean; onBack: () => void;
  }) => (
    <div
      data-testid="file-detail-view"
      data-entry-id={props.entryId ?? ''}
      data-s3-key={props.s3Key ?? ''}
      data-file-name={props.fileName ?? ''}
      data-mime={props.mimeType ?? ''}
      data-size={props.sizeBytes ?? ''}
      data-fit-host={String(!!props.fitMediaToHost)}
      data-chromeless={String(!!props.chromeless)}
    >
      <button type="button" onClick={props.onBack}>viewer-back</button>
    </div>
  ),
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
  // Identity translator: the assertions read raw keys, which keeps them
  // independent of the wording.
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${Object.values(values).join(',')}` : key,
  // The upgrade notice builds a locale-prefixed link to the plans.
  useLocale: () => 'en',
}));

vi.mock('next/link', () => ({
  default: ({ href, children }: any) => <a href={href}>{children}</a>,
}));

import { ApiError } from '@/lib/api/api-client';
import { CreateGenerationModal, readableRefusal } from '../CreateGenerationModal';

function model(over: Record<string, unknown> = {}) {
  return {
    model: 'seedance-2.0',
    kind: 'video',
    label: 'Seedance 2.0',
    provider: 'Seedance',
    iconSlug: null,
    apiToolId: 't-1',
    integrationName: 'seedance',
    accepts: ['prompt', 'duration_seconds'],
    required: ['prompt'],
    limits: { duration_seconds: { allowed: [5, 10] } },
    billedOn: 'duration_seconds',
    // What a call is COUNTED in, which the server states per model. Distinct
    // from `price.unit`, the unit it is SOLD by: they agree here and need not,
    // a model counted in seconds being sellable per minute.
    measuredUnit: 'second',
    defaultQuantity: '5',
    price: { unit: 'second', baseCredits: '0', unitCredits: '60' },
    async: true,
    ...over,
  };
}

function renderModal(props: Record<string, unknown> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <CreateGenerationModal isOpen onClose={() => {}} {...props} />
    </QueryClientProvider>,
  );
}

/** A published quote, which is what the screen is allowed to show as a price. */
function quote(over: Record<string, unknown> = {}) {
  return {
    integrationName: 'seedance',
    available: true,
    // The id of the platform key the price is published against. The server
    // always sends it alongside an endpoint quote, and the surface needs it to
    // tell "the platform sells this" from "a price exists somewhere": a rate
    // with no key behind it is not something anyone can buy. Omitting it here
    // made the fixture describe a response the server never produces.
    platformCredentialId: 42,
    hasPricing: true,
    priceUnit: 'second',
    baseCredits: '0',
    unitCredits: '60',
    markupCredits: '300',
    quantity: '5',
    ...over,
  };
}

beforeEach(() => {
  // No key of the user's own, unless a test says otherwise.
  mocks.getAllCredentials.mockResolvedValue([]);
  mocks.getCredentialTemplates.mockResolvedValue({ credentials: [] });
  mocks.getCredentialTemplateByName.mockResolvedValue(null);
  mocks.getPlatformCredentialPublicInfo.mockResolvedValue(quote());
  // A paid account by default: both buckets can pay.
  mocks.useCreditBalance.mockReturnValue({ subBalance: 500, paygBalance: 100, monthlyCreditsAreWorkflowOnly: false });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('CreateGenerationModal', () => {
  /**
   * The step header was a SEVENTH copy of the one ModalStepIndicator extracted,
   * and the only one that copy never reached, because it was drawn as read-only
   * markers (divs) rather than a row of controls. Being a copy, it had drifted
   * where it hurts most: a step you had already finished looked exactly like one
   * you had not reached yet, since it had no completed state at all - no green,
   * no check, while every other modal in the app shows both.
   *
   * It renders the shared component now, so these pin what the reader gets from
   * it rather than a class list: a finished step reads as finished, and a step
   * behind you is a way back.
   */
  /**
   * What the reader gets for their money.
   *
   * <p>The result step used to be a line of text and a download LINK: the one
   * thing that had just been paid for was the one thing this screen would not
   * show, and nothing said the asset had been written into the workspace at all.
   * It now mounts the app's single file viewer - the same one /app/files opens
   * on a click and the side panel mounts for a chat result - and offers the way
   * to where the asset lives.
   */
  describe('the finished asset', () => {
    const file = { id: 'f1', path: 'tenant/wf/out.mp4', name: 'out.mp4', mimeType: 'video/mp4', size: 2048 };

    async function generate(data: Record<string, unknown>) {
      mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
      mocks.execute.mockResolvedValue({ success: true, data });

      renderModal();
      fireEvent.click(await screen.findByText('formats.video'));
      fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
      fireEvent.click(screen.getByText('generate'));
      await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    }

    it('shows the asset in the SAME viewer the Files page opens', async () => {
      await generate({ model: 'seedance-2.0', kind: 'video', provider: 'Seedance', file });

      const viewer = await screen.findByTestId('file-detail-view');

      expect(viewer.getAttribute('data-file-name')).toBe('out.mp4');
      expect(viewer.getAttribute('data-mime')).toBe('video/mp4');
      expect(viewer.getAttribute('data-size')).toBe('2048');
    });

    it('addresses it by the opaque id, never by the storage key', async () => {
      // `path` is the S3 object key: used as a URL it resolves against the
      // current page and writes the tenant prefix into the DOM. The viewer
      // loads by id and treats the key as display-only.
      await generate({ model: 'seedance-2.0', kind: 'video', provider: 'Seedance', file });

      const viewer = await screen.findByTestId('file-detail-view');

      expect(viewer.getAttribute('data-entry-id')).toBe('f1');
    });

    it('offers the way to where the asset now lives, and closes on the way out', async () => {
      // A generation writes into the workspace. Until now the reader had to
      // know that, close the dialog and go looking.
      const onClose = vi.fn();
      mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
      mocks.execute.mockResolvedValue({
        success: true,
        data: { model: 'seedance-2.0', kind: 'video', provider: 'Seedance', file },
      });
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      render(
        <QueryClientProvider client={client}>
          <CreateGenerationModal isOpen onClose={onClose} />
        </QueryClientProvider>,
      );
      fireEvent.click(await screen.findByText('formats.video'));
      fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
      fireEvent.click(screen.getByText('generate'));
      await screen.findByTestId('file-detail-view');

      fireEvent.click(screen.getByText('openInFiles'));

      // Closed FIRST, and asserted as an ORDER: routing with it mounted would
      // leave a blocking modal floating over the destination, and "both were
      // called" stays green when the two lines are swapped.
      expect(onClose).toHaveBeenCalled();
      expect(nav.push).toHaveBeenCalledWith('/app/files');
      expect(onClose.mock.invocationCallOrder[0])
        .toBeLessThan(nav.push.mock.invocationCallOrder[0]);
    });

    it('bounds the viewer, and tells it to fit the media to that box', async () => {
      // Both halves are load-bearing and neither is visible in a screenshot: the
      // viewer fills its container, so with no height it collapses to nothing;
      // and its own caps are viewport-relative, so without this a portrait asset
      // renders taller than the frame and scrolls inside it.
      await generate({ model: 'seedance-2.0', kind: 'video', provider: 'Seedance', file });

      const viewer = await screen.findByTestId('file-detail-view');

      expect(viewer.parentElement?.className).toMatch(/h-\[\d+vh\]/);
      expect(viewer.getAttribute('data-fit-host')).toBe('true');
    });

    it('shows the asset bare, not as a card inside the dialog', async () => {
      // The asset sits directly on the dialog: no header bar of its own, no
      // border, no second surface. Boxed, the viewer brought its own header and
      // its own full-width download, which put a card inside a card and gave the
      // step three competing actions where it has one.
      await generate({ model: 'seedance-2.0', kind: 'video', provider: 'Seedance', file });

      const viewer = await screen.findByTestId('file-detail-view');
      const host = viewer.parentElement as HTMLElement;

      expect(viewer.getAttribute('data-chromeless')).toBe('true');
      expect(host.className).not.toMatch(/\bborder\b|\brounded-/);
    });

    it('offers the download beside it, through the shared authenticated helper', async () => {
      // The chromeless viewer draws no controls, so this button is the only way
      // to keep the file from this screen. It goes through the shared helper
      // rather than a second hand-written fetch: that helper is where the rule
      // "the token travels in a header, never in a URL" is enforced once.
      await generate({ model: 'seedance-2.0', kind: 'video', provider: 'Seedance', file });
      await screen.findByTestId('file-detail-view');

      fireEvent.click(screen.getByText('download'));

      await waitFor(() => expect(mocks.downloadStoredFile).toHaveBeenCalledWith('f1', 'out.mp4'));
    });

    it('keeps the dialog open after a download - keeping the file is not being done', async () => {
      const onClose = vi.fn();
      mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
      mocks.execute.mockResolvedValue({
        success: true,
        data: { model: 'seedance-2.0', kind: 'video', provider: 'Seedance', file },
      });
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      render(
        <QueryClientProvider client={client}>
          <CreateGenerationModal isOpen onClose={onClose} />
        </QueryClientProvider>,
      );
      fireEvent.click(await screen.findByText('formats.video'));
      fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
      fireEvent.click(screen.getByText('generate'));
      await screen.findByTestId('file-detail-view');

      fireEvent.click(screen.getByText('download'));

      await waitFor(() => expect(mocks.downloadStoredFile).toHaveBeenCalled());
      expect(onClose).not.toHaveBeenCalled();
    });

    it('says so when the save fails, instead of silently re-enabling the button', async () => {
      // A button that comes back to life looks exactly like a click that never
      // registered, and the reader has already paid for this file.
      mocks.downloadStoredFile.mockRejectedValueOnce(new Error('HTTP 403'));
      await generate({ model: 'seedance-2.0', kind: 'video', provider: 'Seedance', file });
      await screen.findByTestId('file-detail-view');

      fireEvent.click(screen.getByText('download'));

      expect(await screen.findByText('HTTP 403')).toBeInTheDocument();
    });

    it('offers no download when the asset came back without a handle', async () => {
      // There is nothing to fetch, so a button that could only fail is worse
      // than no button.
      await generate({ model: 'seedance-2.0', kind: 'video', provider: 'Seedance', file: { name: 'out.mp4' } });

      expect(await screen.findByText('savedNoPreview')).toBeInTheDocument();
      expect(screen.queryByText('download')).toBeNull();
      expect(screen.getByText('openInFiles')).toBeInTheDocument();
    });

    it('sends the viewer own back arrow to Files too, so it is not a dead end', async () => {
      // Its header chevron is labelled "Files". In this modal there is no list
      // behind it, so it goes where it says rather than nowhere.
      await generate({ model: 'seedance-2.0', kind: 'video', provider: 'Seedance', file });
      await screen.findByTestId('file-detail-view');

      fireEvent.click(screen.getByText('viewer-back'));

      expect(nav.push).toHaveBeenCalledWith('/app/files');
    });

    it('says where the asset is when it comes back with no id, instead of a dead link', async () => {
      // Nothing can be previewed or downloaded without the handle, but the file
      // IS in the workspace - which is the only useful thing left to say.
      await generate({ model: 'seedance-2.0', kind: 'video', provider: 'Seedance', file: { name: 'out.mp4' } });

      expect(await screen.findByText('savedNoPreview')).toBeInTheDocument();
      expect(screen.queryByTestId('file-detail-view')).toBeNull();
      // And the way to Files is still offered, since that is where it landed.
      expect(screen.getByText('openInFiles')).toBeInTheDocument();
    });
  });

  describe('the step header', () => {
    /** The dialog renders through a portal, so the steps are on the document. */
    const steps = async () => {
      return waitFor(() => {
        const found = Array.from(document.querySelectorAll('button')).filter((b) =>
          /Format|Prompt|Result|steps\./.test(b.textContent ?? ''),
        );
        // Exactly the three steps: a looser match would silently start reading
        // some other button and every assertion below would still "pass".
        expect(found).toHaveLength(3);
        return found as HTMLButtonElement[];
      });
    };

    it('turns a finished step green, so it reads as done and not as pending', async () => {
      mocks.getModels.mockResolvedValue({ models: [], count: 0, kinds: [] });

      // initialKind opens straight on step 2, which makes step 1 a finished one.
      renderModal({ initialKind: 'image' });
      const [first] = await steps();

      expect(first.className).toContain('emerald');
    });

    it('keeps the check on a finished step, and refuses it on the ones not done', async () => {
      // The check itself is NOT what this change brought - the hand-rolled copy
      // already swapped the icon. What it is here for is the contrast: the check
      // marks the finished step ONLY, so "done" cannot be confused with the step
      // you are on. Asserting the check alone would have passed before the change.
      mocks.getModels.mockResolvedValue({ models: [], count: 0, kinds: [] });

      renderModal({ initialKind: 'image' });
      const [first, second] = await steps();

      const iconOf = (b: HTMLButtonElement) => b.querySelector('svg')?.getAttribute('class') ?? '';

      expect(iconOf(first)).toContain('lucide-check');
      expect(iconOf(second)).not.toContain('lucide-check');
      // Step 3 is deliberately not part of the contrast: "Result" carries a
      // check as its OWN icon (see STEPS), so a check there says nothing about
      // whether it is finished. What separates done from pending on this header
      // is the green, which the case above pins.
    });

    it('names every step for a reader who cannot see the label', async () => {
      // The label is hidden below `sm`, which left three unnamed icon buttons in
      // the tab order at exactly the width where pointing is hardest.
      mocks.getModels.mockResolvedValue({ models: [], count: 0, kinds: [] });

      renderModal({ initialKind: 'image' });

      for (const step of await steps()) {
        expect(step.getAttribute('aria-label')?.length ?? 0).toBeGreaterThan(0);
      }
    });

    it('draws its steps as buttons on the Button rung, like every other modal', async () => {
      mocks.getModels.mockResolvedValue({ models: [], count: 0, kinds: [] });

      renderModal({ initialKind: 'image' });

      for (const step of await steps()) {
        expect(step.tagName).toBe('BUTTON');
        expect(step.className).toContain('rounded-xl');
        expect(step.className).not.toContain('rounded-full');
      }
    });

    it('lets a finished step take you back, and refuses a step you have not reached', async () => {
      mocks.getModels.mockResolvedValue({ models: [], count: 0, kinds: [] });

      renderModal({ initialKind: 'image' });
      const [first, , third] = await steps();

      expect(first.disabled).toBe(false);
      // Step 3 is the result, which does not exist until something was generated.
      expect(third.disabled).toBe(true);

      fireEvent.click(first);

      // Back on step 1, the format picker, whose heading the footer no longer
      // offers a Back out of: step 1 turns the footer button into Cancel.
      await waitFor(() => expect(screen.getByText('cancel')).toBeInTheDocument());
    });
  });

  /**
   * What opening the dialog COSTS.
   *
   * <p>A price rides on every row of the model dropdown, and each one is a
   * published quote asked of the server. That was eight requests when the image
   * catalogue held eight models; it is twenty-six now, all fired the moment the
   * button is pressed, to label a list that is still closed. On an install with
   * no platform credential every one of them returns the same "nothing is sold
   * here" that the first already established, and none of the prices is even
   * rendered, because the payer has flipped to the reader's own key.
   */
  describe('the cost of opening it', () => {
    const threeModels = () => ({
      models: [
        model({ model: 'img-a', kind: 'image', label: 'Image A', integrationName: 'a', apiToolId: 't-a' }),
        model({ model: 'img-b', kind: 'image', label: 'Image B', integrationName: 'b', apiToolId: 't-b' }),
        model({ model: 'img-c', kind: 'image', label: 'Image C', integrationName: 'c', apiToolId: 't-c' }),
      ],
      count: 3,
      kinds: ['image'],
    });

    it('asks for ONE quote when the platform sells nothing, not one per model', async () => {
      mocks.getModels.mockResolvedValue(threeModels());
      // No platform key: the answer for the first model settles it for all.
      mocks.getPlatformCredentialPublicInfo.mockResolvedValue(
        quote({ available: false, platformCredentialId: null, hasPricing: false }),
      );

      renderModal({ initialKind: 'image' });

      await screen.findByText(/Image A/);
      await waitFor(() =>
        expect(mocks.getPlatformCredentialPublicInfo).toHaveBeenCalledTimes(1));
      // And it is the CHOSEN model that was asked about, since its answer is
      // the one that decides who pays.
      expect(mocks.getPlatformCredentialPublicInfo.mock.calls[0][0]).toBe('a');
    });

    it('regression: marks only the rows the platform can actually sell', async () => {
      // The catalogue is not filtered by sellability, so a list holds both
      // kinds. A model the platform does not sell runs on the reader's own key
      // and costs no credits, and a lock on its row would be the same lie the
      // own-key branch already refuses.
      mocks.getModels.mockResolvedValue(threeModels());
      mocks.useCreditBalance.mockReturnValue({ subBalance: 800, paygBalance: 0, monthlyCreditsAreWorkflowOnly: true });
      mocks.getPlatformCredentialPublicInfo.mockImplementation(async (integration: string) =>
        (integration === 'b'
          ? quote({ available: false, platformCredentialId: null, hasPricing: false })
          : quote()));

      renderModal({ initialKind: 'image' });
      await screen.findByText(/Image A/);

      // Three models, one of them unsold: two locks.
      await waitFor(() => expect(screen.getAllByText('label')).toHaveLength(2));
    });

    it('still quotes every model once the platform is known to sell, so the list keeps its prices', async () => {
      mocks.getModels.mockResolvedValue(threeModels());
      mocks.getPlatformCredentialPublicInfo.mockResolvedValue(quote());

      renderModal({ initialKind: 'image' });

      // The deferral must not become a removal: comparing prices before
      // choosing is the reason they are on the rows at all.
      await waitFor(() =>
        expect(mocks.getPlatformCredentialPublicInfo).toHaveBeenCalledTimes(3));
    });
  });

  describe('choosing among many models', () => {
    const twoProviders = () => ({
      models: [
        model({ model: 'a-1', label: 'A One', provider: 'Alpha', integrationName: 'alpha', apiToolId: 't-a1' }),
        model({ model: 'a-2', label: 'A Two', provider: 'Alpha', integrationName: 'alpha', apiToolId: 't-a2' }),
        model({ model: 'b-1', label: 'B One', provider: 'Beta', integrationName: 'beta', apiToolId: 't-b1' }),
      ],
      count: 3,
      kinds: ['video'],
    });

    it('lists each provider once, and only that provider models under it', async () => {
      // One flat list stopped scaling: the image format alone holds twenty-six
      // models from six providers, so the first thing a reader settles is whose
      // model they want.
      mocks.getModels.mockResolvedValue(twoProviders());

      renderModal({ initialKind: 'video' });

      const providers = (await screen.findByTestId('generation-provider')) as HTMLSelectElement;
      expect([...providers.querySelectorAll('option')].map((o) => o.value).filter(Boolean))
        .toEqual(['Alpha', 'Beta']);

      const models = screen.getByTestId('generation-model') as HTMLSelectElement;
      expect([...models.querySelectorAll('option')].map((o) => o.value).filter(Boolean))
        .toEqual(['a-1', 'a-2']);
    });

    it('shows each provider mark from the slug the API catalogue ships', async () => {
      // The same asset every other surface uses, resolved from the slug rather
      // than from a table kept here: a provider added to the catalogue arrives
      // with its own mark and nothing in this file has to learn about it.
      mocks.getModels.mockResolvedValue({
        models: [model({ provider: 'OpenAI', iconSlug: 'openai' })],
        count: 1,
        kinds: ['video'],
      });

      renderModal({ initialKind: 'video' });

      await screen.findByTestId('generation-provider');
      // Queried by selector, not by role: the mark carries an empty alt on
      // purpose, which takes it out of the accessibility tree entirely. That IS
      // the accessible behaviour here, since the provider's name sits beside it
      // and a screen reader announcing the slug would say it twice.
      const icon = document.querySelector('img[src="/icons/services/openai.svg"]');
      expect(icon).not.toBeNull();
      expect(icon).toHaveAttribute('aria-hidden', 'true');
    });

    it('shows no mark at all when the catalogue ships no slug, rather than a placeholder', async () => {
      mocks.getModels.mockResolvedValue({
        models: [model({ provider: 'Nameless', iconSlug: null })],
        count: 1,
        kinds: ['video'],
      });

      renderModal({ initialKind: 'video' });

      await screen.findByTestId('generation-provider');
      expect(document.querySelector('img[src^="/icons/services/"]')).toBeNull();
    });

    it('changing provider lands on that provider first model, never on nothing', async () => {
      // The two fields must never describe different things: keeping the old
      // model would leave the provider field naming one company and the model
      // field naming another company's model.
      mocks.getModels.mockResolvedValue(twoProviders());

      renderModal({ initialKind: 'video' });

      fireEvent.change(await screen.findByTestId('generation-provider'), { target: { value: 'Beta' } });

      const models = screen.getByTestId('generation-model') as HTMLSelectElement;
      expect(models.value).toBe('b-1');
      expect([...models.querySelectorAll('option')].map((o) => o.value).filter(Boolean))
        .toEqual(['b-1']);
    });
  });

  describe('what an input file IS to the chosen model', () => {
    const withInputs = (inputs: Record<string, { role: string; maxItems: number }>) => ({
      models: [model({
        accepts: ['prompt', 'input_image'],
        required: ['prompt', 'input_image'],
        inputs,
      })],
      count: 1,
      kinds: ['video'],
    });

    it('names the field for the role the provider gave it, not with one word for all of them', async () => {
      // Runway and xAI animate FROM a still, so the file becomes the opening
      // frame; OpenAI and Stability transform the file itself; Flux never
      // returns it. "Reference image" everywhere mis-describes two of the
      // three, and the reader only finds out after paying.
      mocks.getModels.mockResolvedValue(withInputs({
        input_image: { role: 'first_frame', maxItems: 1 },
      }));

      renderModal({ initialKind: 'video' });

      expect(await screen.findByLabelText(/assetRoles\.first_frame/)).toBeInTheDocument();
      // Not numbered when there is one: "First frame 1" invites the reader to
      // look for a second.
      expect(screen.queryByLabelText(/assetRoles\.first_frame 1/)).toBeNull();
    });

    it('offers one picker per file the provider takes, numbered so they are told apart', async () => {
      mocks.getModels.mockResolvedValue(withInputs({
        input_image: { role: 'source', maxItems: 3 },
      }));

      renderModal({ initialKind: 'video' });

      expect(await screen.findByLabelText(/assetRoles\.source 1/)).toBeInTheDocument();
      expect(screen.getByLabelText(/assetRoles\.source 2/)).toBeInTheDocument();
      expect(screen.getByLabelText(/assetRoles\.source 3/)).toBeInTheDocument();
      expect(screen.queryByLabelText(/assetRoles\.source 4/)).toBeNull();
    });

    it('sends a LIST when the slot takes several, and a single file when it takes one', async () => {
      mocks.getModels.mockResolvedValue(withInputs({
        input_image: { role: 'source', maxItems: 3 },
      }));
      mocks.uploadGeneric
        .mockResolvedValueOnce({
          url: '/u/1', id: 'f-1', storageKey: 'tenant/a.png',
          fileName: 'a.png', mimeType: 'image/png', size: 10,
        })
        .mockResolvedValueOnce({
          url: '/u/2', id: 'f-2', storageKey: 'tenant/b.png',
          fileName: 'b.png', mimeType: 'image/png', size: 20,
        });
      mocks.execute.mockResolvedValue({ success: true, data: {} });

      renderModal({ initialKind: 'video' });

      fireEvent.change(await screen.findByPlaceholderText('fields.promptPlaceholder'), {
        target: { value: 'combine these' },
      });
      fireEvent.change(screen.getByLabelText(/assetRoles\.source 1/), {
        target: { files: [new File(['a'], 'a.png', { type: 'image/png' })] },
      });
      await screen.findByText('a.png');
      fireEvent.change(screen.getByLabelText(/assetRoles\.source 2/), {
        target: { files: [new File(['b'], 'b.png', { type: 'image/png' })] },
      });
      await screen.findByText('b.png');

      fireEvent.click(screen.getByText('generate'));

      await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
      const sent = mocks.execute.mock.calls.at(-1)![0].params.input_image;
      // A list, in the order the reader filled them, because the provider reads
      // them in order and the first is not interchangeable with the second.
      expect(Array.isArray(sent)).toBe(true);
      expect(sent.map((f: { name: string }) => f.name)).toEqual(['a.png', 'b.png']);
    });

    it('requires only the FIRST file, since the provider treats the rest as optional', async () => {
      mocks.getModels.mockResolvedValue(withInputs({
        input_image: { role: 'source', maxItems: 3 },
      }));
      mocks.uploadGeneric.mockResolvedValue({
        url: '/u/1', id: 'f-1', storageKey: 'tenant/a.png',
        fileName: 'a.png', mimeType: 'image/png', size: 10,
      });

      renderModal({ initialKind: 'video' });

      fireEvent.change(await screen.findByPlaceholderText('fields.promptPlaceholder'), {
        target: { value: 'just one' },
      });
      fireEvent.change(screen.getByLabelText(/assetRoles\.source 1/), {
        target: { files: [new File(['a'], 'a.png', { type: 'image/png' })] },
      });

      await waitFor(() =>
        expect(screen.getByText('generate').closest('button')).not.toBeDisabled());
    });
  });

  it('offers a real button to choose an input file, not the browser raw control', async () => {
    // The one field on this form that opens a dialog was also the one that did
    // not look like the form: the native file input draws its own button in the
    // platform's font and colours.
    mocks.getModels.mockResolvedValue({
      models: [model({ accepts: ['prompt', 'input_image'], required: ['prompt', 'input_image'] })],
      count: 1,
      kinds: ['video'],
    });

    renderModal({ initialKind: 'video' });

    expect(await screen.findByText('fields.chooseFile')).toBeInTheDocument();
    // The input stays in the DOM, hidden but still labelled, so the label and
    // keyboard focus keep working.
    const input = screen.getByLabelText(/params\.input_image/);
    expect(input).toHaveAttribute('type', 'file');
    expect(input).toHaveClass('sr-only');
    expect(screen.getByText('fields.noFile')).toBeInTheDocument();
  });

  it('offers only formats that actually have a model, so no tab is a dead end', async () => {
    mocks.getModels.mockResolvedValue({
      models: [model(), model({ model: 'tts-1', kind: 'voice' })],
      count: 2,
      kinds: ['video', 'voice'],
    });

    renderModal();

    await screen.findByText('formats.video');
    expect(screen.getByText('formats.voice')).toBeInTheDocument();
    // No image model was returned, so there is no image tile to click into.
    expect(screen.queryByText('formats.image')).not.toBeInTheDocument();
  });

  it('shows the price beside the model, BEFORE the button that spends it', async () => {
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    // 300 credits for this request, at 60 per second, stated where the model
    // is chosen.
    expect(await screen.findByText(/price\.total:300/)).toBeInTheDocument();
  });

  it('shows the PUBLISHED price, not the list rate the catalog shipped with', async () => {
    // The listing carries a seed price; the invoice is written from the pricing
    // version an administrator published. Reading the listing here is how one
    // platform ends up stating two prices for the same model, and the one it
    // states loudest is the one it does not charge.
    mocks.getModels.mockResolvedValue({
      models: [model({ price: { unit: 'second', baseCredits: '0', unitCredits: '60' } })],
      count: 1,
      kinds: ['video'],
    });
    // The owner has since doubled it.
    mocks.getPlatformCredentialPublicInfo.mockResolvedValue(
      quote({ unitCredits: '120', markupCredits: '600' }),
    );

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    expect(await screen.findByText(/price\.perUnit:120,source\.priceUnits\.second/)).toBeInTheDocument();
    expect(screen.queryByText(/price\.perUnit:60,source\.priceUnits\.second/)).not.toBeInTheDocument();
  });

  it('quotes on the size of the request, so a longer clip does not show a shorter one’s price', async () => {
    mocks.getModels.mockResolvedValue({
      models: [model({ limits: {} })],
      count: 1,
      kinds: ['video'],
    });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/params\.duration_seconds/), { target: { value: '10' } });

    // The measurement sent is the PLATFORM one (seconds), not converted here:
    // the published row owns the conversion and answers with the unit it used.
    //
    // Each field is asserted INDIVIDUALLY rather than by comparing the whole
    // object. `toHaveBeenCalledWith` compares with toEqual, which treats an
    // absent property as equal to an undefined one, so an object literal is
    // satisfied by a field simply not being sent: deleting `quantityUnit` from
    // the call site left this test green while the quote lost the ability to
    // notice a rate that cannot price this call at all.
    await waitFor(() => expect(mocks.getPlatformCredentialPublicInfo).toHaveBeenCalled());
    const [integration, toolId, quote] = mocks.getPlatformCredentialPublicInfo.mock.calls.at(-1)!;
    expect(integration).toBe('seedance');
    expect(toolId).toBe('t-1');
    expect(quote).toHaveProperty('modelId', 'seedance-2.0');
    expect(quote).toHaveProperty('quantity', 10);
    expect(quote).toHaveProperty('generation', true);
    expect(quote).toHaveProperty('quantityUnit', 'second');
  });

  it('offers no payer choice for a model the platform does not sell, and runs on the reader’s key', async () => {
    // The rule changed deliberately. Nothing published means the platform
    // REFUSES this call, so the old screen said "not sold" beside a choice the
    // reader could still make and lose. Offering two options whose first one is
    // certain to be refused is worse than offering one that works, so the
    // choice disappears and the run goes to the reader's own key, which is what
    // the server would have done anyway.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.getPlatformCredentialPublicInfo.mockResolvedValue(
      { integrationName: 'seedance', available: true, hasPricing: false },
    );
    mocks.execute.mockResolvedValue({ success: true, data: { file: { id: 'f1' } } });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    // The PAYER choice is what disappears. The section itself stays, and that
    // is the point of the change: this is exactly the install where naming the
    // key matters most, and the old screen showed nothing at all here. What is
    // on screen is the provider whose key will run and a way to add one.
    await waitFor(() => expect(screen.queryByText('source.platform')).not.toBeInTheDocument());
    expect(await screen.findByText('configure')).toBeInTheDocument();
    // Named more than once now, and deliberately: the provider field states
    // whose models are on offer, and the credential section states whose key
    // will run. Both are the same word here because there is one provider.
    expect(screen.getAllByText('Seedance').length).toBeGreaterThan(0);

    fireEvent.change(screen.getByLabelText('fields.prompt'), { target: { value: 'a cat' } });
    fireEvent.click(screen.getByText('generate'));

    // The proof is what reaches the server, not what the screen omits.
    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    expect(mocks.execute.mock.calls.at(-1)![0].credential_source).toBe('user');
  });

  it('says not sold, not a number, when the only applicable price is the credential-wide default', async () => {
    // The quote RESOLVES for this model, and positively: the credential-wide
    // default applied. A generation is never sold on a catch-all, so execution
    // refuses this exact call, and the server says so by withholding
    // `hasPricing` while still reporting the amount it found and why it does
    // not count. This is the shape the server actually emits (both the local
    // quote and the CE relay), so it is the shape pinned here: the reader must
    // get the unpriced note and never the 2 credits sitting right there in the
    // payload.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.getPlatformCredentialPublicInfo.mockResolvedValue({
      integrationName: 'seedance',
      available: true,
      hasPricing: false,
      versionDefaultOnly: true,
      defaultMarkupCredits: '2',
    });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    // No payer choice, and above all NOT the 2 credits sitting in the payload:
    // quoting an amount the server refuses is the one thing this screen must
    // never do, and the catch-all default is exactly such an amount.
    await waitFor(() => expect(screen.queryByText('source.platform')).not.toBeInTheDocument());
    expect(screen.queryByText(/price\.flat:2/)).not.toBeInTheDocument();
  });

  it('says nothing at all while the quote is still in flight', async () => {
    // An answer that has not arrived is not an answer of "no price". Printing
    // the unpriced note during the fetch tells the reader a model is
    // unavailable a moment before showing them its price.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.getPlatformCredentialPublicInfo.mockReturnValue(new Promise(() => {}));

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    expect(screen.queryByText('price.unpriced')).not.toBeInTheDocument();
  });

  it('asks nothing and offers nothing for a model with no endpoint behind it', async () => {
    // Its quote is DISABLED, and a disabled query never stops being pending.
    // Reading pending as "still loading" leaves the row permanently blank,
    // which is the blank that reads as free.
    mocks.getModels.mockResolvedValue({
      models: [model({ integrationName: null, apiToolId: null })],
      count: 1,
      kinds: ['video'],
    });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    // No endpoint means nothing the platform could sell, so no payer choice...
    await waitFor(() => expect(screen.queryByText('source.platform')).not.toBeInTheDocument());
    // ... and no key section either: with no integration there is no provider
    // whose keys could be offered, so there is nothing to configure or pick.
    expect(screen.queryByText('configure')).not.toBeInTheDocument();
    // And nothing was asked, because there is nothing to ask about. This is the
    // assertion that carries the test: the model is still listed and still
    // runnable on the reader's own key, it simply cannot be quoted.
    expect(mocks.getPlatformCredentialPublicInfo).not.toHaveBeenCalled();
  });

  it('states a flat per-call price as one amount, not as a rate times a size', async () => {
    // A per-call model has no size to multiply. Describing it with the
    // per-unit sentence would invite the reader to expect the number to change
    // with what they type, and it never will.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.getPlatformCredentialPublicInfo.mockResolvedValue(
      quote({ priceUnit: 'call', unitCredits: '0', baseCredits: '40', markupCredits: '40', quantity: null }),
    );

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    expect(await screen.findByText(/price\.flat:40/)).toBeInTheDocument();
    expect(screen.queryByText(/price\.perUnit/)).not.toBeInTheDocument();
  });

  it('quotes the rate alone when the size of the call is not known yet', async () => {
    // The backend reports no quantity when nothing was measured. Printing a
    // total anyway would state a price for a call whose size nobody has
    // decided, and the reader would read it as what they are about to pay.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.getPlatformCredentialPublicInfo.mockResolvedValue(
      quote({ quantity: null, markupCredits: '60' }),
    );

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    expect(await screen.findByText(/price\.perUnit:60,source\.priceUnits\.second/)).toBeInTheDocument();
    expect(screen.queryByText(/price\.total/)).not.toBeInTheDocument();
  });

  it('states the floor, which a rate alone hides', async () => {
    // A model at 4 per second with a minimum of 8 cannot cost 4 for a short
    // call. Quoting only the rate advertises a price no run can have.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.getPlatformCredentialPublicInfo.mockResolvedValue(
      quote({ unitCredits: '4', markupCredits: '20', minCredits: '8' }),
    );

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    expect(await screen.findByText(/price\.min:8/)).toBeInTheDocument();
  });

  it('will not run until every required parameter is there', async () => {
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    // `prompt` is required and empty.
    expect(screen.getByText('generate').closest('button')).toBeDisabled();
    expect(mocks.execute).not.toHaveBeenCalled();
  });

  it('sends a typed number as a NUMBER, since a string would change what is billed', async () => {
    mocks.getModels.mockResolvedValue({
      models: [model({ limits: {} })],
      count: 1,
      kinds: ['video'],
    });
    mocks.execute.mockResolvedValue({ success: true, data: { model: 'seedance-2.0', kind: 'video', provider: 'Seedance' } });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
    fireEvent.change(screen.getByLabelText(/params\.duration_seconds/), { target: { value: '10' } });
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    const sent = mocks.execute.mock.calls[0][0];
    expect(sent.model).toBe('seedance-2.0');
    expect(sent.params.prompt).toBe('a boat');
    expect(sent.params.duration_seconds).toBe(10);
    expect(typeof sent.params.duration_seconds).toBe('number');
  });

  it('never sends a billing scope, because the server decides where the charge lands', async () => {
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.execute.mockResolvedValue({ success: true, data: { model: 'seedance-2.0', kind: 'video', provider: 'Seedance' } });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    const sent = mocks.execute.mock.calls[0][0];
    // A browser that could name the scope would be choosing where its own
    // charge is recorded.
    expect(sent).not.toHaveProperty('billingScopeId');
    expect(sent).not.toHaveProperty('billingScopeKind');
    expect(Object.keys(sent.params)).not.toContain('__streamId__');
  });

  it('shows a refusal in the words that say what to do about it', async () => {
    // The refusals this can produce are actionable by the reader: add credits,
    // name a size, use your own key. Replacing them with a generic failure
    // would remove the only part that helps.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.execute.mockResolvedValue({
      success: false,
      error: 'PLATFORM_NOT_AVAILABLE: no price is published for this generation.',
    });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
    fireEvent.click(screen.getByText('generate'));

    expect(await screen.findByText(/no price is published/)).toBeInTheDocument();
  });

  it('reports the size it was billed on, so the charge is explainable', async () => {
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.execute.mockResolvedValue({
      success: true,
      data: { model: 'seedance-2.0', kind: 'video', provider: 'Seedance', billed_quantity: 10, billed_unit: 'second' },
    });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
    fireEvent.click(screen.getByText('generate'));

    expect(await screen.findByText('billedOn:10,source.priceUnits.second')).toBeInTheDocument();
  });

  it('names which key pays on EVERY run, because absent means something else', async () => {
    // Omitting the field is not "the default this modal shows": the server
    // reads an absent source as the fallback arrangement, tries the user's own
    // key first and only then the platform. The run could therefore use a key
    // the person never chose, while the price beside the button was quoting
    // the platform's.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.execute.mockResolvedValue({ success: true, data: { model: 'seedance-2.0', kind: 'video', provider: 'Seedance' } });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    expect(mocks.execute.mock.calls[0][0].credential_source).toBe('platform');
  });

  it('runs on the user’s own key when they ask for it', async () => {
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.execute.mockResolvedValue({ success: true, data: { model: 'seedance-2.0', kind: 'video', provider: 'Seedance' } });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.click(screen.getByText('source.user'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    expect(mocks.execute.mock.calls[0][0].credential_source).toBe('user');
  });

  it('drops the credit price once the platform is no longer the payer', async () => {
    // A price the run cannot cost is worse than no price: on the user's own key
    // the platform charges nothing, and `credential_source: 'user'` is honoured
    // strictly, so there is no path back to the quoted amount.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    expect(await screen.findByText(/price\.perUnit:60,source\.priceUnits\.second/)).toBeInTheDocument();

    fireEvent.click(screen.getByText('source.user'));
    expect(screen.queryByText(/price\.perUnit:60,source\.priceUnits\.second/)).not.toBeInTheDocument();
    // Nor the unpriced note, which would be just as wrong: the platform is not
    // refusing this run, it is not the one being paid for it.
    expect(screen.queryByText('price.unpriced')).not.toBeInTheDocument();
  });

  it('lets the reader pick their own key even with a broken one already connected', async () => {
    // The "no key of your own" warning was removed on purpose: it fired on a
    // screen that no longer forces the choice, and it told the reader something
    // the server says better when it happens. What must still hold is that a
    // dead credential does not silently become the key the run uses, so the
    // choice remains available and what is SENT is the pool that was picked.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.getAllCredentials.mockResolvedValue([
      { id: 1, integration: 'seedance', status: 'error' },
    ]);
    mocks.execute.mockResolvedValue({ success: true, data: { file: { id: 'f1' } } });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.click(await screen.findByText('source.user'));
    fireEvent.change(screen.getByLabelText('fields.prompt'), { target: { value: 'a cat' } });
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    expect(mocks.execute.mock.calls.at(-1)![0].credential_source).toBe('user');
  });

  it('runs on the account’s DEFAULT key for the provider, without being asked', async () => {
    // An account can hold several keys for one provider. Nothing on this screen
    // used to name which one would run, and nothing carried a choice either:
    // the default ran, silently. It still does, which is the right answer, but
    // now it is the one the picker is showing.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.getAllCredentials.mockResolvedValue([
      { id: 7, integration: 'seedance', name: 'Personal', status: 'active', is_default: false },
      { id: 9, integration: 'seedance', name: 'Studio', status: 'active', is_default: true },
    ]);
    mocks.execute.mockResolvedValue({ success: true, data: { file: { id: 'f1' } } });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.click(await screen.findByText('source.user'));
    await screen.findByText('Studio');

    fireEvent.change(screen.getByLabelText('fields.prompt'), { target: { value: 'a cat' } });
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    expect(mocks.execute.mock.calls.at(-1)![0].credential_id).toBe(9);
  });

  it('lets the reader run on ANOTHER of their keys for the same provider', async () => {
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.getAllCredentials.mockResolvedValue([
      { id: 7, integration: 'seedance', name: 'Personal', status: 'active', is_default: false },
      { id: 9, integration: 'seedance', name: 'Studio', status: 'active', is_default: true },
    ]);
    mocks.execute.mockResolvedValue({ success: true, data: { file: { id: 'f1' } } });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.click(await screen.findByText('source.user'));
    await screen.findByText('Personal');

    // The second select on screen is the key picker; the first is the model.
    const selects = screen.getAllByTestId('model-select');
    fireEvent.change(selects[selects.length - 1], { target: { value: '7' } });

    fireEvent.change(screen.getByLabelText('fields.prompt'), { target: { value: 'a cat' } });
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    expect(mocks.execute.mock.calls.at(-1)![0].credential_id).toBe(7);
  });

  it('forgets the pinned key when the MODEL changes, since a key belongs to one provider', async () => {
    // Two models of the same format routinely come from two providers, and the
    // dropdown is how a reader changes model. Carrying the pin across would
    // send the id of a Seedance key on a call to another provider: the server
    // refuses it and falls back, so the run fails for a "missing key" rather
    // than for the stale pin nobody meant to keep.
    mocks.getModels.mockResolvedValue({
      models: [model(), model({ model: 'other-2.0', label: 'Other', provider: 'Otherco', integrationName: 'otherco', apiToolId: 't-2' })],
      count: 2,
      kinds: ['video'],
    });
    mocks.getAllCredentials.mockResolvedValue([
      { id: 9, integration: 'seedance', name: 'Studio', status: 'active', is_default: true },
    ]);
    mocks.execute.mockResolvedValue({ success: true, data: { file: { id: 'f1' } } });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.click(await screen.findByText('source.user'));
    await screen.findByText('Studio');

    // Switch PROVIDER: the model list is scoped to one provider now, so this
    // is the control a reader uses to reach another provider's model, and it
    // lands on that provider's first model. The second provider has no key of
    // the reader's, so the pin must not survive the move.
    fireEvent.change(screen.getByTestId('generation-provider'), { target: { value: 'Otherco' } });

    fireEvent.change(screen.getByLabelText('fields.prompt'), { target: { value: 'a cat' } });
    await waitFor(() => expect(screen.getByText('generate').closest('button')).not.toBeDisabled());
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    expect(mocks.execute.mock.calls.at(-1)![0]).not.toHaveProperty('credential_id');
  });

  it('offers a way to ADD a key when the reader has none for the provider', async () => {
    // Without this the only way to learn that no key is configured was to spend
    // the click that runs the generation and read the refusal.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.getAllCredentials.mockResolvedValue([]);

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.click(await screen.findByText('source.user'));

    expect(await screen.findByText('configure')).toBeInTheDocument();
  });

  it('names no key at all while the PLATFORM is the payer, since none of yours runs', async () => {
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.getAllCredentials.mockResolvedValue([
      { id: 9, integration: 'seedance', name: 'Studio', status: 'active', is_default: true },
    ]);
    mocks.execute.mockResolvedValue({ success: true, data: { file: { id: 'f1' } } });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText('fields.prompt'), { target: { value: 'a cat' } });
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    const sent = mocks.execute.mock.calls.at(-1)![0];
    expect(sent.credential_source).toBe('platform');
    // Sending it here would state a choice the run cannot honour: the platform's
    // own key answers the call, and the catalog reads a pinned id only on the
    // other branch.
    expect(sent).not.toHaveProperty('credential_id');
  });

  it('forgets the payer choice when the modal is closed and reopened', async () => {
    // Reopening on someone else's earlier choice would spend from a pool the
    // new reader never picked.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { rerender } = render(
      <QueryClientProvider client={client}>
        <CreateGenerationModal isOpen onClose={() => {}} />
      </QueryClientProvider>,
    );
    fireEvent.click(await screen.findByText('formats.video'));
    // Awaited: the payer choice appears once the quote says the platform sells
    // this model, rather than being offered on a guess and taken away again.
    fireEvent.click(await screen.findByText('source.user'));
    expect(screen.getByText('source.user').closest('button')).toHaveAttribute('aria-checked', 'true');

    rerender(
      <QueryClientProvider client={client}>
        <CreateGenerationModal isOpen={false} onClose={() => {}} />
      </QueryClientProvider>,
    );
    rerender(
      <QueryClientProvider client={client}>
        <CreateGenerationModal isOpen onClose={() => {}} />
      </QueryClientProvider>,
    );

    fireEvent.click(await screen.findByText('formats.video'));
    expect(screen.getByText('source.platform').closest('button')).toHaveAttribute('aria-checked', 'true');
  });

  it('warns BEFORE the button when the only credits the account holds cannot pay for a generation', async () => {
    // The monthly grant funds workflow runs and nothing else, so a generation
    // on the platform key draws the top-up bucket alone. This account has 800
    // credits on screen and none that can pay. Left to the run, the refusal
    // arrives saying the balance is zero, next to a screen saying 800.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.useCreditBalance.mockReturnValue({ subBalance: 800, paygBalance: 0, monthlyCreditsAreWorkflowOnly: true });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    expect(await screen.findByRole('link', { name: 'cta' })).toBeInTheDocument();
  });

  it('offers an ADVISORY list here, unlike the workflow builder', async () => {
    // Deliberate, and the opposite of what the inspector does with the same
    // flag. There a parameter is often bound to runtime data, so closing the
    // field would take the templating away; here every value is typed by a
    // person and the alternative is a plain text box asking for an opaque
    // identifier. The trade is that a value the catalogue does not know cannot
    // be entered in this dialog.
    mocks.getModels.mockResolvedValue({
      models: [model({
        limits: { style: { allowed: ['anime', 'cinematic'], allowedEnforced: false } },
        accepts: ['prompt', 'style'],
      })],
      count: 1,
      kinds: ['video'],
    });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    // A select, not a text box: the option is there to be picked.
    expect(await screen.findByText('anime')).toBeInTheDocument();
  });

  it('marks each model row it cannot pay for, while the reader is still choosing', async () => {
    // The sentence under the picker arrives after a model is chosen; the badge
    // is on the rows themselves, which is where the choice is actually made.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.useCreditBalance.mockReturnValue({ subBalance: 800, paygBalance: 0, monthlyCreditsAreWorkflowOnly: true });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    expect(await screen.findByText('label')).toBeInTheDocument();
  });

  it('takes the row badge away once the payer is the reader’s own key', async () => {
    // Their own key is not billed in credits, so a badge there would be a lie.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.useCreditBalance.mockReturnValue({ subBalance: 800, paygBalance: 0, monthlyCreditsAreWorkflowOnly: true });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    expect(await screen.findByText('label')).toBeInTheDocument();

    fireEvent.click(screen.getByText('source.user'));

    expect(screen.queryByText('label')).not.toBeInTheDocument();
  });

  it('says nothing about credits once the payer is the user’s own key', async () => {
    // On their own key the platform charges nothing, so which credits they
    // hold stops being a fact about this run.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.useCreditBalance.mockReturnValue({ subBalance: 800, paygBalance: 0, monthlyCreditsAreWorkflowOnly: true });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    expect(await screen.findByRole('link', { name: 'cta' })).toBeInTheDocument();

    fireEvent.click(screen.getByText('source.user'));
    expect(screen.queryByRole('link', { name: 'cta' })).not.toBeInTheDocument();
  });

  it('stays quiet for an account that can actually pay', async () => {
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.useCreditBalance.mockReturnValue({ subBalance: 800, paygBalance: 300, monthlyCreditsAreWorkflowOnly: true });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    expect(screen.queryByRole('link', { name: 'cta' })).not.toBeInTheDocument();
  });

  it('stays quiet while the balance is still unknown, rather than warning on a guess', async () => {
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    // The plan rule DOES apply; what is unknown is the balance. With the flag
    // false the check short-circuits before the null guard and the test passes
    // for the wrong reason: deleting that guard left every test green.
    mocks.useCreditBalance.mockReturnValue({ subBalance: null, paygBalance: null, monthlyCreditsAreWorkflowOnly: true });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    expect(screen.queryByRole('link', { name: 'cta' })).not.toBeInTheDocument();
  });

  it('says nothing to a PAYING account whose monthly credits pay for everything', async () => {
    // A monthly balance with no top-up is the ORDINARY state of a subscriber.
    // Inferring the rule from the two balances told every PRO and TEAM customer
    // their credits could not pay, while the server charged them without
    // complaint. The rule belongs to the plan, and the server answers it.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.useCreditBalance.mockReturnValue({
      subBalance: 800, paygBalance: 0, monthlyCreditsAreWorkflowOnly: false,
    });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    expect(screen.queryByRole('link', { name: 'cta' })).not.toBeInTheDocument();
  });

  it('presents a lost connection as still running and still charged, never as a failure', async () => {
    // Giving up on the request cancels nothing: the server finishes the call,
    // stores the asset and commits the charge. Reporting "it failed" would tell
    // someone they were not charged for something they were, and hide a file
    // they now own. The proxy in front of this app caps a held request well
    // below what a video takes, so this is the ordinary outcome, not an
    // exotic one.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.execute.mockRejectedValue(new ApiError('HTTP 504: ', 504));

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
    fireEvent.click(screen.getByText('generate'));

    expect(await screen.findByText('errors.stillRunning')).toBeInTheDocument();
    expect(screen.queryByText(/504/)).not.toBeInTheDocument();
  });

  // The rule names several statuses and a fallback, and one example proves one
  // example: replacing the whole predicate with `status === 504` left every
  // test green. Each row below is a distinct branch of it.
  it.each([
    ['502 from a gateway that gave up on a working upstream', new ApiError('HTTP 502: ', 502)],
    ['524 from an edge proxy on a long generation', new ApiError('HTTP 524: ', 524)],
    ['a socket that dropped, which fetch reports as a TypeError, not an ApiError',
      new TypeError('Failed to fetch')],
  ])('says the generation is still running and charged for %s', async (_case, thrown) => {
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.execute.mockRejectedValue(thrown);

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
    fireEvent.click(screen.getByText('generate'));

    expect(await screen.findByText('errors.stillRunning')).toBeInTheDocument();
  });

  it.each([
    ['a 500, where nothing was submitted because every refusal comes back 200',
      new ApiError('Internal error', 500)],
    ['a 503, which says the upstream is down rather than slow', new ApiError('Unavailable', 503)],
    ['a 429, a request that was rejected and never ran', new ApiError('Too many requests', 429)],
  ])('does NOT claim a charge for %s', async (_case, thrown) => {
    // "It will be charged" is a statement about someone's money. Saying it when
    // nothing was submitted is a lie in the safe direction, which is still a lie.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.execute.mockRejectedValue(thrown);

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
    fireEvent.click(screen.getByText('generate'));

    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
    expect(screen.queryByText('errors.stillRunning')).not.toBeInTheDocument();
  });

  it('still reports a real fault as a fault, so the two are not collapsed', async () => {
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.execute.mockRejectedValue(new ApiError('Model was removed from the catalog', 400));

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
    fireEvent.click(screen.getByText('generate'));

    expect(await screen.findByText(/removed from the catalog/)).toBeInTheDocument();
  });

  it('REGRESSION: does not print a machine envelope at the reader when one arrives', async () => {
    // The live defect: a missing provider key came back as the whole upstream
    // envelope, and this dialog showed it verbatim - internal ids, an endpoint
    // path, a request id. Fixed on the server, where the invariant belongs;
    // guarded here because this is the last hop before a person and the string
    // has crossed six of them, two of which quote a third party.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });
    mocks.execute.mockResolvedValue({
      success: false,
      error: '{"success":false,"result":{"credential_name":"elevenlabs","error":"credentials_required"},'
        + '"metadata":{"endpoint":"/sound-generation","apiId":"e3cd5dfb"},"requestId":"c9ce98ff"}',
    });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText(/fields\.prompt/), { target: { value: 'a boat' } });
    fireEvent.click(screen.getByText('generate'));

    expect(await screen.findByText('errors.unreadable')).toBeInTheDocument();
    expect(screen.queryByText(/requestId/)).not.toBeInTheDocument();
    expect(screen.queryByText(/sound-generation/)).not.toBeInTheDocument();
  });

  it('keeps showing a refusal that merely opens with a brace, because that is still a sentence', () => {
    // The guard replaces a DOCUMENT, not a punctuation mark. A sentence can
    // never be swallowed by it, which is what makes it safe to have at all.
    expect(readableRefusal('{not json} add your ElevenLabs key.', 'fallback'))
      .toBe('{not json} add your ElevenLabs key.');
    expect(readableRefusal('CREDENTIALS_REQUIRED: add your ElevenLabs key.', 'fallback'))
      .toBe('CREDENTIALS_REQUIRED: add your ElevenLabs key.');
    // Nothing at all is worse than the generic line: an empty box says nothing.
    expect(readableRefusal(undefined, 'fallback')).toBe('fallback');
    expect(readableRefusal('   ', 'fallback')).toBe('fallback');
    // A JSON array is a document too.
    expect(readableRefusal('[{"error":"credentials_required"}]', 'fallback')).toBe('fallback');
  });

  it('opens PAST the format step when the caller names one, which is what the prop claims', async () => {
    // The prop set the format and left the reader on step 1 to pick it again,
    // so it did nothing its documentation said. The old test asserted the
    // format tile was on screen, which it is on step 1 regardless: deleting
    // the prop entirely would not have failed it.
    mocks.getModels.mockResolvedValue({ models: [model()], count: 1, kinds: ['video'] });

    renderModal({ initialKind: 'video' });

    // Step 2 is where the model is chosen, and the format tiles are behind us.
    // Matched loosely: the option carries the provider and the price beside the
    // label, so an exact-string match would pin the wording of the whole line
    // rather than the fact that we are past step 1.
    expect(await screen.findByText(/Seedance 2\.0/)).toBeInTheDocument();
    expect(screen.queryByText('modelCount:1')).not.toBeInTheDocument();
  });

  /**
   * A reference image is a FILE, and the platform reads its bytes to hand them
   * to whichever provider was chosen. Offered as a text box, the only thing a
   * reader could type is a path or a link, and neither is something the bytes
   * can be read from: the run is refused after the button that spends money.
   */
  describe('an input file', () => {
    const withImage = () =>
      model({ accepts: ['prompt', 'input_image'], required: ['prompt', 'input_image'] });

    it('is offered as a file picker, not as a box to type a path into', async () => {
      mocks.getModels.mockResolvedValue({ models: [withImage()], count: 1, kinds: ['video'] });

      renderModal({ initialKind: 'video' });

      const field = await screen.findByLabelText(/params\.input_image/);
      expect(field).toHaveAttribute('type', 'file');
      expect(field).toHaveAttribute('accept', 'image/*');
    });

    it('sends the WHOLE file handle, which is the only shape the backend can read bytes from', async () => {
      mocks.getModels.mockResolvedValue({ models: [withImage()], count: 1, kinds: ['video'] });
      mocks.uploadGeneric.mockResolvedValue({
        url: '/api/proxy/files/by-id/f-9/raw',
        id: 'f-9',
        storageKey: 'tenant-1/general/generation-input/cat.png',
        fileName: 'cat.png',
        mimeType: 'image/png',
        size: 1234,
      });
      mocks.execute.mockResolvedValue({ success: true, data: {} });

      renderModal({ initialKind: 'video' });

      fireEvent.change(await screen.findByPlaceholderText('fields.promptPlaceholder'), {
        target: { value: 'make it move' },
      });
      const picker = await screen.findByLabelText(/params\.input_image/);
      fireEvent.change(picker, {
        target: { files: [new File(['x'], 'cat.png', { type: 'image/png' })] },
      });

      // The name comes back on screen once the upload lands, which is also what
      // tells the reader the file is attached rather than merely chosen.
      expect(await screen.findByText('cat.png')).toBeInTheDocument();

      fireEvent.click(screen.getByText('generate'));

      await waitFor(() => expect(mocks.execute).toHaveBeenCalled());
      expect(mocks.execute.mock.calls[0][0].params.input_image).toEqual({
        _type: 'file',
        path: 'tenant-1/general/generation-input/cat.png',
        name: 'cat.png',
        mimeType: 'image/png',
        size: 1234,
        id: 'f-9',
      });
    });

    it('will not let the run start until the required file is actually attached', async () => {
      mocks.getModels.mockResolvedValue({ models: [withImage()], count: 1, kinds: ['video'] });

      renderModal({ initialKind: 'video' });

      fireEvent.change(await screen.findByPlaceholderText('fields.promptPlaceholder'), {
        target: { value: 'make it move' },
      });

      // The prompt alone is not enough: the model refuses the call without the
      // image, and refusing here costs nothing where refusing there costs a
      // round trip the reader believes they paid for.
      expect(screen.getByText('generate').closest('button')).toBeDisabled();
    });

    it('does not keep generating from the file the reader just replaced', async () => {
      // Pick A, then pick B and have B fail. Left attached, A would be sent:
      // the field shows an error, the button stays live, and the run produces
      // something from a file the reader believed they had replaced.
      mocks.getModels.mockResolvedValue({ models: [withImage()], count: 1, kinds: ['video'] });
      mocks.uploadGeneric
        .mockResolvedValueOnce({
          url: '/api/proxy/files/by-id/f-1/raw', id: 'f-1',
          storageKey: 'tenant-1/general/generation-input/first.png',
          fileName: 'first.png', mimeType: 'image/png', size: 10,
        })
        .mockRejectedValueOnce(new Error('storage is full'));

      renderModal({ initialKind: 'video' });

      const picker = await screen.findByLabelText(/params\.input_image/);
      fireEvent.change(picker, {
        target: { files: [new File(['a'], 'first.png', { type: 'image/png' })] },
      });
      expect(await screen.findByText('first.png')).toBeInTheDocument();

      fireEvent.change(picker, {
        target: { files: [new File(['b'], 'second.png', { type: 'image/png' })] },
      });

      expect(await screen.findByText(/fields\.uploadFailed:storage is full/)).toBeInTheDocument();
      expect(screen.queryByText('first.png')).not.toBeInTheDocument();
      // And the run cannot start, because the required file is no longer there.
      expect(screen.getByText('generate').closest('button')).toBeDisabled();
    });

    it('drops the error when the model changes, so it cannot accuse a field it never touched', async () => {
      mocks.getModels.mockResolvedValue({
        models: [withImage(), model({ model: 'other-1', label: 'Other One' })],
        count: 2,
        kinds: ['video'],
      });
      mocks.uploadGeneric.mockRejectedValue(new Error('storage is full'));

      renderModal({ initialKind: 'video' });

      fireEvent.change(await screen.findByLabelText(/params\.input_image/), {
        target: { files: [new File(['x'], 'cat.png', { type: 'image/png' })] },
      });
      expect(await screen.findByText(/fields\.uploadFailed:storage is full/)).toBeInTheDocument();

      fireEvent.change(screen.getByTestId('generation-model'), { target: { value: 'other-1' } });

      await waitFor(() =>
        expect(screen.queryByText(/fields\.uploadFailed/)).not.toBeInTheDocument());
    });

    it('reports a failed upload in place, before the reader presses the button that spends', async () => {
      mocks.getModels.mockResolvedValue({ models: [withImage()], count: 1, kinds: ['video'] });
      mocks.uploadGeneric.mockRejectedValue(new Error('storage is full'));

      renderModal({ initialKind: 'video' });

      fireEvent.change(await screen.findByLabelText(/params\.input_image/), {
        target: { files: [new File(['x'], 'cat.png', { type: 'image/png' })] },
      });

      expect(await screen.findByText(/fields\.uploadFailed:storage is full/)).toBeInTheDocument();
      expect(mocks.execute).not.toHaveBeenCalled();
    });
  });

  /**
   * Escape belongs to the dialog on top.
   *
   * <p>Regression: the chat binds its OWN document-level Escape to stop a
   * running stream. With this dialog reachable from the composer and deaf to
   * the key, the reflex that closes any dialog killed the answer being written
   * underneath and left the dialog open. The dialog now claims the key on the
   * capture phase and marks it handled, which is exactly what the chat's
   * handler stands down on.
   */
  it('regression: Escape closes it, and the key does not reach a listener underneath', async () => {
    const onClose = vi.fn();
    const underneath = vi.fn((event: KeyboardEvent) => {
      // The chat's own guard, verbatim: it steps aside once someone above has
      // taken the key.
      if (event.key !== 'Escape' || event.repeat || event.defaultPrevented) return;
      underneathRan();
    });
    const underneathRan = vi.fn();
    document.addEventListener('keydown', underneath);
    try {
      renderModal({ onClose });
      await screen.findByText('formats.video');

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(onClose).toHaveBeenCalledTimes(1);
      expect(underneathRan).not.toHaveBeenCalled();
    } finally {
      document.removeEventListener('keydown', underneath);
    }
  });

  it('regression: a dropdown open INSIDE it owns Escape, so the form is not thrown away', async () => {
    // The provider and model pickers are Radix poppers, which listen for
    // Escape on the same capture phase. Claiming the key unconditionally
    // closed the whole dialog to shut a menu, discarding the typed prompt and
    // any uploaded asset. The popper wrapper's presence is the test, so it is
    // planted here directly: this suite substitutes a native select for Radix,
    // which is precisely why the real conflict could not surface in it.
    const onClose = vi.fn();
    renderModal({ onClose });
    await screen.findByText('formats.video');

    const popper = document.createElement('div');
    popper.setAttribute('data-radix-popper-content-wrapper', '');
    document.body.appendChild(popper);
    try {
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(onClose).not.toHaveBeenCalled();
    } finally {
      popper.remove();
    }

    // And once the menu is gone the key comes back to the dialog.
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('leaves every other key, a repeat and an already-handled Escape alone', async () => {
    const onClose = vi.fn();
    renderModal({ onClose });
    await screen.findByText('formats.video');

    fireEvent.keyDown(document, { key: 'Enter' });
    fireEvent.keyDown(document, { key: 'Escape', repeat: true });
    // Already handled by something above: taking it twice would close two
    // things on one press.
    const handled = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    handled.preventDefault();
    document.dispatchEvent(handled);

    expect(onClose).not.toHaveBeenCalled();
  });

  it('stops listening once it is closed, so a later Escape is not its business', async () => {
    const onClose = vi.fn();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { rerender } = render(
      <QueryClientProvider client={client}>
        <CreateGenerationModal isOpen onClose={onClose} />
      </QueryClientProvider>,
    );
    await screen.findByText('formats.video');

    rerender(
      <QueryClientProvider client={client}>
        <CreateGenerationModal isOpen={false} onClose={onClose} />
      </QueryClientProvider>,
    );
    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onClose).not.toHaveBeenCalled();
  });

  it('Escape is ignored while a generation is in flight, which has been paid for', async () => {
    const onClose = vi.fn();
    mocks.getModels.mockResolvedValue({ models: [model({ limits: {} })], count: 1, kinds: ['video'] });
    // Never settles: the request is in flight for the rest of the test.
    mocks.execute.mockReturnValue(new Promise(() => {}));
    renderModal({ onClose });

    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText('fields.prompt'), { target: { value: 'a cat' } });
    fireEvent.click(screen.getByText('generate'));
    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());

    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    document.dispatchEvent(escape);

    // Closing the only screen that will show the result loses it.
    expect(onClose).not.toHaveBeenCalled();
    // And refusing must be QUIET: the chat underneath stops its stream on any
    // Escape it still sees, so letting the key through would trade the dialog
    // staying open for the answer being written underneath.
    expect(escape.defaultPrevented).toBe(true);
  });

  it('regression: the backdrop and the X refuse too while a generation is running', async () => {
    // The footer's Cancel was disabled while running, and the other two ways
    // out were not, which made the rule a property of one button rather than
    // of the dialog.
    const onClose = vi.fn();
    mocks.getModels.mockResolvedValue({ models: [model({ limits: {} })], count: 1, kinds: ['video'] });
    mocks.execute.mockReturnValue(new Promise(() => {}));
    renderModal({ onClose });

    fireEvent.click(await screen.findByText('formats.video'));
    fireEvent.change(screen.getByLabelText('fields.prompt'), { target: { value: 'a cat' } });
    fireEvent.click(screen.getByText('generate'));
    await waitFor(() => expect(mocks.execute).toHaveBeenCalled());

    fireEvent.click(screen.getByLabelText('close'));
    expect(onClose).not.toHaveBeenCalled();

    // The backdrop is the dialog's outermost element; clicking the panel is
    // stopped before it, so this is the only click that could dismiss.
    fireEvent.click(screen.getByRole('dialog').parentElement!);
    expect(onClose).not.toHaveBeenCalled();
  });
});

describe('CreateGenerationModal - values only the provider can name', () => {
  /**
   * An ElevenLabs voice belongs to the account holding the key: the shared
   * defaults, plus everything that account cloned or bought. No list shipped in
   * the catalogue is true for two readers, and a wrong voice id is twenty opaque
   * characters that fail at the provider AFTER the call is paid for. So the row
   * only says asking is worth it, and the dialog does the asking.
   */
  function voiceModel(over: Record<string, unknown> = {}) {
    return model({
      accepts: ['prompt', 'voice'],
      limits: { voice: { optionsAvailable: true } },
      ...over,
    });
  }

  it('offers the provider’s own values, labelled, instead of a box for an opaque id', async () => {
    mocks.getModels.mockResolvedValue({ models: [voiceModel()], count: 1, kinds: ['video'] });
    mocks.getModelOptions.mockResolvedValue({
      success: true,
      options: [{ value: '21m00', label: 'Rachel' }, { value: 'AZnzlk', label: 'Domi' }],
      count: 2,
    });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    // The NAME is what a person recognises; the id is what the run needs.
    const option = await screen.findByRole('option', { name: 'Rachel' });
    expect(option).toBeInTheDocument();
    expect((option as HTMLOptionElement).value).toBe('21m00');
  });

  it('asks with the payer the dialog is showing, and asks again when it moves', async () => {
    mocks.getModels.mockResolvedValue({ models: [voiceModel()], count: 1, kinds: ['video'] });
    mocks.getModelOptions.mockResolvedValue({ success: true, options: [{ value: 'v', label: 'V' }] });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    await screen.findByRole('option', { name: 'V' });
    expect(mocks.getModelOptions).toHaveBeenCalledWith(
      'seedance-2.0', 'voice', 'platform', null,
    );

    fireEvent.click(screen.getByText('source.user'));

    // A voice list read on the platform's key is not the reader's own: leaving
    // it on screen would offer ids the chosen key never had.
    await waitFor(() => expect(mocks.getModelOptions).toHaveBeenCalledWith(
      'seedance-2.0', 'voice', 'user', null,
    ));
  });

  it('drops a chosen value the new key does not actually offer', async () => {
    mocks.getModels.mockResolvedValue({ models: [voiceModel()], count: 1, kinds: ['video'] });
    mocks.getModelOptions
      .mockResolvedValueOnce({ success: true, options: [{ value: 'platform-only', label: 'Platform Voice' }] })
      .mockResolvedValue({ success: true, options: [{ value: 'mine', label: 'My Voice' }] });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    const select = (await screen.findByRole('option', { name: 'Platform Voice' })).closest('select')!;
    fireEvent.change(select, { target: { value: 'platform-only' } });
    expect((select as HTMLSelectElement).value).toBe('platform-only');

    fireEvent.click(screen.getByText('source.user'));

    // Left in place it still LOOKS chosen, and the run fails after it is billed.
    await waitFor(() => expect(
      (screen.getByRole('option', { name: 'My Voice' }).closest('select') as HTMLSelectElement).value,
    ).toBe(''));
  });

  it('says why the field is a text box when the values could not be had', async () => {
    mocks.getModels.mockResolvedValue({ models: [voiceModel()], count: 1, kinds: ['video'] });
    mocks.getModelOptions.mockResolvedValue({
      success: false,
      error: 'no ElevenLabs key is connected',
    });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    // Silence reads as "this field has no choices", which is a different fact
    // from "nobody was ever asked".
    expect(await screen.findByText('no ElevenLabs key is connected')).toBeInTheDocument();
  });

  it('does not ask for a field whose values the catalogue already knows', async () => {
    mocks.getModels.mockResolvedValue({
      models: [model({ accepts: ['prompt', 'style'], limits: { style: { allowed: ['anime'] } } })],
      count: 1,
      kinds: ['video'],
    });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));
    await screen.findByRole('option', { name: 'anime' });

    // Each ask costs a call to the provider. A closed set the catalogue
    // documents is not one of them.
    expect(mocks.getModelOptions).not.toHaveBeenCalled();
  });

  it('lets a value outside a SAMPLE be typed, instead of showing a closed list that is not one', async () => {
    mocks.getModels.mockResolvedValue({ models: [voiceModel()], count: 1, kinds: ['video'] });
    mocks.getModelOptions.mockResolvedValue({
      success: true,
      options: [{ value: 'v1', label: 'Voice One' }],
      truncated: true,
      total_count: 812,
    });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    // A select over a sample is a dead end: the reader whose voice is one of
    // the other 811 cannot enter it at all. Type-or-pick, one control.
    const field = await screen.findByLabelText(/params\.voice/);
    expect(field.tagName).toBe('INPUT');
    fireEvent.change(field, { target: { value: 'v800' } });
    expect((field as HTMLInputElement).value).toBe('v800');
    // And the sample is still offered, so the common case stays a click.
    // Read off the DOM rather than by role: a datalist's options are suggestions
    // attached to the input, not choices in the accessibility tree.
    const suggestions = document.getElementById(field.getAttribute('list')!);
    expect(suggestions?.querySelector('option')?.getAttribute('value')).toBe('v1');
  });

  it('regression: an empty answer SAYS so instead of leaving a bare box', async () => {
    // The live symptom was "it loads, then nothing". A field that was going to
    // offer values and ends up offering none has to say that much: silence
    // describes a refusal, an empty account and a provider that omits the
    // model equally well, and they call for different moves.
    mocks.getModels.mockResolvedValue({ models: [voiceModel()], count: 1, kinds: ['video'] });
    mocks.getModelOptions.mockResolvedValue({ success: true, options: [], count: 0 });

    renderModal();
    fireEvent.click(await screen.findByText('formats.video'));

    expect(await screen.findByText('fields.optionsEmpty')).toBeInTheDocument();
  });
});
