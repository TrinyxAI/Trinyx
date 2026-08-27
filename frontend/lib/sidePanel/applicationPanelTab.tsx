'use client';

import * as React from 'react';
import { AppWindow } from 'lucide-react';
import { ApplicationPanelContent } from '@/components/app/ApplicationSidePanel';
import type { SidePanelTab } from '@/contexts/SidePanelContext';
import { applicationPanelTabId } from '@/lib/sidePanel/tabResource';

export interface ApplicationPanelTabInput {
  /** Publication id of the application to open. */
  publicationId: string;
  /** Tab label (falls back to "Application"). */
  title?: string;
  /** Live run id from the agent's execute marker, if any. */
  runId?: string;
}

// Built next to the parser that reads it back, so the two cannot drift.
export { applicationPanelTabId };

/**
 * Build the side-panel tab descriptor for an auto-opened application.
 *
 * `keepMounted: true` is REQUIRED: when the agent opens several apps at once,
 * the SidePanel renders only the ACTIVE tab's content unless a tab is
 * keepMounted. Without it, an inactive app's data fetch is cancelled on unmount
 * and never resolves - the "only the last app resolved" bug. keepMounted keeps
 * every opened app mounted so each resolves in the background.
 */
export function buildApplicationPanelTab({ publicationId, title, runId }: ApplicationPanelTabInput): SidePanelTab {
  return {
    id: applicationPanelTabId(publicationId, runId),
    label: title || 'Application',
    icon: <AppWindow className="w-4 h-4" />,
    content: <ApplicationPanelContent publicationId={publicationId} runId={runId} />,
    preferredWidth: 0.35,
    keepMounted: true,
  };
}
