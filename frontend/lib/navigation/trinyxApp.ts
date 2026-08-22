const FALLBACK_TRINYX_APP_ORIGIN = 'https://app.trinyx.fr';

export const TRINYX_APP_ORIGIN = (
  process.env.NEXT_PUBLIC_TRINYX_APP_ORIGIN || FALLBACK_TRINYX_APP_ORIGIN
).replace(/\/+$/, '');

export type TrinyxChatHandoffIntent = 'prompt' | 'draft';

export interface TrinyxChatHandoff {
  intent: TrinyxChatHandoffIntent;
  text: string;
}

export function buildTrinyxChatHref(
  text: string,
  intent: TrinyxChatHandoffIntent,
): string {
  const trimmed = text.trim();
  if (!trimmed) return `${TRINYX_APP_ORIGIN}/app/chat`;

  const params = new URLSearchParams();
  params.set(intent, trimmed);
  return `${TRINYX_APP_ORIGIN}/app/chat#${params.toString()}`;
}

export function readTrinyxChatHandoff(hash: string): TrinyxChatHandoff | null {
  const params = new URLSearchParams(hash.startsWith('#') ? hash.slice(1) : hash);
  const prompt = params.get('prompt')?.trim();
  if (prompt) return { intent: 'prompt', text: prompt };

  const draft = params.get('draft')?.trim();
  if (draft) return { intent: 'draft', text: draft };

  return null;
}
