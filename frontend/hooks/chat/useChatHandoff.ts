'use client';

import { useEffect, useRef } from 'react';
import { readTrinyxChatHandoff } from '@/lib/navigation/trinyxApp';

interface UseChatHandoffOptions {
  authLoading: boolean;
  setInputValue: (value: string) => void;
  sendMessage: (content?: string) => void | Promise<void>;
}

export function useChatHandoff({
  authLoading,
  setInputValue,
  sendMessage,
}: UseChatHandoffOptions): void {
  const consumedRef = useRef(false);

  useEffect(() => {
    if (authLoading || consumedRef.current) return;

    const handoff = readTrinyxChatHandoff(window.location.hash);
    if (!handoff) return;

    consumedRef.current = true;
    window.history.replaceState(
      window.history.state,
      '',
      `${window.location.pathname}${window.location.search}`,
    );

    if (handoff.intent === 'draft') {
      setInputValue(handoff.text);
      return;
    }

    void sendMessage(handoff.text);
  }, [authLoading, sendMessage, setInputValue]);
}
