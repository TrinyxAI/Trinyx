// @vitest-environment jsdom

import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useChatHandoff } from './useChatHandoff';

describe('useChatHandoff', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/app/chat');
  });

  it('prefills a draft and clears the fragment without sending', () => {
    window.history.replaceState({}, '', '/app/chat#draft=Build+a+support+agent');
    const setInputValue = vi.fn();
    const sendMessage = vi.fn();

    renderHook(() => useChatHandoff({
      authLoading: false,
      setInputValue,
      sendMessage,
    }));

    expect(setInputValue).toHaveBeenCalledOnce();
    expect(setInputValue).toHaveBeenCalledWith('Build a support agent');
    expect(sendMessage).not.toHaveBeenCalled();
    expect(window.location.hash).toBe('');
  });

  it('waits for auth initialization, then sends a prompt exactly once', () => {
    window.history.replaceState({}, '', '/app/chat#prompt=Create+a+weekly+report');
    const setInputValue = vi.fn();
    const sendMessage = vi.fn();

    const { rerender } = renderHook(
      ({ authLoading }) => useChatHandoff({ authLoading, setInputValue, sendMessage }),
      { initialProps: { authLoading: true } },
    );

    expect(sendMessage).not.toHaveBeenCalled();

    act(() => rerender({ authLoading: false }));
    expect(sendMessage).toHaveBeenCalledOnce();
    expect(sendMessage).toHaveBeenCalledWith('Create a weekly report');
    expect(window.location.hash).toBe('');

    act(() => rerender({ authLoading: false }));
    expect(sendMessage).toHaveBeenCalledOnce();
  });
});
