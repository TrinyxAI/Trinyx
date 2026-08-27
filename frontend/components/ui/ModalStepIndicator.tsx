'use client';

import React from 'react';
import { Check } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * The step header of a multi-step modal: one clickable pill per step, joined by a
 * short rule that fills in as the user advances.
 *
 * This existed SIX times, copy-pasted character for character, in
 * CreateAgentModal, CreateDataSourceModal, CreateInterfaceModal, AddColumnModal,
 * ProjectMultiStepModal and ShareWorkflowModal. The copies had already drifted:
 * three of them let you jump to any step, three disabled the steps ahead, and one
 * used a different hover tint. Restyling them meant six identical edits, which is
 * how they drifted in the first place.
 *
 * Shape: the steps take the Button rung, `rounded-xl`. They were `rounded-full`
 * capsules, then `rounded-2xl` on the theory that a step is a stage rather than a
 * control and should read as a small surface. That theory does not survive the
 * arithmetic: the step is `py-1.5` around a `text-sm` line, so 32px tall, and
 * `rounded-2xl` is 16px of corner, EXACTLY half the height. The result was a
 * perfect capsule, so the move off `rounded-full` changed nothing on screen. A
 * step is clickable, it sits inside a dialog full of square buttons, and it
 * should look like one: same rung as `buttonVariants`.
 */

export interface ModalStep {
  /** 1-based step number; matches the modal's own step state. */
  number: number;
  icon: React.ComponentType<{ className?: string }>;
  label: string;
}

export interface ModalStepIndicatorProps {
  steps: ModalStep[];
  currentStep: number;
  onStepClick?: (step: number) => void;
  /**
   * Whether a step can be jumped to. Defaults to "any step", which is what the
   * modals with cheap, reversible steps do; the ones that build state forward
   * (project, publish, agent) pass a predicate that refuses the steps ahead.
   */
  isStepEnabled?: (step: number) => boolean;
  className?: string;
}

export function ModalStepIndicator({
  steps,
  currentStep,
  onStepClick,
  isStepEnabled,
  className,
}: ModalStepIndicatorProps) {
  return (
    <div className={cn('flex items-center justify-center gap-2 mb-6', className)}>
      {steps.map((step, index) => {
        const isActive = step.number === currentStep;
        const isCompleted = step.number < currentStep;
        const enabled = isStepEnabled ? isStepEnabled(step.number) : true;

        const Icon = step.icon;

        return (
          <React.Fragment key={step.number}>
            <button
              type="button"
              // The step's number, as a handle. The browser suites address steps
              // by it (`[data-step="2"][aria-current="step"]`), which is how they
              // say "the dialog is on step 2" without depending on a label that
              // every locale spells differently. The generation modal carried it
              // on its own copy of this header; folding that copy in here without
              // it took five checked-in tests down, so it belongs to the shared
              // component now.
              data-step={step.number}
              onClick={() => onStepClick?.(step.number)}
              disabled={!enabled}
              aria-current={isActive ? 'step' : undefined}
              // The label is hidden below `sm` (three to five of them do not fit
              // on a phone), which left the control as a bare icon with NO
              // accessible name at exactly the width where a pointer is least
              // precise. Naming it here holds at every width; the visible span
              // repeats the same words, so nothing is announced twice.
              aria-label={step.label}
              title={step.label}
              className={cn(
                'flex items-center gap-2 px-3 py-1.5 rounded-xl transition-colors',
                isActive
                  ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)]'
                  : isCompleted
                    ? 'bg-emerald-500/20 text-emerald-600 dark:text-emerald-400'
                    : 'bg-theme-tertiary text-theme-secondary',
                enabled
                  ? cn(
                      'cursor-pointer',
                      isCompleted ? 'hover:bg-emerald-500/30' : !isActive && 'hover:bg-theme-secondary',
                    )
                  : 'cursor-not-allowed',
              )}
            >
              {isCompleted ? <Check className="h-4 w-4" /> : <Icon className="h-4 w-4" />}
              <span className="text-sm font-medium hidden sm:inline">{step.label}</span>
            </button>
            {index < steps.length - 1 && (
              <div
                className={cn(
                  'w-8 h-0.5 rounded-sm',
                  step.number < currentStep ? 'bg-emerald-500' : 'bg-theme-tertiary',
                )}
              />
            )}
          </React.Fragment>
        );
      })}
    </div>
  );
}

export default ModalStepIndicator;
