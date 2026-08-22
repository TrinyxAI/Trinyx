'use client';

import { useCallback } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/providers/smart-providers';
import { TRINYX_APP_ORIGIN } from '@/lib/navigation/trinyxApp';

type Variant = 'primary' | 'secondary' | 'link';

interface SignInButtonProps {
  children: React.ReactNode;
  variant?: Variant;
  className?: string;
  returnTo?: string;
  /** Explicit app/auth origin for cross-host handoff. */
  baseUrl?: string;
}

export default function SignInButton({
  children,
  variant = 'primary',
  className = '',
  returnTo = '/app/chat',
  baseUrl,
}: SignInButtonProps) {
  const router = useRouter();
  const { isAuthenticated, isLoading, loginWithRedirect } = useAuth();

  const handleClick = useCallback(
    async (e: React.MouseEvent) => {
      e.preventDefault();

      // trinyx.fr and app.trinyx.fr are deployed as two separate frontend
      // containers. Marketing CTAs must therefore hand off to the app host
      // instead of trying to run the landing container's auth flow locally.
      const appOrigin = baseUrl ?? TRINYX_APP_ORIGIN;
      if (typeof window !== 'undefined' && window.location.origin !== appOrigin) {
        window.location.assign(`${appOrigin}${returnTo}`);
        return;
      }

      if (isLoading) return;
      if (isAuthenticated) {
        router.push(returnTo);
        return;
      }
      await loginWithRedirect({ appState: { returnTo } });
    },
    [baseUrl, isAuthenticated, isLoading, loginWithRedirect, returnTo, router]
  );

  const appOrigin = baseUrl ?? TRINYX_APP_ORIGIN;
  const href = `${appOrigin}${returnTo}`;

  const variantStyle: React.CSSProperties =
    variant === 'primary'
      ? { background: 'var(--accent-primary)', color: 'var(--accent-foreground)' }
      : variant === 'secondary'
        ? { border: '1px solid var(--border-color)', color: 'var(--text-primary)' }
        : { color: 'var(--text-secondary)' };

  return (
    <a
      href={href}
      onClick={handleClick}
      className={className}
      style={variantStyle}
      aria-busy={isLoading || undefined}
    >
      {children}
    </a>
  );
}
