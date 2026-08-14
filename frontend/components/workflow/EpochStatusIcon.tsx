'use client';

import { CheckCircle2, XCircle, CircleSlash } from 'lucide-react';

/**
 * The status badge of ONE epoch, sized to sit inside a dense epoch row.
 *
 * Compact by necessity: the epoch rows live in a popover that can be as narrow as
 * 240px (the embedded application side panel), where a text pill would push the
 * timestamps off. The word itself is carried by the row's tooltip / title, which the
 * callers build from the same status through `getRunStatusLabel`.
 *
 * The slot keeps its width for every status INCLUDING none, so a run transitioning
 * from running to completed never shifts the columns to its right.
 *
 * @param size follows the row's type scale: `sm` for a `text-sm` row (the run panel),
 *             `xs` for a `text-xs` one (the application toolbar dropdown).
 */
export function EpochStatusIcon({
  status,
  size = 'xs',
}: {
  status: string | null | undefined;
  size?: 'xs' | 'sm';
}) {
  const normalized = (status || '').toUpperCase();
  const box = size === 'sm' ? 'w-3.5 h-3.5' : 'w-3 h-3';
  const icon = size === 'sm' ? 'h-3.5 w-3.5' : 'h-3 w-3';

  return (
    <span className={`${box} shrink-0 inline-flex items-center justify-center`} aria-hidden="true">
      {normalized === 'RUNNING' ? (
        <span className="relative flex h-1.5 w-1.5">
          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75" />
          <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-blue-500" />
        </span>
      ) : normalized === 'COMPLETED' ? (
        <CheckCircle2 className={`${icon} text-emerald-500 dark:text-emerald-400`} />
      ) : normalized === 'FAILED' ? (
        <XCircle className={`${icon} text-red-500 dark:text-red-400`} />
      ) : normalized === 'CANCELLED' || normalized === 'STOPPED' || normalized === 'TIMEOUT' ? (
        <CircleSlash className={`${icon} text-gray-400 dark:text-gray-500`} />
      ) : normalized ? (
        // Anything this component has no branch for: a hollow dot, not a verdict. The
        // fallback matters - an unknown status would otherwise leave the slot blank,
        // indistinguishable from "no status", while the tooltip beside it names one.
        <span className="h-1.5 w-1.5 rounded-full border border-gray-400 dark:border-gray-500" />
      ) : null}
    </span>
  );
}
