'use client';

import React, { useCallback, useState } from 'react';
import { Loader2, Settings, Volume2, VolumeX, Workflow } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Link } from '@/i18n/navigation';
import { publicationService } from '@/lib/api/orchestrator/publication.service';

interface ApplicationSettingsMenuProps {
  publicationId: string;
  /**
   * Cloud-linked CE: the publication lives on the cloud, so the copy goes through
   * the remote route (the local publication row does not exist).
   */
  remote?: boolean;
  /**
   * Offer the "Create an editable copy" entry. When false, this entry is absent -
   * but the cog can still exist for the sound entry below. It is the combination
   * that decides: a menu with NO entry at all is a dead affordance and is not
   * rendered.
   */
  canCreateEditableCopy?: boolean;
  /**
   * Current mute state of the application's own audio/video, or {@code undefined}
   * when this application has none - in which case no sound entry is offered.
   *
   * Only the interface's frame can tell us whether there is anything to hear (it
   * is sandboxed and cross-origin), so the page reports it down to here rather
   * than this component guessing.
   */
  soundMuted?: boolean;
  /** Toggle the application's sound. Required for the sound entry to appear. */
  onToggleSound?: () => void;
  className?: string;
}

/**
 * Settings cog for an installed application: a small popover holding the actions that
 * are not part of reading the app's Info panel. Mounted bottom-right of the application
 * page, opposite the Info panel in the top-right corner.
 *
 * <p>Today it holds a single entry, "Create an editable copy", which used to sit inline
 * in the Info tab. It is a rarely-used, one-shot action, so it reads better behind a cog
 * than as a permanent block above the app description. The same copy can also be
 * requested at install time from the acquire modal.
 */
export function ApplicationSettingsMenu({
  publicationId,
  remote = false,
  canCreateEditableCopy = false,
  soundMuted,
  onToggleSound,
  className,
}: ApplicationSettingsMenuProps) {
  const t = useTranslations('marketplace');
  // Sound labels live in the applications namespace, shared with the app cards -
  // one source of truth rather than the same sentence translated twice.
  const tSound = useTranslations('applications');
  const [open, setOpen] = useState(false);

  const [editableCopy, setEditableCopy] = useState<{
    status: 'idle' | 'creating' | 'done' | 'error';
    workflowId?: string;
    created?: boolean;
  }>({ status: 'idle' });

  const handleCreateEditableCopy = useCallback(async () => {
    setEditableCopy({ status: 'creating' });
    try {
      const result = await publicationService.createEditableWorkflowCopy(publicationId, remote);
      setEditableCopy({ status: 'done', workflowId: result.workflowId, created: result.created });
    } catch (err: any) {
      // A workflow-quota refusal (409 PLAN_RESOURCE_LIMIT_EXCEEDED) already raises the
      // GLOBAL plan-limit toast with its upgrade call to action, so this component stays
      // quiet and just resets - saying it twice, once vaguely, is the house anti-pattern.
      const limitReached = err?.status === 409 && err?.code === 'PLAN_RESOURCE_LIMIT_EXCEEDED';
      setEditableCopy({ status: limitReached ? 'idle' : 'error' });
    }
  }, [publicationId, remote]);

  // The application has audio, so the menu has a sound entry to offer.
  const canToggleSound = soundMuted !== undefined && !!onToggleSound;

  // Nothing to put in the menu - render no cog rather than an empty popover. Note
  // this is now a DISJUNCTION: an app with sound but no editable copy still gets a
  // cog, because that is the only place its sound can be turned on.
  if (!canCreateEditableCopy && !canToggleSound) return null;

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          data-testid="application-settings-trigger"
          aria-label={t('settingsMenu.label')}
          title={t('settingsMenu.label')}
          className={cn(
            'relative h-8 w-8 p-0 rounded-xl flex items-center justify-center',
            'backdrop-blur-sm transition-all duration-200',
            'bg-white/40 dark:bg-gray-800/40 text-gray-400 dark:text-gray-500',
            'hover:bg-white/90 dark:hover:bg-gray-800/90 hover:text-gray-700 dark:hover:text-gray-200',
            'opacity-80 hover:opacity-100',
            className
          )}
        >
          <Settings className="h-3.5 w-3.5" />
        </button>
      </PopoverTrigger>
      <PopoverContent
        // Anchored bottom-right of the application page, so the menu opens UPWARD.
        side="top"
        align="end"
        sideOffset={6}
        className="w-[264px] p-1.5 bg-theme-primary rounded-xl border border-gray-300/70 dark:border-gray-600/70 z-[100000]"
      >
        {/* Sound sits FIRST: it is the reversible, per-visit control, while the
            editable copy is a one-shot action that leaves this page. It also
            stays reachable in the "copy created" state below, which replaces the
            copy entry entirely. */}
        {canToggleSound && (
          <div className={canCreateEditableCopy ? 'pb-1 mb-1 border-b border-gray-200 dark:border-gray-700' : ''}>
            <button
              type="button"
              data-testid="application-settings-sound"
              aria-pressed={!soundMuted}
              onClick={onToggleSound}
              className="w-full flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-sm text-theme-primary transition-colors hover:bg-gray-100 dark:hover:bg-gray-700"
            >
              {soundMuted
                ? <VolumeX className="h-3.5 w-3.5 shrink-0" />
                : <Volume2 className="h-3.5 w-3.5 shrink-0" />}
              <span className="text-left">
                {soundMuted ? tSound('unmuteSound') : tSound('muteSound')}
              </span>
            </button>
          </div>
        )}
        {!canCreateEditableCopy ? null : editableCopy.status === 'done' && editableCopy.workflowId ? (
          <div className="px-2.5 py-2 space-y-1">
            <p className="text-sm text-theme-primary leading-snug">
              {editableCopy.created ? t('editableCopy.created') : t('editableCopy.existing')}
            </p>
            {/* Locale-aware Link (house style): a bare href drops the /{locale}
                prefix and forces a full reload of the app shell. */}
            <Link
              href={`/app/workflow/${editableCopy.workflowId}`}
              className="inline-flex items-center gap-1.5 text-sm font-medium text-[var(--accent-primary)] hover:underline"
              onClick={() => setOpen(false)}
            >
              <Workflow className="h-3.5 w-3.5" aria-hidden="true" />
              {t('editableCopy.open')}
            </Link>
          </div>
        ) : (
          <div className="space-y-0.5">
            <button
              type="button"
              data-testid="application-settings-editable-copy"
              disabled={editableCopy.status === 'creating'}
              onClick={handleCreateEditableCopy}
              className="w-full flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-sm text-theme-primary transition-colors hover:bg-gray-100 dark:hover:bg-gray-700 disabled:opacity-60"
            >
              {editableCopy.status === 'creating' ? (
                <>
                  <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin" />
                  <span className="text-left">{t('editableCopy.creating')}</span>
                </>
              ) : (
                <>
                  <Workflow className="h-3.5 w-3.5 shrink-0" />
                  <span className="text-left">{t('editableCopy.button')}</span>
                </>
              )}
            </button>
            <p className="px-2.5 pb-1 text-xs text-theme-secondary leading-snug">
              {editableCopy.status === 'error'
                ? t('editableCopy.error')
                : t('editableCopy.description')}
            </p>
          </div>
        )}
      </PopoverContent>
    </Popover>
  );
}

export default ApplicationSettingsMenu;
