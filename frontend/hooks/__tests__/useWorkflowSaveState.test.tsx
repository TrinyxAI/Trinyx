/**
 * @vitest-environment jsdom
 *
 * Save state is per WORKFLOW, not per window.
 *
 * The page header and the side panel's workflow sub-tab both offer Save, and
 * both read the canvas' broadcast events. With two canvases mounted (a page one
 * and a panel one) an unscoped listener showed the other workflow's unsaved dot,
 * cleared its own on the other one's save, and greyed out Save while the other
 * workflow's agent was streaming.
 */
import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useWorkflowSaveState } from '../useWorkflowSaveState';

function emit(type: string, detail: Record<string, unknown>) {
  act(() => { window.dispatchEvent(new CustomEvent(type, { detail })); });
}

afterEach(() => { vi.useRealTimers(); });

describe('useWorkflowSaveState', () => {
  it('adopts the dirty flag of its own workflow', () => {
    const { result } = renderHook(() => useWorkflowSaveState('wf-1'));

    emit('workflowDirtyChange', { isDirty: true, workflowId: 'wf-1' });

    expect(result.current.isDirty).toBe(true);
  });

  it('ignores the dirty flag of another workflow', () => {
    const { result } = renderHook(() => useWorkflowSaveState('wf-1'));

    emit('workflowDirtyChange', { isDirty: true, workflowId: 'wf-other' });

    expect(result.current.isDirty).toBe(false);
  });

  it('ignores another workflow finishing a save, so its own unsaved dot survives', () => {
    const { result } = renderHook(() => useWorkflowSaveState('wf-1'));
    emit('workflowDirtyChange', { isDirty: true, workflowId: 'wf-1' });

    emit('workflowViewSaveComplete', { success: true, workflowId: 'wf-other' });

    expect(result.current.isDirty).toBe(true);
    expect(result.current.saveStatus).toBe('idle');
  });

  it('confirms its own save and clears the dirty flag', () => {
    const { result } = renderHook(() => useWorkflowSaveState('wf-1'));
    emit('workflowDirtyChange', { isDirty: true, workflowId: 'wf-1' });

    emit('workflowViewSaveComplete', { success: true, workflowId: 'wf-1' });

    expect(result.current.saveStatus).toBe('saved');
    expect(result.current.isDirty).toBe(false);
  });

  it('surfaces a failed save as an error and keeps the changes flagged unsaved', () => {
    const { result } = renderHook(() => useWorkflowSaveState('wf-1'));
    emit('workflowDirtyChange', { isDirty: true, workflowId: 'wf-1' });

    emit('workflowViewSaveComplete', { success: false, workflowId: 'wf-1' });

    expect(result.current.saveStatus).toBe('error');
    expect(result.current.isDirty).toBe(true);
  });

  it('only its own agent stream blocks the save', () => {
    const { result } = renderHook(() => useWorkflowSaveState('wf-1'));

    emit('workflowStreamingStateChange', { isStreaming: true, workflowId: 'wf-other' });
    expect(result.current.isAgentStreaming).toBe(false);

    emit('workflowStreamingStateChange', { isStreaming: true, workflowId: 'wf-1' });
    expect(result.current.isAgentStreaming).toBe(true);
  });

  it('asks ITS workflow to save, and shows the pending state while it does', () => {
    const seen: CustomEvent[] = [];
    window.addEventListener('workflowViewSave', (e) => seen.push(e as CustomEvent));
    const { result } = renderHook(() => useWorkflowSaveState('wf-1'));

    act(() => { result.current.requestSave(); });

    expect(seen).toHaveLength(1);
    expect(seen[0].detail).toEqual({ workflowId: 'wf-1' });
    expect(result.current.saveStatus).toBe('saving');
  });

  it('returns to the neutral label once the confirmation has been read', async () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useWorkflowSaveState('wf-1'));

    emit('workflowViewSaveComplete', { success: true, workflowId: 'wf-1' });
    expect(result.current.saveStatus).toBe('saved');

    await act(async () => { vi.advanceTimersByTime(2000); });

    expect(result.current.saveStatus).toBe('idle');
  });

  it('cancels the pending reset on unmount instead of firing it on a dead hook', () => {
    vi.useFakeTimers();
    const clearTimeoutSpy = vi.spyOn(globalThis, 'clearTimeout');
    const { unmount } = renderHook(() => useWorkflowSaveState('wf-1'));
    // Arms the 2s reset.
    emit('workflowViewSaveComplete', { success: true, workflowId: 'wf-1' });
    clearTimeoutSpy.mockClear();

    unmount();

    // Asserting the state after unmount proves nothing: `result.current` is
    // frozen at the last render either way. The cancellation is the behaviour.
    expect(clearTimeoutSpy).toHaveBeenCalled();
    clearTimeoutSpy.mockRestore();
  });

  it('asks nothing of a workflow it does not have', () => {
    const seen: Event[] = [];
    window.addEventListener('workflowViewSave', (e) => seen.push(e));
    const { result } = renderHook(() => useWorkflowSaveState(undefined));

    act(() => { result.current.requestSave(); });

    expect(seen).toHaveLength(0);
    expect(result.current.saveStatus, 'and it does not pretend to be saving').toBe('idle');
  });

  it('subscribes to nothing while disabled', () => {
    const { result } = renderHook(() => useWorkflowSaveState('wf-1', false));

    emit('workflowDirtyChange', { isDirty: true, workflowId: 'wf-1' });

    expect(result.current.isDirty).toBe(false);
  });
});
