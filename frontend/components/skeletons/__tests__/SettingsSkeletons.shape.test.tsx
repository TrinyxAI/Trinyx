// @vitest-environment jsdom
/**
 * A skeleton is a PROMISE about the shape that is coming. When its corner does
 * not match the element it stands in for, the page visibly re-shapes itself the
 * moment the data lands, which is the one thing a skeleton exists to avoid.
 *
 * Two boxes had drifted: the credential TYPE chip was `rounded-2xl` on an h-6
 * (24px) box - 16px of corner on 24px is a capsule, and the real chip is
 * `px-2 py-1 rounded-md` - and the tab bar sat a rung below the real one on both
 * the surface and its items.
 *
 * So this pins the two specific rungs against the shared primitives they must
 * follow, plus one invariant over EVERY box in the file: a radius that reaches
 * half the height redraws a capsule whatever the class is called.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';

import { badgeVariants } from '@/components/ui/badge';
import { buttonVariants } from '@/components/ui/button';
import {
  CredentialsListSkeleton,
  IntegrationsListSkeleton,
  OverviewPageSkeleton,
  SettingsHeaderSkeleton,
  SettingsPageSkeleton,
  SidebarSkeleton,
  TabsSkeleton,
} from '../SettingsSkeletons';

/** Tailwind height rung -> rendered px. */
const HEIGHT_PX: Record<string, number> = {
  'h-3': 12, 'h-4': 16, 'h-5': 20, 'h-6': 24, 'h-8': 32, 'h-9': 36,
  'h-10': 40, 'h-16': 64, 'h-32': 128, 'h-48': 192, 'h-64': 256,
};

/** Radius rung -> rendered px. `rounded` alone is the 4px default. */
const RADIUS_PX: Record<string, number> = {
  rounded: 4, 'rounded-sm': 2, 'rounded-md': 6, 'rounded-lg': 8,
  'rounded-xl': 12, 'rounded-2xl': 16, 'rounded-3xl': 24,
};

const ALL_SKELETONS: Array<[string, React.ReactElement]> = [
  ['SettingsHeaderSkeleton', <SettingsHeaderSkeleton key="h" />],
  ['SettingsPageSkeleton', <SettingsPageSkeleton key="p" />],
  ['CredentialsListSkeleton', <CredentialsListSkeleton key="c" />],
  ['TabsSkeleton', <TabsSkeleton key="t" />],
  ['OverviewPageSkeleton', <OverviewPageSkeleton key="o" />],
  ['IntegrationsListSkeleton', <IntegrationsListSkeleton key="i" />],
  ['SidebarSkeleton', <SidebarSkeleton key="s" />],
];

function classesOf(el: Element): string[] {
  return el.className.split(/\s+/).filter(Boolean);
}

function radiusOf(el: Element): string | undefined {
  return classesOf(el).find((c) => c in RADIUS_PX);
}

afterEach(cleanup);

describe('settings skeletons - the corner matches what is coming', () => {
  it('never draws a capsule, in any skeleton of the file', () => {
    for (const [name, ui] of ALL_SKELETONS) {
      const { container } = render(ui);
      for (const el of Array.from(container.querySelectorAll('*'))) {
        expect(classesOf(el), name).not.toContain('rounded-full');
      }
      cleanup();
    }
  });

  it('never gives a box a radius that reaches half its own height', () => {
    // The failure this catches is silent: the class says "square", the pixels
    // draw a pill. It is the same arithmetic that hid the modal step capsule.
    for (const [name, ui] of ALL_SKELETONS) {
      const { container } = render(ui);
      for (const el of Array.from(container.querySelectorAll('*'))) {
        const classes = classesOf(el);
        const height = classes.find((c) => c in HEIGHT_PX);
        const radius = radiusOf(el);
        if (!height || !radius) continue;
        expect(RADIUS_PX[radius] / HEIGHT_PX[height], `${name}: ${height} ${radius}`).toBeLessThan(0.5);
      }
      cleanup();
    }
  });

  it('draws the credential type chip at the Badge rung, like the real chip', () => {
    // The real cell renders `text-xs px-2 py-1 rounded-md`, a label. Reading the
    // rung off badgeVariants keeps the two together if the label rung ever moves.
    const badgeRadius = badgeVariants().split(/\s+/).find((c) => c in RADIUS_PX);
    const { container } = render(<CredentialsListSkeleton />);

    // The only h-6 box in this skeleton is that chip.
    const chips = Array.from(container.querySelectorAll('*')).filter((el) => classesOf(el).includes('h-6'));

    expect(badgeRadius).toBeDefined();
    expect(chips).not.toHaveLength(0);
    for (const chip of chips) expect(radiusOf(chip)).toBe(badgeRadius);
  });

  it('draws the tab bar as a surface holding controls, like the page it replaces', () => {
    // Overview renders a `rounded-2xl` bar of `h-9 rounded-xl` triggers.
    const buttonRadius = buttonVariants().split(/\s+/).find((c) => c in RADIUS_PX);
    const { container } = render(<TabsSkeleton tabCount={3} />);
    const bar = container.firstElementChild as HTMLElement;
    const items = Array.from(bar.children);

    expect(radiusOf(bar)).toBe('rounded-2xl');
    expect(items).toHaveLength(3);
    for (const item of items) expect(radiusOf(item)).toBe(buttonRadius);
  });

  it('keeps the tab bar one rung above the items it holds', () => {
    // Stated as a relation, not two literals: a surface is above its controls.
    const rungs = ['rounded', 'rounded-sm', 'rounded-md', 'rounded-lg', 'rounded-xl', 'rounded-2xl', 'rounded-3xl'];
    const { container } = render(<TabsSkeleton tabCount={2} />);
    const bar = container.firstElementChild as HTMLElement;

    const barRung = rungs.indexOf(radiusOf(bar) as string);
    const itemRung = rungs.indexOf(radiusOf(bar.children[0]) as string);

    expect(itemRung).toBeGreaterThan(-1);
    expect(barRung).toBeGreaterThan(itemRung);
  });
});
