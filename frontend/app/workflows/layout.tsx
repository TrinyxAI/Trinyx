import React from 'react';
import { NextIntlClientProvider } from 'next-intl';
import { getMessages, setRequestLocale } from 'next-intl/server';
import { resolveRequestLocale } from '@/i18n/resolveRequestLocale';
import CeCloudCreditModal from '@/components/billing/CeCloudCreditModal';
import ModelNotManagedModal from '@/components/billing/ModelNotManagedModal';
import AgentErrorModal from '@/components/billing/AgentErrorModal';
import AccountRestoreModal from '@/components/auth/AccountRestoreModal';
import { WorkflowLayoutDirectionProvider } from '@/contexts/WorkflowLayoutDirectionContext';

export default async function WorkflowsLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const locale = await resolveRequestLocale();
  setRequestLocale(locale);
  const messages = await getMessages();

  return (
    <NextIntlClientProvider messages={messages} locale={locale}>
      {/* The standalone builder lives outside the /app layout, so it needs its own
          layout-direction provider - otherwise the canvas here always falls back to the
          safe-hook default and the reading-direction preference silently does nothing. */}
      <WorkflowLayoutDirectionProvider>
        {children}
        {/* The standalone builder lives outside the /app layout, so the CE cloud-relay modals
            (no-op in Cloud, self-gated to CE) are mounted here too - otherwise a relay error
            during a builder test-run would dispatch its event with no listener. */}
        <CeCloudCreditModal />
        <ModelNotManagedModal />
        <AgentErrorModal />
        {/* Same reason: a deactivated person who opens a bookmarked builder URL gets every call
            refused here too, and without this listener the restore interstitial never appears,
            leaving them in an app where nothing loads and no path leads anywhere. */}
        <AccountRestoreModal />
      </WorkflowLayoutDirectionProvider>
    </NextIntlClientProvider>
  );
}
