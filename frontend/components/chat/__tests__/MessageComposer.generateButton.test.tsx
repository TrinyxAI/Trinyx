// @vitest-environment jsdom
/**
 * The composer's way IN to a generation.
 *
 * <p>A generation makes an image, a video or a sound, which is exactly what a
 * message tends to be about, so the way to start one sits in the composer next
 * to the tools that consume one. This pins the three things that entry point
 * has to get right, all of which the Files entry point had to learn once
 * already: it is offered only to a reader who may write, it survives an answer
 * that does not arrive, and it never drags the dialog into the composer's
 * bundle before someone asks for it.
 *
 * <p>What each catalogue answer MEANS is pinned once, on the control itself
 * (`GenerateEntryButton.test.tsx`). What is left here is what only a composer
 * can get wrong: where the control sits, which composers get one, and that the
 * dialog is not MOUNTED until someone asks for it (which is a different claim
 * from where it lands in the bundle, and the only one a test can make).
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/hooks/useDefaultSkills', () => ({
  useDefaultSkills: () => ({
    activeSkillIds: new Set<string>(),
    setActiveSkillIds: vi.fn(),
    initializeDefaults: vi.fn(),
    hasExplicitSkillSelection: false,
  }),
}));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));
vi.mock('@/lib/api/orchestrator', () => ({ orchestratorApi: {} }));
vi.mock('@/components/chat/AttachmentHandler', () => ({ AttachmentHandler: () => null }));
vi.mock('@/components/chat/QueuedMessageBar', () => ({ QueuedMessageBar: () => null }));

const api = vi.hoisted(() => ({ getModels: vi.fn() }));
vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: { getModels: api.getModels },
}));

const gate = vi.hoisted(() => ({ canMutate: true }));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({}),
  useCanMutateInCurrentOrg: () => gate.canMutate,
}));

// A stand-in for the dialog: this is about whether the composer mounts it, not
// about what it draws. The real one has its own suite.
const modalProps = vi.hoisted(() => vi.fn());
vi.mock('@/components/chat/CreateGenerationModal', () => ({
  CreateGenerationModal: (props: Record<string, unknown>) => {
    modalProps(props);
    return props.isOpen ? <div data-testid="generation-modal" /> : null;
  },
}));

import { ApiError } from '@/lib/api/api-client';
import { MessageComposer } from '../MessageComposer';

/** One model, which is all "served, and has something to run" needs. */
function catalogue(models: unknown[] = [{ model: 'seedance-2.0', kind: 'video' }]) {
  return { models, count: models.length, kinds: ['video'] };
}

function renderComposer(minimal = false, disabled = false) {
  // retry: false so a 5xx is one answer rather than four spaced by backoff -
  // this suite is about what each answer means, not about the retry policy.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MessageComposer
        minimal={minimal}
        disabled={disabled}
        inputValue=""
        onInputChange={() => {}}
        onSendMessage={() => {}}
        showAttachmentMenu={false}
        onShowAttachmentMenu={() => {}}
      />
    </QueryClientProvider>,
  );
}

function generateButton(): HTMLButtonElement | null {
  return screen.queryByLabelText('chat.generateAsset') as HTMLButtonElement | null;
}

/** Wait for the catalogue answer to land, so a render is not read mid-flight. */
async function settled() {
  await waitFor(() => expect(api.getModels).toHaveBeenCalled());
  await act(async () => { await Promise.resolve(); });
}

beforeEach(() => {
  gate.canMutate = true;
  modalProps.mockClear();
  api.getModels.mockReset();
  api.getModels.mockResolvedValue(catalogue());
});
afterEach(cleanup);

describe('MessageComposer - starting a generation from the composer', () => {
  it('offers it immediately after Tools & Skills, in the same leading row', async () => {
    renderComposer();
    await settled();

    const tools = screen.getByTitle('credentials.toolsAndSkills');
    // The NEXT control, not merely a later one: "somewhere below" would also
    // hold with the button parked beside Send at the other end of the row.
    // The control wraps its button in a span, so the sibling to compare is
    // that wrapper.
    expect(tools.nextElementSibling).toBe(generateButton()!.parentElement);
  });

  it('is there in a DM too, which has no Tools at all', async () => {
    // Making an asset is about the account, not about who reads the thread, so
    // the minimal composer gets it even though the AI-only controls are absent.
    renderComposer(true);
    await settled();

    expect(screen.queryByTitle('credentials.toolsAndSkills')).not.toBeInTheDocument();
    expect(generateButton()).toBeInTheDocument();
  });

  it('stays usable on a composer that cannot send: making is not sending', async () => {
    // The builder's chat-trigger panels render this composer with `disabled`
    // as a matter of course. That prop says this message cannot be SENT yet,
    // which has nothing to say about whether an asset may be made.
    renderComposer(false, true);
    await settled();

    expect(generateButton()).toBeEnabled();
  });

  it('does not mount the dialog until it is opened', async () => {
    renderComposer();
    await settled();

    expect(modalProps).not.toHaveBeenCalled();
    expect(screen.queryByTestId('generation-modal')).not.toBeInTheDocument();
  });

  it('opens the dialog on click, and unmounts it again on close', async () => {
    renderComposer();
    await settled();

    await act(async () => { fireEvent.click(generateButton()!); });
    await waitFor(() => expect(screen.getByTestId('generation-modal')).toBeInTheDocument());

    const onClose = modalProps.mock.calls.at(-1)![0].onClose as () => void;
    await act(async () => { onClose(); });

    // Unmounted, not merely hidden: a closed dialog holding a quote and a
    // half-filled form would hand the next opening someone else's draft.
    expect(screen.queryByTestId('generation-modal')).not.toBeInTheDocument();
  });

  it('is wired to the catalogue, not merely drawn: a 404 takes it away', async () => {
    // The composer's half of the contract is that it renders the shared
    // control at all. What each answer means is pinned on that control.
    api.getModels.mockRejectedValue(new ApiError('not found', 404));
    renderComposer();
    await settled();

    await waitFor(() => expect(generateButton()).not.toBeInTheDocument());
  });

  it('asks the catalogue once, however many composers are on the page', async () => {
    // Two composers share a page (the docked chat beside the main one), and
    // they share the dialog's cache entry: the same key, one request.
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MessageComposer inputValue="" onInputChange={() => {}} onSendMessage={() => {}}
          showAttachmentMenu={false} onShowAttachmentMenu={() => {}} />
        <MessageComposer inputValue="" onInputChange={() => {}} onSendMessage={() => {}}
          showAttachmentMenu={false} onShowAttachmentMenu={() => {}} />
      </QueryClientProvider>,
    );
    await settled();

    expect(api.getModels).toHaveBeenCalledTimes(1);
  });
});
