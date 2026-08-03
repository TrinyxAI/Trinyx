// @vitest-environment jsdom
/**
 * Payload triggers (chat / form / webhook) open their side-panel tab instead of
 * firing blind. A workflow can hold SEVERAL triggers of one type, so the event
 * must name the exact trigger by its backend step id - matching on type alone
 * always activates the first tab of that family and drops the user's choice.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

import { NodePlayButton } from '../../NodePlayButton';
import { findTriggerTabConfig } from '@/lib/workflow/triggerTabEvent';

const captured: Array<Record<string, unknown>> = [];
const listener = (e: Event) => captured.push((e as CustomEvent).detail);

beforeEach(() => {
  captured.length = 0;
});

const clickPlay = (props: { variant: 'message' | 'form' | 'webhook'; triggerId?: string }) => {
  window.addEventListener('workflowOpenTriggerTab', listener);
  try {
    render(
      <NodePlayButton
        nodeId="chat-trigger-2"
        status="ready"
        canExecute
        onExecute={() => {}}
        position="bottom-center"
        borderColor="#000"
        {...props}
      />,
    );
    fireEvent.click(screen.getByRole('button'));
  } finally {
    window.removeEventListener('workflowOpenTriggerTab', listener);
  }
};

describe('NodePlayButton - open trigger tab event', () => {
  it.each([
    ['message', 'chat'],
    ['form', 'form'],
    ['webhook', 'webhook'],
  ])('names the exact trigger for a %s button', (variant, tab) => {
    clickPlay({ variant: variant as 'message', triggerId: 'trigger:sales_chat' });
    expect(captured).toEqual([
      { nodeId: 'chat-trigger-2', triggerId: 'trigger:sales_chat', triggerType: tab },
    ]);
  });

  it('still dispatches (type only) when no trigger id is available', () => {
    clickPlay({ variant: 'message' });
    expect(captured[0]).toMatchObject({ nodeId: 'chat-trigger-2', triggerType: 'chat' });
    expect(captured[0].triggerId).toBeUndefined();
  });

  it.each(['lightning', 'schedule', 'workflow', 'table', 'error'])(
    'fires a %s trigger directly instead of opening a tab',
    (variant) => {
      // The negative half of PANEL_TAB_BY_TRIGGER_VARIANT: a no-payload trigger
      // must execute, never route to a side-panel tab it has no input for.
      const onExecute = vi.fn();
      window.addEventListener('workflowOpenTriggerTab', listener);
      try {
        render(
          <NodePlayButton
            nodeId="n1"
            status="ready"
            canExecute
            onExecute={onExecute}
            variant={variant as 'lightning'}
            position="bottom-center"
            borderColor="#000"
          />,
        );
        fireEvent.click(screen.getByRole('button'));
      } finally {
        window.removeEventListener('workflowOpenTriggerTab', listener);
      }

      expect(onExecute).toHaveBeenCalledWith('n1');
      expect(captured).toEqual([]);
    },
  );

  describe('onBeforeLaunch - the hook that must catch BOTH exits', () => {
    /**
     * A caller with something to settle before the run starts (the run-step
     * popover leaves the focused epoch) cannot hang it off `onExecute`: that
     * hook never fires for a payload trigger, whose play opens a side-panel tab
     * and lets the run start from there. So the callback has to run on both
     * paths, exactly once, and never on a button that does nothing.
     */
    const renderPlay = (props: Record<string, unknown>) => {
      const onBeforeLaunch = vi.fn();
      const onExecute = vi.fn();
      window.addEventListener('workflowOpenTriggerTab', listener);
      try {
        render(
          <NodePlayButton
            nodeId="n1"
            status="ready"
            canExecute
            onExecute={onExecute}
            onBeforeLaunch={onBeforeLaunch}
            position="bottom-center"
            borderColor="#000"
            {...props}
          />,
        );
        const button = screen.queryByRole('button');
        if (button) fireEvent.click(button);
      } finally {
        window.removeEventListener('workflowOpenTriggerTab', listener);
      }
      return { onBeforeLaunch, onExecute };
    };

    it.each(['message', 'form', 'webhook'])('runs once BEFORE the tab opens for a %s trigger', (variant) => {
      const { onBeforeLaunch, onExecute } = renderPlay({ variant });
      expect(onBeforeLaunch).toHaveBeenCalledTimes(1);
      // The tab did open, so the callback did not replace the action.
      expect(captured).toHaveLength(1);
      // ...and the payload trigger was still not fired blind.
      expect(onExecute).not.toHaveBeenCalled();
    });

    it('runs once before firing a blind-fireable trigger', () => {
      const { onBeforeLaunch, onExecute } = renderPlay({ variant: 'lightning' });
      expect(onBeforeLaunch).toHaveBeenCalledTimes(1);
      expect(onExecute).toHaveBeenCalledWith('n1');
    });

    it.each([
      ['a pending trigger', { variant: 'lightning', status: 'pending' }],
      ['a trigger that cannot execute', { variant: 'lightning', canExecute: false }],
      ['a button mid-flight', { variant: 'lightning', isLoading: true }],
    ])('never runs on %s', (_label, props) => {
      // An inert button must not tell its caller a run is starting: the popover
      // would leave the focused epoch for a run that never happens.
      const { onBeforeLaunch, onExecute } = renderPlay(props);
      expect(onBeforeLaunch).not.toHaveBeenCalled();
      expect(onExecute).not.toHaveBeenCalled();
      expect(captured).toEqual([]);
    });
  });

  it('resolves to the picked tab, not the first one sharing the type', () => {
    // End-to-end with the panel-side resolver: this is the bug the id fixes.
    const configs = [
      { triggerId: 'trigger:support_chat', type: 'chat' },
      { triggerId: 'trigger:sales_chat', type: 'chat' },
    ];
    clickPlay({ variant: 'message', triggerId: 'trigger:sales_chat' });

    const detail = captured[0] as { nodeId: string; triggerType: 'chat'; triggerId?: string };
    expect(findTriggerTabConfig(configs, detail)?.triggerId).toBe('trigger:sales_chat');
  });
});
