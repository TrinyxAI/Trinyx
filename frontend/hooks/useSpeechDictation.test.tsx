// @vitest-environment jsdom

import { act, renderHook, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useSpeechDictation } from './useSpeechDictation';

describe('useSpeechDictation', () => {
  afterEach(() => {
    Reflect.deleteProperty(window, 'SpeechRecognition');
  });

  it('reuses the browser dictation stream to update the supplied value', async () => {
    let recognition: {
      onresult?: (event: { results: Array<Array<{ transcript: string }>> }) => void;
      onend?: () => void;
      start: ReturnType<typeof vi.fn>;
      stop: ReturnType<typeof vi.fn>;
    } | null = null;

    class MockSpeechRecognition {
      continuous = false;
      interimResults = false;
      lang = '';
      onresult?: (event: { results: Array<Array<{ transcript: string }>> }) => void;
      onend?: () => void;
      onerror?: () => void;
      start = vi.fn();
      stop = vi.fn(() => this.onend?.());

      constructor() {
        recognition = this;
      }
    }

    Object.defineProperty(window, 'SpeechRecognition', {
      configurable: true,
      value: MockSpeechRecognition,
    });

    const { result } = renderHook(() => {
      const [value, setValue] = useState('Build');
      return {
        value,
        ...useSpeechDictation({ value, onChange: setValue }),
      };
    });

    await waitFor(() => expect(result.current.speechSupported).toBe(true));

    act(() => result.current.toggleDictation());
    expect(recognition).not.toBeNull();

    act(() => {
      recognition?.onresult?.({
        results: [[{ transcript: 'an agent' }]],
      });
    });

    expect(result.current.value).toBe('Build an agent');
    expect(result.current.isListening).toBe(true);

    act(() => result.current.toggleDictation());
    expect(result.current.isListening).toBe(false);
  });
});
