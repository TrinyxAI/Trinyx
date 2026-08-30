'use client';

import { useCallback, useEffect, useState } from 'react';
import { isEventForWorkflow } from '@/lib/workflow/workflowEventScope';

export interface WorkflowSaveState {
  /** Feedback for the Save control: idle -> saving -> saved | error -> idle. */
  saveStatus: 'idle' | 'saving' | 'saved' | 'error';
  /** The canvas holds unsaved changes. */
  isDirty: boolean;
  /** The workflow agent is streaming into the canvas - saving mid-stream is refused. */
  isAgentStreaming: boolean;
  /** Ask the canvas of THIS workflow to save, and arm the "saving" feedback. */
  requestSave: () => void;
}

/**
 * Save-related state of ONE workflow canvas, read off the window events the
 * canvas broadcasts (`workflowDirtyChange`, `workflowViewSaveComplete`,
 * `workflowStreamingStateChange`).
 *
 * Shared by the two places that offer Save: the page header (ChatHeader) and
 * the right side panel's workflow sub-tab. Both used to be the same block of
 * effects; the panel is the reason it moved here rather than being copied, and
 * the reason every subscription is SCOPED - with a panel canvas mounted next to
 * a page canvas, an unscoped listener showed the other workflow's dirty dot and
 * cleared its own on the other one's save.
 *
 * Pass `enabled: false` to keep the hook inert (the header does this when it is
 * not on a workflow page) rather than mounting it conditionally.
 */
export function useWorkflowSaveState(workflowId?: string | null, enabled = true): WorkflowSaveState {
  const [saveStatus, setSaveStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const [isDirty, setIsDirty] = useState(false);
  const [isAgentStreaming, setIsAgentStreaming] = useState(false);

  useEffect(() => {
    if (!enabled) return;
    const handler = (event: Event) => {
      const detail = (event as CustomEvent).detail;
      if (!isEventForWorkflow(detail, workflowId)) return;
      setIsDirty(!!detail?.isDirty);
    };
    window.addEventListener('workflowDirtyChange', handler);
    return () => window.removeEventListener('workflowDirtyChange', handler);
  }, [enabled, workflowId]);

  useEffect(() => {
    if (!enabled) return;
    const handler = (event: Event) => {
      const detail = (event as CustomEvent).detail;
      if (!isEventForWorkflow(detail, workflowId)) return;
      setIsAgentStreaming(!!detail?.isStreaming);
    };
    window.addEventListener('workflowStreamingStateChange', handler);
    return () => window.removeEventListener('workflowStreamingStateChange', handler);
  }, [enabled, workflowId]);

  useEffect(() => {
    if (!enabled) return;
    let resetTimer: ReturnType<typeof setTimeout> | undefined;
    const handler = (event: Event) => {
      const detail = (event as CustomEvent).detail;
      if (!isEventForWorkflow(detail, workflowId)) return;
      const success = !!detail?.success;
      setSaveStatus(success ? 'saved' : 'error');
      if (success) setIsDirty(false);
      // Back to the neutral label after the confirmation has been read.
      clearTimeout(resetTimer);
      resetTimer = setTimeout(() => setSaveStatus('idle'), 2000);
    };
    window.addEventListener('workflowViewSaveComplete', handler);
    return () => {
      clearTimeout(resetTimer);
      window.removeEventListener('workflowViewSaveComplete', handler);
    };
  }, [enabled, workflowId]);

  const requestSave = useCallback(() => {
    if (!workflowId) return;
    setSaveStatus('saving');
    window.dispatchEvent(new CustomEvent('workflowViewSave', { detail: { workflowId } }));
  }, [workflowId]);

  return { saveStatus, isDirty, isAgentStreaming, requestSave };
}
