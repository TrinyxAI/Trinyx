'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { getClientLocale } from '@/lib/utils/locale';

interface UseSpeechDictationOptions {
  value: string;
  onChange: (value: string) => void;
}

export function useSpeechDictation({ value, onChange }: UseSpeechDictationOptions) {
  const [speechSupported, setSpeechSupported] = useState(false);
  const [isListening, setIsListening] = useState(false);
  const recognitionRef = useRef<SpeechRecognition | null>(null);
  const confirmedTranscriptRef = useRef('');

  useEffect(() => {
    setSpeechSupported(
      'SpeechRecognition' in window || 'webkitSpeechRecognition' in window,
    );
  }, []);

  const toggleDictation = useCallback(() => {
    if (!speechSupported) return;

    if (isListening && recognitionRef.current) {
      recognitionRef.current.stop();
      return;
    }

    const SpeechRecognitionApi = window.SpeechRecognition || window.webkitSpeechRecognition;
    const recognition = new SpeechRecognitionApi();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = getClientLocale();
    confirmedTranscriptRef.current = value;

    recognition.onresult = (event: SpeechRecognitionEvent) => {
      let transcript = '';
      for (let index = 0; index < event.results.length; index += 1) {
        transcript += event.results[index][0].transcript;
      }

      const base = confirmedTranscriptRef.current;
      const separator = base && !base.endsWith(' ') && transcript ? ' ' : '';
      onChange(base + separator + transcript);
    };

    recognition.onend = () => {
      setIsListening(false);
      recognitionRef.current = null;
    };

    recognition.onerror = (event: SpeechRecognitionErrorEvent) => {
      console.error('Speech recognition error:', event.error);
      setIsListening(false);
      recognitionRef.current = null;
    };

    recognitionRef.current = recognition;
    recognition.start();
    setIsListening(true);
  }, [isListening, onChange, speechSupported, value]);

  useEffect(() => {
    return () => {
      recognitionRef.current?.stop();
    };
  }, []);

  return { speechSupported, isListening, toggleDictation };
}
