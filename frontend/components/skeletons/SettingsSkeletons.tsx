'use client';

import React from 'react';

import { cn } from '@/lib/utils';

/**
 * Generic skeleton box with animation.
 *
 * `cn` and not a template string: the base carries `rounded`, so a caller asking
 * for another rung used to ship BOTH classes and let the stylesheet order decide
 * which one won. tailwind-merge drops the base, so the caller's radius is the
 * one that renders - which is what the shapes below are chosen against.
 */
function SkeletonBox({ className }: { className?: string }) {
  return (
    <div className={cn('bg-theme-tertiary rounded animate-pulse', className)} />
  );
}

/**
 * Skeleton for settings page header (icon + title + subtitle)
 */
export function SettingsHeaderSkeleton() {
  return (
    <div className="flex items-center gap-3">
      <SkeletonBox className="w-10 h-10 rounded-xl" />
      <div className="space-y-2">
        <SkeletonBox className="h-5 w-32" />
        <SkeletonBox className="h-4 w-48" />
      </div>
    </div>
  );
}

/**
 * Skeleton for a full settings page
 */
export function SettingsPageSkeleton() {
  return (
    <div className="space-y-8">
      <SettingsHeaderSkeleton />
      <div className="space-y-4">
        <SkeletonBox className="h-10 w-full rounded-xl" />
        <SkeletonBox className="h-64 w-full rounded-xl" />
      </div>
    </div>
  );
}

/**
 * Skeleton for credentials list with filters and table
 */
export function CredentialsListSkeleton() {
  return (
    <div className="space-y-4">
      {/* Filters skeleton */}
      <div className="flex flex-col gap-3 md:flex-row md:items-center">
        <SkeletonBox className="h-10 flex-1 rounded-xl" />
        <SkeletonBox className="h-10 w-full md:w-[180px] rounded-xl" />
      </div>

      {/* Table skeleton */}
      <div className="w-full overflow-x-auto border border-theme rounded-xl">
        <table className="w-full text-sm">
          <thead className="bg-theme-tertiary border-b border-theme">
            <tr>
              <th className="px-3 py-3 w-12"><SkeletonBox className="h-4 w-4 mx-auto" /></th>
              <th className="px-4 py-3"><SkeletonBox className="h-4 w-16" /></th>
              <th className="px-4 py-3"><SkeletonBox className="h-4 w-12" /></th>
              <th className="px-4 py-3"><SkeletonBox className="h-4 w-12" /></th>
              <th className="px-4 py-3"><SkeletonBox className="h-4 w-20" /></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-theme">
            {[1, 2, 3].map((i) => (
              <tr key={i}>
                <td className="px-3 py-3 w-12">
                  <SkeletonBox className="h-4 w-4 mx-auto" />
                </td>
                <td className="px-4 py-3">
                  <div className="flex items-center gap-3">
                    <SkeletonBox className="h-8 w-8 rounded" />
                    <div className="space-y-1">
                      <SkeletonBox className="h-4 w-24" />
                      <SkeletonBox className="h-3 w-32" />
                    </div>
                  </div>
                </td>
                <td className="px-4 py-3">
                  {/* The credential TYPE chip. The real one is
                      `text-xs px-2 py-1 rounded-md`, which is 24px tall - the same
                      h-6 as here, so the corner has to be the label rung too.
                      `rounded-2xl` is 16px on those 24px: a capsule, i.e. a shape
                      the row never actually shows. */}
                  <SkeletonBox className="h-6 w-16 rounded-md" />
                </td>
                <td className="px-4 py-3">
                  <SkeletonBox className="h-4 w-4 mx-auto rounded-md" />
                </td>
                <td className="px-4 py-3">
                  <SkeletonBox className="h-4 w-24" />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/**
 * Skeleton for tabs navigation.
 *
 * Mirrors the settings overview tab bar it stands in for: a `rounded-2xl`
 * surface holding `h-9` `rounded-xl` controls. It was a rung low on both, so the
 * bar changed shape under the reader the moment the page resolved.
 */
export function TabsSkeleton({ tabCount = 3 }: { tabCount?: number }) {
  return (
    <div className="flex gap-2 p-1 bg-theme-tertiary rounded-2xl">
      {Array.from({ length: tabCount }).map((_, i) => (
        <SkeletonBox key={i} className="h-9 flex-1 rounded-xl" />
      ))}
    </div>
  );
}

/**
 * Skeleton for overview page with tabs and content
 */
export function OverviewPageSkeleton() {
  return (
    <div className="space-y-8">
      <SettingsHeaderSkeleton />

      {/* Tabs skeleton */}
      <div className="flex justify-center">
        <div className="w-full max-w-md">
          <TabsSkeleton tabCount={4} />
        </div>
      </div>

      {/* Content skeleton */}
      <div className="space-y-6">
        <SkeletonBox className="h-48 w-full rounded-xl" />
        <SkeletonBox className="h-32 w-full rounded-xl" />
      </div>
    </div>
  );
}

/**
 * Skeleton for MCP/integrations list
 */
export function IntegrationsListSkeleton() {
  return (
    <div className="space-y-4">
      {/* Search skeleton */}
      <SkeletonBox className="h-10 w-full rounded-xl" />

      {/* Grid skeleton */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {[1, 2, 3, 4, 5, 6].map((i) => (
          <div key={i} className="border border-theme rounded-xl p-4 space-y-3">
            <div className="flex items-center gap-3">
              <SkeletonBox className="h-10 w-10 rounded-lg" />
              <div className="flex-1 space-y-1">
                <SkeletonBox className="h-4 w-24" />
                <SkeletonBox className="h-3 w-32" />
              </div>
            </div>
            <SkeletonBox className="h-8 w-full rounded-lg" />
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * Skeleton for sidebar
 */
export function SidebarSkeleton() {
  return (
    <div className="w-64 h-screen bg-theme-secondary border-r border-theme">
      <div className="p-4 space-y-4">
        {/* Logo area */}
        <SkeletonBox className="h-8 w-32" />

        {/* Nav items */}
        <div className="space-y-2 pt-4">
          {[1, 2, 3, 4, 5].map((i) => (
            <SkeletonBox key={i} className="h-10 w-full rounded-lg" />
          ))}
        </div>
      </div>
    </div>
  );
}

export default {
  SettingsHeaderSkeleton,
  SettingsPageSkeleton,
  CredentialsListSkeleton,
  TabsSkeleton,
  OverviewPageSkeleton,
  IntegrationsListSkeleton,
  SidebarSkeleton,
};
