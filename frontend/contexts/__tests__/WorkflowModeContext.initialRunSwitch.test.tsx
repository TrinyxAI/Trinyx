/// <reference types="@testing-library/jest-dom" />
// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', () => ({
  usePathname: () => '/en/app/applications/pub',
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    prefetch: vi.fn(),
  }),
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    listVersions: vi.fn().mockResolvedValue({
      currentVersion: 1,
      pinnedVersion: null,
    }),
  },
}));

describe('WorkflowModeProvider initialRunId lifecycle', () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    cleanup();
  });

  it('clears interface stores when a new application run mounts in a fresh provider', async () => {
    const { WorkflowModeProvider } = await import('../WorkflowModeContext');
    const { useInterfacePaginationStore } = await import('@/lib/stores/interface-pagination-store');
    const { usePendingInterfacesStore } = await import('@/lib/stores/pending-interfaces-store');

    const first = render(
      <WorkflowModeProvider workflowId="workflow-a" initialRunId="run-a">
        <div />
      </WorkflowModeProvider>,
    );
    await act(async () => {});

    useInterfacePaginationStore.getState().setPage('run-a-interface', 4);
    useInterfacePaginationStore.getState().setCarouselIndex('workflow-a:run-a', 1);
    // Two surfaces, neither of which is the provider that remounts below. They
    // used to be wiped along with everything else, which handed back the
    // per-surface isolation the keyed index exists to provide: opening a second
    // application tab reset the page every other tab was showing.
    useInterfacePaginationStore.getState().setCarouselIndex('workflow-c:run-c', 2);
    usePendingInterfacesStore.getState().addPending({
      nodeId: 'interface:first',
      interfaceId: 'iface-a',
      label: 'First',
      status: 'awaiting',
      actionMapping: { continue: '__continue' },
      addedAt: Date.now(),
    });

    first.unmount();

    render(
      <WorkflowModeProvider workflowId="workflow-b" initialRunId="run-b">
        <div />
      </WorkflowModeProvider>,
    );
    await act(async () => {});

    expect(useInterfacePaginationStore.getState().pages).toEqual({});
    expect(
      useInterfacePaginationStore.getState().carouselIndex,
      'a provider mounting on another run leaves every live surface alone',
    ).toEqual({ 'workflow-a:run-a': 1, 'workflow-c:run-c': 2 });
    expect(usePendingInterfacesStore.getState().interfaces.size).toBe(0);
    expect(usePendingInterfacesStore.getState().activeNodeId).toBeNull();
  });
});
