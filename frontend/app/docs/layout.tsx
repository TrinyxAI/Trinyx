import type { ReactNode } from 'react';
import type { Metadata } from 'next';
import { LandingShell } from '@/components/landing/LandingShell';
import { docsStyles } from './_components/docsStyles';
import { DocsNav } from './_components/DocsNav';
import { DocsMobileNav } from './_components/DocsMobileNav';
import { DocsToc } from './_components/DocsToc';
import { DocsPrevNext } from './_components/DocsPrevNext';
import { DocsThemeToggle } from './_components/DocsThemeToggle';

// Docs shell. Reuses the public `LandingShell` chrome (header, footer, light-by-
// default decoupled theme; the toggle below persists a docs-only choice under
// `docs-theme`) and injects the docs CSS via `extraStyles`, then lays out a
// sidebar / content / TOC grid inside it. English-only: this whole tree lives
// outside `app/[locale]`, so nothing here may call next-intl hooks.
export const metadata: Metadata = {
  title: {
    template: '%s · Trinyx Docs',
    default: 'Documentation · Trinyx',
  },
  description: 'Guides and reference for Trinyx - the AI automation platform.',
};

export default function DocsLayout({ children }: { children: ReactNode }) {
  // Documentation shares the public Trinyx origin, so chrome links stay relative.
  return (
    <LandingShell
      extraStyles={docsStyles}
      headerExtra={<DocsThemeToggle />}
      themeStorageKey="docs-theme"
      themeRespectStored
    >
      <div className="docs-layout">
        <aside className="docs-sidebar">
          <DocsNav />
        </aside>
        <div className="docs-content">
          <DocsMobileNav />
          {children}
          <DocsPrevNext />
        </div>
        <aside className="docs-toc">
          <DocsToc />
        </aside>
      </div>
    </LandingShell>
  );
}
