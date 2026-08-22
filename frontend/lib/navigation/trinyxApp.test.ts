import { describe, expect, it } from 'vitest';
import {
  buildTrinyxChatHref,
  readTrinyxChatHandoff,
  TRINYX_APP_ORIGIN,
} from './trinyxApp';

describe('Trinyx chat handoff', () => {
  it('uses the single configured app origin and encodes a prompt in the fragment', () => {
    expect(buildTrinyxChatHref('  Build a weekly report\nfrom HubSpot  ', 'prompt'))
      .toBe(`${TRINYX_APP_ORIGIN}/app/chat#prompt=Build+a+weekly+report%0Afrom+HubSpot`);
  });

  it('keeps Unicode drafts and ignores empty handoffs', () => {
    const href = buildTrinyxChatHref('Crée un agent 🤖', 'draft');
    expect(readTrinyxChatHandoff(new URL(href).hash)).toEqual({
      intent: 'draft',
      text: 'Crée un agent 🤖',
    });
    expect(readTrinyxChatHandoff('#prompt=%20%20')).toBeNull();
  });

  it('prefers an explicit prompt when both keys are present', () => {
    expect(readTrinyxChatHandoff('#draft=keep&prompt=send')).toEqual({
      intent: 'prompt',
      text: 'send',
    });
  });
});
