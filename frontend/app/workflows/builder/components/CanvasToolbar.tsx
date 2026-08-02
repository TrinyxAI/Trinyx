'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Panel, type Node } from 'reactflow';
import { ZoomIn, ZoomOut, Focus, Lock, Unlock, SquareDashedMousePointer, Hand, Undo2, Redo2, Settings, Wand2, Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Select,
  SelectTrigger,
  SelectContent,
  SelectItem,
} from '@/components/ui/select';
import { CanvasRunTriggerButton } from './CanvasRunTriggerButton';
import { TriggerNodePinButton } from './nodes/TriggerNodePinButton';
import type { CanvasCursorMode } from '../hooks/useBoxSelection';
import type { BuilderNodeData } from '../types';

interface CanvasToolbarProps {
  isRunMode: boolean;
  canUndo: boolean;
  canRedo: boolean;
  isInteractive: boolean;
  isLockFocused: boolean;
  cursorMode: CanvasCursorMode;
  isSettingsOpen: boolean;
  /** Canvas nodes - feed the run-mode trigger picker. */
  nodes: Node<BuilderNodeData>[];
  /**
   * Show the run-mode controls (fire a trigger, pin the workflow as production).
   * False in the read-only marketplace preview, which can neither run nor pin.
   */
  showRunControls: boolean;
  workflowId?: string;
  onUndo?: () => void;
  onRedo?: () => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFitView: () => void;
  onAutoLayout: () => void;
  onToggleInteractivity: () => void;
  onCursorModeChange: (mode: CanvasCursorMode) => void;
  onToggleSettings: () => void;
}

export function CanvasToolbar({
  isRunMode,
  canUndo,
  canRedo,
  isInteractive,
  isLockFocused,
  cursorMode,
  isSettingsOpen,
  nodes,
  showRunControls,
  workflowId,
  onUndo,
  onRedo,
  onZoomIn,
  onZoomOut,
  onFitView,
  onAutoLayout,
  onToggleInteractivity,
  onCursorModeChange,
  onToggleSettings,
}: CanvasToolbarProps) {
  const t = useTranslations('workflowBuilder.canvas');
  const cursorModeLabel = cursorMode === 'selection' ? t('cursorModeSelection') : t('cursorModePan');
  return (
    <Panel position="bottom-center" className="mb-6">
      <div className="flex items-center gap-1 rounded-full bg-white/95 dark:bg-gray-800/95 px-2 sm:px-3 py-2 backdrop-blur border-0 max-w-[calc(100vw-32px)] overflow-x-auto" style={{ scrollbarWidth: 'none' }}>
        {/* Run controls - run mode only: fire a chosen trigger, and pin the
            viewed version as production while it is not the pinned one (the
            pin button hides itself once it is). */}
        {showRunControls && (
          // empty:hidden - both children gate themselves off (no fireable
          // trigger / already on the pinned run), and a bare separator with
          // padding would be left behind otherwise.
          <div className="flex items-center gap-1 border-r border-slate-200 dark:border-slate-700 pr-1 empty:hidden" data-testid="canvas-toolbar-run-controls">
            <CanvasRunTriggerButton nodes={nodes} />
            {workflowId && <TriggerNodePinButton workflowId={workflowId} variant="toolbar" />}
          </div>
        )}

        {/* Cursor mode - only in edit mode. Persisted for every workflow, so the
            select reflects a user preference rather than per-graph state. */}
        {!isRunMode && (
          <div className="flex items-center gap-1 border-r border-slate-200 dark:border-slate-700 pr-1">
            <Select value={cursorMode} onValueChange={(value) => onCursorModeChange(value as CanvasCursorMode)}>
              <SelectTrigger
                className="h-8 min-h-8 w-auto gap-1 rounded-full border-0 bg-transparent px-2 py-0 shadow-none hover:bg-slate-100 dark:hover:bg-slate-700 focus:ring-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
                // The trigger shows an icon only, so the accessible name has to
                // carry the ACTIVE mode too - an aria-label overrides `title`.
                aria-label={`${t('cursorMode')}: ${cursorModeLabel}`}
                title={cursorModeLabel}
                data-testid="canvas-cursor-mode-select"
                // Same guards as the settings panel's selects: stop the canvas
                // pane from treating the dropdown interaction as a click on it.
                // (Box selection is already safe: its native mousedown listener
                // skips any target inside a button.)
                onClick={(e) => e.stopPropagation()}
                onMouseDown={(e) => e.stopPropagation()}
              >
                {cursorMode === 'selection'
                  ? <SquareDashedMousePointer className="h-4 w-4" />
                  : <Hand className="h-4 w-4" />}
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="pan">
                  <span className="flex items-center gap-2">
                    <Hand className="h-3.5 w-3.5" />
                    {t('cursorModePan')}
                  </span>
                </SelectItem>
                <SelectItem value="selection">
                  <span className="flex items-center gap-2">
                    <SquareDashedMousePointer className="h-3.5 w-3.5" />
                    {t('cursorModeSelection')}
                  </span>
                </SelectItem>
              </SelectContent>
            </Select>
          </div>
        )}

        {/* Undo/Redo - only in edit mode */}
        {!isRunMode && (
          <div className="flex items-center gap-1 border-r border-slate-200 dark:border-slate-700 pr-1">
            <Button
              onClick={onUndo}
              disabled={!canUndo}
              variant="ghost"
              size="sm"
              className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
              title={t('undoTooltip')}
            >
              <Undo2 className="h-4 w-4" />
            </Button>
            <Button
              onClick={onRedo}
              disabled={!canRedo}
              variant="ghost"
              size="sm"
              className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
              title={t('redoTooltip')}
            >
              <Redo2 className="h-4 w-4" />
            </Button>
          </div>
        )}

        {/* Zoom controls */}
        <div className="flex items-center gap-1 border-r border-slate-200 dark:border-slate-700 pr-1">
          <Button
            onClick={onZoomIn}
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={t('zoomIn')}
          >
            <ZoomIn className="h-4 w-4" />
          </Button>
          <Button
            onClick={onZoomOut}
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={t('zoomOut')}
          >
            <ZoomOut className="h-4 w-4" />
          </Button>
        </div>

        {/* View controls */}
        <div className="flex items-center gap-1 border-r border-slate-200 dark:border-slate-700 pr-1">
          <Button
            onClick={onFitView}
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={t('fitView')}
          >
            <Focus className="h-4 w-4" />
          </Button>
          <Button
            onClick={onAutoLayout}
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={t('autoLayout')}
          >
            <Wand2 className="h-4 w-4" />
          </Button>
        </div>

        {/* Interactivity lock */}
        <div className="flex items-center gap-1 border-r border-slate-200 dark:border-slate-700 pr-1">
          <Button
            onClick={onToggleInteractivity}
            variant={isLockFocused ? "default" : "ghost"}
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={isInteractive ? t('disableInteractivity') : t('enableInteractivity')}
          >
            {isInteractive ? (
              <Unlock className="h-4 w-4" />
            ) : (
              <Lock className="h-4 w-4" />
            )}
          </Button>
        </div>

        {/* Settings */}
        <div className="flex items-center gap-1 border-r border-slate-200 dark:border-slate-700 pr-1">
          <Button
            onClick={onToggleSettings}
            variant={isSettingsOpen ? "default" : "ghost"}
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={t('settings')}
          >
            <Settings className="h-4 w-4" />
          </Button>
        </div>

        {/* AI Assistant */}
        <div className="flex items-center gap-1">
          <Button
            onClick={() => {
              window.dispatchEvent(new CustomEvent('workflowViewToggleMessagesPanel', {
                detail: { toggle: true, view: 'chat' }
              }));
            }}
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
            title={t('openConversation')}
          >
            <Sparkles className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </Panel>
  );
}
