'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AlertTriangle, RotateCcw, LogOut } from 'lucide-react';
import { useTranslations } from 'next-intl';

import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import LoadingSpinner from '@/components/LoadingSpinner';
import {
  ACCOUNT_INACTIVE_EVENT,
  clearBlockedCallLatch,
  hasBlockedCallBeenSeen,
} from '@/lib/api/api-client';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import type { AccountDeletionStatus } from '@/lib/api/services/user-api.service';
import { useAuth } from '@/lib/providers/smart-providers';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

/**
 * Interstitial for an account that asked to be deleted and then came back.
 *
 * Deactivation blocks every call at the gateway but leaves the Keycloak identity enabled,
 * so the person can still sign in during the grace period. Without this screen they land
 * in an app where nothing loads and no path leads anywhere: the deletion is only
 * cancellable through {@code POST /users/profile/restore}, which nothing was calling.
 *
 * Opened by the {@link ACCOUNT_INACTIVE_EVENT} that apiClient fires when the gateway
 * refuses a call for an inactive account, so it costs nothing for everyone else (no probe
 * on load). Dismissible like the other global modals: the app behind it is unusable, and
 * the next blocked call brings it straight back.
 */
export default function AccountRestoreModal() {
  const t = useTranslations('modals.accountRestore');
  const { logout } = useAuth();

  const [open, setOpen] = useState(false);
  const [status, setStatus] = useState<AccountDeletionStatus | null>(null);
  const [loading, setLoading] = useState(false);
  const [restoring, setRestoring] = useState(false);
  const [failed, setFailed] = useState(false);

  // Every blocked call fires the event, and a deactivated account blocks ALL of them, so the
  // event arrives in bursts. The guard is a ref, not the `open` state: a state updater must be
  // pure, and React may invoke it more than once per commit (it does under StrictMode), which
  // would fire the request twice for one burst.
  const statusRequestedRef = useRef(false);

  const openAndReadStatus = useCallback(() => {
    setOpen(true);
    if (statusRequestedRef.current) return;
    statusRequestedRef.current = true;
    setLoading(true);
    unifiedApiService
      .getAccountDeletionStatus()
      .then(setStatus)
      .catch(() => {
        // Release the guard: this screen is the only route to the restore endpoint, so a single
        // 502 must not strip the button for the rest of the page session with no way to retry.
        statusRequestedRef.current = false;
        setStatus(null);
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    // Read the latch as well as the event. The first blocked call on /app/* is issued while this
    // component is still unmounted behind FirstLoginGuard's spinner, so waiting for an event
    // alone can miss the only signal there is.
    if (hasBlockedCallBeenSeen()) openAndReadStatus();

    window.addEventListener(ACCOUNT_INACTIVE_EVENT, openAndReadStatus);
    return () => window.removeEventListener(ACCOUNT_INACTIVE_EVENT, openAndReadStatus);
  }, [openAndReadStatus]);

  // Dismissing clears a failed attempt, so the next blocked call does not reopen the dialog
  // still showing an error from an attempt the person may have made minutes ago.
  const handleOpenChange = useCallback((next: boolean) => {
    setOpen(next);
    if (!next) setFailed(false);
  }, []);

  const handleRestore = useCallback(async () => {
    setRestoring(true);
    setFailed(false);
    try {
      await unifiedApiService.restoreAccount();
      clearBlockedCallLatch();
      // Everything on screen was rendered from calls the gateway refused, so a reload is
      // what actually brings the app back. Reloading also re-reads the account state from
      // scratch rather than trusting this component's view of it.
      window.location.reload();
    } catch (error) {
      console.error('Account restore failed:', error);
      setRestoring(false);
      setFailed(true);
    }
  }, []);

  // `formatUtcDate`'s fallback is applied with `||`, so an empty-string fallback still yields "-".
  // Deciding here keeps a null or unparseable deletionAt from rendering as "deleted on -".
  const parsedDeletionAt = status?.deletionAt ? new Date(status.deletionAt) : null;
  const deletionDate =
    parsedDeletionAt && !Number.isNaN(parsedDeletionAt.getTime())
      ? formatUtcDate(status!.deletionAt)
      : '';
  const scheduled = status?.scheduledForDeletion === true && deletionDate !== '';

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-md">
        {/* DialogTitle/DialogDescription rather than a bare h2 + p: Radix derives the dialog's
            accessible name and description from them, and this screen is a blocked account's
            only way out, so it has to be announced. */}
        <DialogHeader>
          <div className="pr-8 text-center">
            <div className="w-12 h-12 sm:w-14 sm:h-14 bg-amber-100 dark:bg-amber-900/30 rounded-xl flex items-center justify-center mx-auto mb-3 sm:mb-4">
              <AlertTriangle className="h-6 w-6 sm:h-7 sm:w-7 text-amber-600 dark:text-amber-400" />
            </div>
            {/* Three states, not two. While the status is in flight nothing is known yet, and
                falling back to the "inactive account, contact support" copy meant the common case
                read as an unexplained lockout for as long as the request took. */}
            <DialogTitle className="text-lg sm:text-xl">
              {loading ? t('checkingTitle') : scheduled ? t('title') : t('blockedTitle')}
            </DialogTitle>
            <DialogDescription className="mt-1 text-sm text-theme-secondary">
              {loading
                ? t('checkingDescription')
                : scheduled
                  ? t('description', { date: deletionDate })
                  : t('blockedDescription')}
            </DialogDescription>
          </div>
        </DialogHeader>

        {loading ? (
          <div className="flex justify-center py-4">
            <LoadingSpinner size="sm" />
          </div>
        ) : (
          scheduled && (
            <div className="bg-theme-tertiary border border-theme rounded-xl p-4 text-left">
              <h3 className="text-sm font-semibold text-theme-primary mb-2">
                {t('whatHappens')}
              </h3>
              <div className="space-y-1 text-sm text-theme-secondary">
                <p>* {t('keepEverything')}</p>
                <p>* {t('untilThenNothingLost')}</p>
                <p>* {t('afterThatPermanent')}</p>
              </div>
            </div>
          )
        )}

        {failed && (
          <p className="text-sm text-red-600 dark:text-red-400 text-center">
            {t('restoreFailed')}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button
            variant="outline"
            size="sm"
            onClick={() => logout()}
            disabled={restoring}
          >
            <LogOut className="h-3.5 w-3.5 mr-1.5" />
            {t('signOut')}
          </Button>
          {/* The label stays through the busy state: swapping it for a bare spinner leaves the
              button with no accessible name, on the one control that unblocks the account. */}
          {scheduled && (
            <Button
              size="sm"
              onClick={handleRestore}
              disabled={restoring || loading}
              aria-busy={restoring}
            >
              {restoring ? (
                <LoadingSpinner size="xs" />
              ) : (
                <RotateCcw className="h-3.5 w-3.5 mr-1.5" />
              )}
              {t('reactivate')}
            </Button>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
