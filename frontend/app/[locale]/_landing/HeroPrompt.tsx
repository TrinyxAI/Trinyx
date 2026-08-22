'use client';

import { useCallback, useMemo, useState } from 'react';
import { HomeSuggestionChips } from '@/components/chat/HomeSuggestionChips';
import { MessageComposer } from '@/components/chat/MessageComposer';
import { ModelSelectorDropdown } from '@/components/chat/ModelSelectorDropdown';
import {
  getEffectiveDefaultSelectedModel,
  modelMatches,
  useVisibleModels,
} from '@/hooks/useModels';
import { PROVIDER_ICON_MAP } from '@/lib/ai-providers/providerIcons';
import {
  buildTrinyxChatHref,
  type TrinyxChatHandoffIntent,
} from '@/lib/navigation/trinyxApp';

export default function HeroPrompt() {
  const [value, setValue] = useState('');
  const [showModelSelector, setShowModelSelector] = useState(false);
  const { models } = useVisibleModels();
  const availableModels = useMemo(
    () => models.map((model) => ({
      ...model,
      iconSlug: PROVIDER_ICON_MAP[model.provider.toLowerCase()] || model.provider.toLowerCase(),
    })),
    [models],
  );
  const selectedModel = getEffectiveDefaultSelectedModel();
  const selectedModelData = availableModels.find((model) => modelMatches(model, selectedModel));

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
            showModelSelector={showModelSelector}
            setShowModelSelector={setShowModelSelector}
            selectedModel={selectedModel}
            selectedModelData={selectedModelData}
            availableModels={availableModels}
            setSelectedModel={() => openDraft(value)}
            changeModelTitle="Choose a model in Trinyx"
          />
        }
      />
      <HomeSuggestionChips onPick={setValue} />
    </div>
  );
}
