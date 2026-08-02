'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';

interface BackEdgeConfigPanelProps {
  edgeId: string;
  condition: string;
  /** Undefined means "inherit": the global default cap applies. */
  maxIterations?: number;
  onConditionChange: (condition: string) => void;
  onMaxIterationsChange: (maxIterations: number | undefined) => void;
}

/**
 * Small floating panel for configuring back-edge (loop edge) properties.
 * Rendered inline in the EdgeLabelRenderer when a back-edge is selected.
 */
export function BackEdgeConfigPanel({
  edgeId,
  condition,
  maxIterations,
  onConditionChange,
  onMaxIterationsChange,
}: BackEdgeConfigPanelProps) {
  const t = useTranslations('backEdge');

  return (
    <div
      className="bg-white dark:bg-slate-800 border border-orange-300 dark:border-orange-600 rounded-lg shadow-lg p-3"
      style={{
        pointerEvents: 'all',
        minWidth: '240px',
      }}
      onClick={(e) => e.stopPropagation()}
      data-testid={`back-edge-config-${edgeId}`}
    >
      <div className="text-xs font-medium text-orange-600 dark:text-orange-400 mb-2">
        {t('title')}
      </div>

      {/* Condition */}
      <div className="mb-2">
        <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">
          {t('condition')}
        </label>
        <input
          type="text"
          value={condition}
          onChange={(e) => onConditionChange(e.target.value)}
          placeholder={t('conditionPlaceholder')}
          className="w-full text-xs px-2 py-1.5 rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-1 focus:ring-orange-500"
        />
      </div>

      {/* Max Iterations - blank inherits the workflow / global setting */}
      <div>
        <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">
          {t('maxIterations')}
        </label>
        <input
          type="number"
          value={maxIterations ?? ''}
          onChange={(e) => {
            const raw = e.target.value.trim();
            if (raw === '') {
              onMaxIterationsChange(undefined);
              return;
            }
            onMaxIterationsChange(Math.max(1, parseInt(raw, 10) || 1));
          }}
          placeholder={t('maxIterationsInherited')}
          min={1}
          max={10000}
          className="w-full text-xs px-2 py-1.5 rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-1 focus:ring-orange-500"
        />
      </div>
    </div>
  );
}
