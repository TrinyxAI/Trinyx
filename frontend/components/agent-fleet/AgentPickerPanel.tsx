'use client';

import React, { useState } from 'react';
import { X, Search } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { canvasChromeSurfaceClass } from '@/components/ui/canvas-chrome';
import { Input } from '@/components/ui/input';
import { AvatarDisplay } from '@/components/agents';
import { useTranslations } from 'next-intl';

export interface AgentPickerItem {
  id: string;
  name: string;
  description?: string;
  avatarUrl?: string;
  modelProvider?: string;
  modelName?: string;
}

interface AgentPickerPanelProps {
  isOpen: boolean;
  onClose: () => void;
  agents: AgentPickerItem[];
  onSelectAgent: (agent: AgentPickerItem) => void;
}

export function AgentPickerPanel({
  isOpen,
  onClose,
  agents,
  onSelectAgent,
}: AgentPickerPanelProps) {
  const t = useTranslations();
  const [searchQuery, setSearchQuery] = useState('');

  if (!isOpen) return null;

  const filtered = agents.filter(a => {
    const term = searchQuery.trim().toLowerCase();
    if (!term) return true;
    return [a.name, a.description || ''].join(' ').toLowerCase().includes(term);
  });

  return (
    /* Main panel - same style as NodeCreatorPanel: a floating chrome surface,
       one radius step above the controls it holds. */
    <div
      data-agent-picker-panel
      className={`w-[min(340px,calc(100vw-48px))] max-h-[800px] flex flex-col pointer-events-auto overflow-hidden relative z-[100] ${canvasChromeSurfaceClass}`}
    >
      {/* One in-flow close button for every screen size, square and at the
          palette's control size - the same top bar NodeCreatorPanel settled on.
          It used to be two: a round pill floating at `-left-10` on desktop,
          which had to be hidden below `sm` because that negative offset clips
          off-canvas, plus a second round one inside the panel for mobile. */}
      <div className="flex justify-end px-3 pt-3 pb-0 flex-shrink-0">
        <Button onClick={onClose} variant="ghost" size="icon" className="h-7 w-7 flex-shrink-0" title={t('common.close')}>
          <X className="h-4 w-4" />
        </Button>
      </div>

      {/* Search - same as NodeCreatorPanel */}
      <div className="px-5 pt-2 flex-shrink-0">
        <div className="relative flex items-center">
          <div className="absolute left-3 pointer-events-none z-10">
            <Search className="h-4 w-4 text-[var(--text-muted)]" />
          </div>
          <Input
            type="text"
            placeholder={`${t('common.search')}...`}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9 pr-9"
            autoFocus
          />
          {searchQuery && (
            <button
              onClick={() => setSearchQuery('')}
              className="absolute right-3 z-10 text-[var(--text-muted)] hover:text-[var(--text-primary)]"
            >
              <X className="h-4 w-4" />
            </button>
          )}
        </div>
      </div>

      {/* Agent list */}
      <div className="flex-1 overflow-y-auto px-3 py-3 space-y-1">
        {filtered.length === 0 ? (
          <div className="flex items-center justify-center py-8 text-sm text-theme-secondary">
            {t('sidePanel.noResults')}
          </div>
        ) : (
          filtered.map((agent) => (
            <button
              key={agent.id}
              onClick={() => onSelectAgent(agent)}
              className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-left transition-colors hover:bg-[var(--bg-tertiary)]"
            >
              <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" className="!w-8 !h-8 flex-shrink-0" />
              <div className="flex-1 min-w-0">
                <span className="text-sm font-medium text-theme-primary truncate block">{agent.name}</span>
                {agent.description && (
                  <span className="text-xs text-theme-secondary truncate block">{agent.description}</span>
                )}
                {agent.modelProvider && agent.modelName && (
                  <span className="text-xs text-theme-muted">{agent.modelProvider}/{agent.modelName}</span>
                )}
              </div>
            </button>
          ))
        )}
      </div>
    </div>
  );
}
