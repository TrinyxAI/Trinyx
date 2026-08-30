'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Play, Bug, ChevronDown } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';

/**
 * Run control for the workflow builder, as a split button:
 *  - primary click = automatic run (unchanged `workflowViewStart` event;
 *    per-node mocks apply on their own, they are never a run-level choice),
 *  - a chevron opening a small menu (same visual pattern as the version
 *    history dropdown) with the two execution modes: Auto (default) and
 *    Step-by-step (`workflowStartStepByStep`, read by useWorkflowExecution).
 * Desktop shows icon + label on the primary; mobile is icon-only.
 *
 * Rendered by the page header (ChatHeader) and by the right side panel's
 * workflow sub-tab, which is the reason it lives here rather than inside the
 * header: a workflow opened as a panel tab is on no workflow PAGE, so the header
 * shows nothing for it and the panel has to carry the same control.
 */
export function WorkflowRunSplitButton({ workflowId, desktop }: { workflowId: string; desktop: boolean }) {
  const t = useTranslations();
  const [isMenuOpen, setIsMenuOpen] = React.useState(false);

  const dispatchRun = () => {
    setIsMenuOpen(false);
    window.dispatchEvent(new CustomEvent('workflowViewStart', { detail: { workflowId } }));
  };

  const dispatchStepByStep = () => {
    setIsMenuOpen(false);
    window.dispatchEvent(new CustomEvent('workflowStartStepByStep', { detail: { workflowId } }));
  };

  return (
    <div className="flex items-center">
      <Button
        variant="default"
        size="sm"
        onClick={(e) => {
          e.stopPropagation();
          dispatchRun();
        }}
        title={t('actions.run')}
        className={desktop ? "h-8 px-2 lg:px-3 rounded-r-none" : "h-8 px-2 rounded-r-none"}
      >
        <Play className={desktop ? "w-4 h-4 lg:mr-1" : "w-4 h-4"} />
        {desktop && <span className="hidden lg:inline">{t('actions.run')}</span>}
      </Button>
      <Popover open={isMenuOpen} onOpenChange={setIsMenuOpen}>
        <PopoverTrigger asChild>
          <Button
            variant="default"
            size="sm"
            onClick={(e) => e.stopPropagation()}
            aria-label={t('actions.run')}
            aria-haspopup="menu"
            aria-expanded={isMenuOpen}
            className="h-8 px-1 rounded-l-none border-l border-white/20 dark:border-black/20"
          >
            <ChevronDown className={`w-3.5 h-3.5 transition-transform duration-200 ${isMenuOpen ? 'rotate-180' : ''}`} />
          </Button>
        </PopoverTrigger>
        <PopoverContent
          align="end"
          className="w-56 p-2 rounded-2xl bg-theme-primary border border-theme shadow-lg"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="px-3 py-2">
            <div className="text-sm font-medium text-theme-primary">
              {t('workflowBuilder.canvas.runWorkflow')}
            </div>
          </div>
          <div role="menu" className="space-y-1">
            <button
              type="button"
              role="menuitem"
              className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
              onClick={dispatchRun}
            >
              <Play className="h-4 w-4 flex-shrink-0" strokeWidth={2} fill="currentColor" />
              <span className="text-sm flex-1 text-left truncate">{t('workflowBuilder.canvas.runAuto')}</span>
            </button>
            <button
              type="button"
              role="menuitem"
              className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
              onClick={dispatchStepByStep}
            >
              <Bug className="h-4 w-4 flex-shrink-0" strokeWidth={2} />
              <span className="text-sm flex-1 text-left truncate">{t('workflowBuilder.canvas.runStepByStep')}</span>
            </button>
          </div>
        </PopoverContent>
      </Popover>
    </div>
  );
}
