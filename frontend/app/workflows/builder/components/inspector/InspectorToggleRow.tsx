'use client';

/**
 * The inspector's toggle row: label on the left, switch on the right, one line of
 * help underneath.
 *
 * Extracted from NodeSettingsSection, where it was module-private, when the mcp
 * account selector needed the same control. Copying the markup instead would have
 * left two definitions of what a toggle in this panel looks like, and they drift:
 * the first restyle of one of them is the moment the panel stops looking like one
 * panel. The blockedReason branch is part of the shape and travels with it, so a
 * second caller that has to gate a toggle does not reinvent the tooltip either.
 */

import React from 'react';

import { Switch } from '@/components/ui/switch';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';

export interface InspectorToggleRowProps {
  label: string;
  /**
   * What the switch is announced as, when the visible label does not carry the
   * on/off meaning on its own. A boolean setting ("Continue on failure") reads
   * fine as its own label; a row that names a subject ("Account used by this
   * step") leaves a screen-reader user hearing "... switch, off" with no way to
   * know what "on" would do. Defaults to {@code label}.
   */
  ariaLabel?: string;
  help: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled: boolean;
  /** When set, the toggle is gated for this node type - shown as a tooltip. */
  blockedReason?: string;
  testId: string;
}

export function InspectorToggleRow({
  label,
  ariaLabel,
  help,
  checked,
  onChange,
  disabled,
  blockedReason,
  testId,
}: InspectorToggleRowProps) {
  const toggle = (
    <Switch
      checked={checked}
      onCheckedChange={onChange}
      disabled={disabled}
      aria-label={ariaLabel ?? label}
    />
  );

  return (
    <div className="flex flex-col gap-1.5" data-testid={testId}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium text-slate-500 dark:text-slate-400">{label}</span>
        {blockedReason ? (
          <TooltipProvider delayDuration={150}>
            <Tooltip>
              <TooltipTrigger asChild>
                {/* span wrapper: a disabled button does not fire pointer events */}
                <span tabIndex={0} data-testid={`${testId}-blocked`}>{toggle}</span>
              </TooltipTrigger>
              <TooltipContent side="left" className="max-w-72">
                {blockedReason}
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        ) : (
          toggle
        )}
      </div>
      <p className="text-sm text-slate-400 dark:text-slate-500">
        {blockedReason ?? help}
      </p>
    </div>
  );
}
