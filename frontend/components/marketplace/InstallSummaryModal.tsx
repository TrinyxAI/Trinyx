'use client';

import React, { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { CheckCircle, AppWindow, Workflow, Monitor, Table2, Bot, Zap } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import type { InstalledResourceCounts } from '@/lib/stores/marketplace-install-store';

/**
 * Post-install summary: what an install actually put in the workspace.
 *
 * <p>An install can create far more than the card suggests - the application plus its
 * interfaces, its tables and its agents, each landing in a different list. Until now the
 * user was told nothing and had to go find them (and, while installing also minted an
 * editable workflow copy, wonder why some of them appeared twice). This screen names
 * every resource that was created, so the workspace never changes silently.
 *
 * The counts come from the backend's acquire response, never from the publication's
 * advertised metadata: what the clone actually created is the only honest thing to show.
 */

export interface InstallSummaryTarget {
  publication: WorkflowPublication;
  resources: InstalledResourceCounts;
  /**
   * Outcome of the install-time "also create an editable copy" opt-in, absent when the
   * user did not ask for one. The copy is NOT one of the app's own resources: it is a
   * second workflow of their own, so it gets its own line and its own open action. A
   * failed copy is reported as a note, never as a failed install.
   */
  editableCopy?: { workflowId: string | null; failed: boolean };
}

/** One line per resource type, primary item first. Exported for reuse in the modal's success screen. */
export function InstalledResourcesList({
  publication,
  resources,
  editableCopy,
  onOpenEditableCopy,
}: InstallSummaryTarget & {
  /** Navigate to the editable copy. Without it the copy is named but not clickable. */
  onOpenEditableCopy?: () => void;
}) {
  const t = useTranslations('modals.acquire');
  // The editable-copy strings live in the `marketplace` namespace, where the settings-cog
  // menu offering the same action already reads them.
  const tMarketplace = useTranslations('marketplace');

  const displayMode = publication.displayMode || 'WORKFLOW';
  const type = publication.publicationType;

  // The primary item is what the user knowingly installed; the counts below are
  // everything that came with it.
  const primary: { icon: React.ReactNode; label: string } =
    type === 'AGENT' || displayMode === 'AGENT'
      ? { icon: <Bot className="h-3.5 w-3.5 text-theme-secondary shrink-0" />, label: t('installedPrimaryAgent') }
      : type === 'SKILL' || displayMode === 'SKILL'
        ? { icon: <Zap className="h-3.5 w-3.5 text-theme-secondary shrink-0" />, label: t('installedPrimarySkill') }
        : type === 'TABLE' || displayMode === 'TABLE'
          ? { icon: <Table2 className="h-3.5 w-3.5 text-theme-secondary shrink-0" />, label: t('installedPrimaryTable') }
          : type === 'INTERFACE' || displayMode === 'INTERFACE'
            ? { icon: <Monitor className="h-3.5 w-3.5 text-theme-secondary shrink-0" />, label: t('installedPrimaryInterface') }
            : displayMode === 'APPLICATION'
              ? { icon: <AppWindow className="h-3.5 w-3.5 text-theme-secondary shrink-0" />, label: t('installedPrimaryApplication') }
              : { icon: <Workflow className="h-3.5 w-3.5 text-theme-secondary shrink-0" />, label: t('installedPrimaryWorkflow') };

  // Only types we have a label for are rendered: an unknown key from a newer backend is
  // skipped rather than shown to the user as a raw identifier.
  const extras: { key: string; icon: React.ReactNode; label: string }[] = [];
  const push = (key: keyof InstalledResourceCounts & string, icon: React.ReactNode, label: (count: number) => string) => {
    const count = resources?.[key];
    if (typeof count === 'number' && count > 0) extras.push({ key, icon, label: label(count) });
  };
  push('workflows', <Workflow className="h-3.5 w-3.5 text-theme-secondary shrink-0" />, (count) => t('installedWorkflows', { count }));
  push('interfaces', <Monitor className="h-3.5 w-3.5 text-theme-secondary shrink-0" />, (count) => t('installedInterfaces', { count }));
  push('tables', <Table2 className="h-3.5 w-3.5 text-theme-secondary shrink-0" />, (count) => t('installedTables', { count }));
  push('agents', <Bot className="h-3.5 w-3.5 text-theme-secondary shrink-0" />, (count) => t('installedAgents', { count }));

  return (
    <>
      <ul className="flex flex-col gap-1.5">
        <li className="flex items-center gap-2">
          {primary.icon}
          <span className="text-sm text-theme-primary">{primary.label}</span>
        </li>
        {extras.map((extra) => (
          <li key={extra.key} className="flex items-center gap-2">
            {extra.icon}
            <span className="text-sm text-theme-primary">{extra.label}</span>
          </li>
        ))}
        {editableCopy?.workflowId && (
          <li className="flex items-center gap-2 mt-1.5 pt-1.5 border-t border-theme">
            <Workflow className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
            {onOpenEditableCopy ? (
              <button
                type="button"
                data-testid="installed-open-editable-copy"
                onClick={onOpenEditableCopy}
                className="text-sm font-medium text-[var(--accent-primary)] hover:underline"
              >
                {tMarketplace('editableCopy.openCreated')}
              </button>
            ) : (
              <span className="text-sm text-theme-primary">{tMarketplace('editableCopy.created')}</span>
            )}
          </li>
        )}
      </ul>
      {editableCopy?.failed && (
        <p
          data-testid="installed-editable-copy-failed"
          className="mt-2 pt-2 border-t border-theme text-xs text-theme-secondary leading-snug"
        >
          {tMarketplace('editableCopy.installFailed')}
        </p>
      )}
    </>
  );
}

interface InstallSummaryModalProps extends InstallSummaryTarget {
  /** Navigate to the editable copy created alongside the install, when there is one. */
  onOpenEditableCopy?: () => void;
  /** Close the summary. The install itself is already finished when this renders. */
  onClose: () => void;
  /** Primary action: go to where the installed resource lives. Falls back to closing. */
  onOpen?: () => void;
  /** Label of the primary action (the caller knows the destination). */
  openLabel?: string;
}

export function InstallSummaryModal({
  publication,
  resources,
  editableCopy,
  onOpenEditableCopy,
  onClose,
  onOpen,
  openLabel,
}: InstallSummaryModalProps) {
  const t = useTranslations('modals.acquire');
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  // Escape closes it, like every other dismissible surface here - the summary is
  // informational, so being unable to dismiss it from the keyboard would be a trap.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  if (!mounted) return null;

  return createPortal(
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] overflow-y-auto"
        role="dialog"
        aria-modal="true"
        aria-labelledby="install-summary-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="text-center">
          <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
            <CheckCircle className="h-8 w-8 text-theme-primary" />
          </div>
          <h2 id="install-summary-title" className="text-xl font-semibold text-theme-primary mb-2">
            {t('installedTitle')}
          </h2>
          <p className="text-sm text-theme-secondary mb-5">
            {t('installedIntro', { title: publication.title })}
          </p>
        </div>

        <div className="rounded-xl border border-theme bg-theme-secondary/40 p-4 mb-6 text-left">
          <InstalledResourcesList
            publication={publication}
            resources={resources}
            editableCopy={editableCopy}
            onOpenEditableCopy={onOpenEditableCopy}
          />
        </div>

        <div className="flex gap-3">
          <Button onClick={onClose} variant="outline" className="flex-1">
            {t('close')}
          </Button>
          {onOpen && (
            <Button onClick={onOpen} className="flex-1">
              {openLabel ?? t('goToApplications')}
            </Button>
          )}
        </div>
      </div>
    </div>,
    document.body
  );
}

export default InstallSummaryModal;
