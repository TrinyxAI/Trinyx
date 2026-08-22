'use client';

import { useCallback, useState } from 'react';
import { ArrowUp, Mic, Plus, Square } from 'lucide-react';
import { buildTrinyxChatHref, type TrinyxChatHandoffIntent } from '@/lib/navigation/trinyxApp';
import { useSpeechDictation } from '@/hooks/useSpeechDictation';

export default function HeroPrompt() {
  const [value, setValue] = useState('');
  const { speechSupported, isListening, toggleDictation } = useSpeechDictation({
    value,
    onChange: setValue,
  });

  const openChat = useCallback((intent: TrinyxChatHandoffIntent) => {
    if (intent === 'prompt' && !value.trim()) return;
    window.location.assign(buildTrinyxChatHref(value, intent));
  }, [value]);

  return (
    <form
      className="hero-prompt-card"
      onSubmit={(event) => {
        event.preventDefault();
        openChat('prompt');
      }}
    >
      <label htmlFor="trinyx-hero-prompt" className="sr-only">
        Describe what you want Trinyx to build
      </label>
      <textarea
        id="trinyx-hero-prompt"
        value={value}
        onChange={(event) => setValue(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault();
            openChat('prompt');
          }
        }}
        rows={2}
        placeholder="Ask Trinyx to automate a task…"
        className="hero-prompt-input"
      />

      <div className="hero-prompt-actions">
        <button
          type="button"
          onClick={() => openChat('draft')}
          className="hero-prompt-icon"
          aria-label="Open attachments, tools, and skills in Trinyx"
          title="Open attachments, tools, and skills in Trinyx"
        >
          <Plus aria-hidden="true" />
        </button>

        <div className="hero-prompt-actions-end">
          <button
            type="submit"
            disabled={!value.trim()}
            className="hero-prompt-build"
          >
            <span>Build</span>
            <ArrowUp aria-hidden="true" />
          </button>

          {speechSupported && (
            <button
              type="button"
              onClick={toggleDictation}
              className="hero-prompt-icon"
              aria-label={isListening ? 'Stop dictation' : 'Start dictation'}
              aria-pressed={isListening}
              title={isListening ? 'Stop dictation' : 'Start dictation'}
            >
              {isListening ? <Square aria-hidden="true" /> : <Mic aria-hidden="true" />}
            </button>
          )}
        </div>
      </div>
    </form>
  );
}
