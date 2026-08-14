'use client';

import { useEffect } from 'react';
import { useAuth } from '@/lib/providers/smart-providers';

interface AuthenticatedViewProps {
  children: React.ReactNode;
  maxWidth?: string;
  overflow?: boolean;
}

export function AuthenticatedView({ children, maxWidth = 'max-w-6xl', overflow }: AuthenticatedViewProps) {
  const { isLoading, isAuthenticated, loginWithRedirect } = useAuth();

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      loginWithRedirect({
        appState: { returnTo: window.location.pathname }
      });
    }
  }, [isLoading, isAuthenticated, loginWithRedirect]);

  if (isLoading || !isAuthenticated) {
    return null;
  }

  // A phone has ~48px less width than the desktop gutters assume, and on these
  // pages that difference is the whole margin between a header row that fits and
  // one that pushes the page sideways. The desktop gutter is unchanged.
  if (overflow) {
    return (
      <div className="flex-1 min-h-0 overflow-hidden flex flex-col px-4 sm:px-6 pt-4 sm:pt-6 pb-2">
        <div className={`${maxWidth} mx-auto w-full flex-1 min-h-0 flex flex-col`}>
          {children}
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 overflow-y-auto">
      {/* Per axis on purpose: a `sm:p-6` shorthand sits in a media query, which
          is emitted after every unprefixed utility, and would quietly take the
          bottom padding back down from `pb-12` to 24px on desktop. */}
      <div className="min-h-full w-full px-4 pt-4 pb-12 sm:px-6 sm:pt-6">
        <div className={`${maxWidth} mx-auto space-y-6 w-full`}>
          {children}
        </div>
      </div>
    </div>
  );
}
