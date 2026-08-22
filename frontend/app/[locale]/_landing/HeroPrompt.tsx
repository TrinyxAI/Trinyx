'use client';

import { useCallback, useState } from 'react';
import { HomeSuggestionChips } from '@/components/chat/HomeSuggestionChips';
import { MessageComposer } from '@/components/chat/MessageComposer';
import { ModelSelectorDropdown } from '@/components/chat/ModelSelectorDropdown';
import {
  buildTrinyxChatHref,
  type TrinyxChatHandoffIntent,
} from '@/lib/navigation/trinyxApp';

const EMPTY_LANDING_MODEL = { provider: '', id: '' };

export default function HeroPrompt() {
  const [value, setValue] = useState('');

  const openChat = useCallback((text: string, intent: TrinyxChatHandoffIntent) => {
    if (intent === 'prompt' && !text.trim()) return;
    window.location.assign(buildTrinyxChatHref(text, intent));
  }, []);

  const openDraft = useCallback((currentValue: string) => {
    openChat(currentValue, 'draft');
  }, [openChat]);

  return (
    <div className="mx-auto mt-10 w-full max-w-3xl text-left">
      <MessageComposer
        inputValue={value}
        onInputChange={setValue}
        onSendMessage={(content) => {
          if (content) openChat(content, 'prompt');
        }}
        showAttachmentMenu={false}
        onShowAttachmentMenu={() => {}}
        fullWidth
        defaultSkillsEnabled={false}
        onDeferredAction={openDraft}
        leadingControl={
          <ModelSelectorDropdown
            showModelSelector={false}
            setShowModelSelector={() => openDraft(value)}
            selectedModel={EMPTY_LANDING_MODEL}
            selectedModelData={{ id: '', name: 'Choose model' }}
            availableModels={[]}
            setSelectedModel={() => {}}
            changeModelTitle="Choose a model in Trinyx"
          />
        }
      />
      <HomeSuggestionChips onPick={setValue} />
    </div>
  );
}
