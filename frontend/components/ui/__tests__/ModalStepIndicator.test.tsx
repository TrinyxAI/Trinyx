/**
 * @vitest-environment jsdom
 *
 * The multi-step modal header existed as six character-for-character copies
 * (CreateAgent, CreateDataSource, CreateInterface, AddColumn, ProjectMultiStep,
 * ShareWorkflow) which had already drifted in what they let you click. These
 * tests pin the two things the extraction was for:
 *
 *  - the steps are SQUARE, on the Button rung, because a step is a clickable
 *    control sitting in a dialog full of square buttons;
 *  - the "can I jump to this step" rule is a PARAMETER, not something each copy
 *    re-decides.
 *
 * The shape assertions used to pin `rounded-2xl`, "one rung above a Button". The
 * step is 32px tall (`py-1.5` around a `text-sm` line), so 16px of corner was
 * exactly half its height: a capsule. The class had changed, the pixels had not.
 * Hence the rule below is stated in terms of the Button, and the 32px arithmetic
 * is asserted rather than trusted.
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { FileText, Play, Globe } from 'lucide-react';
import { ModalStepIndicator } from '@/components/ui/ModalStepIndicator';
import { buttonVariants } from '@/components/ui/button';

const STEPS = [
  { number: 1, icon: FileText, label: 'Information' },
  { number: 2, icon: Play, label: 'Showcase' },
  { number: 3, icon: Globe, label: 'Visibility' },
];

const stepButtons = () => screen.getAllByRole('button');

describe('ModalStepIndicator - shape', () => {
  it('renders one button per step, and one connector between each pair', () => {
    const { container } = render(<ModalStepIndicator steps={STEPS} currentStep={2} />);
    expect(stepButtons()).toHaveLength(3);
    // The connectors are the only non-button children of the row.
    expect(container.querySelectorAll('div.w-8.h-0\\.5')).toHaveLength(2);
  });

  it('every step is square, not the capsule it used to be', () => {
    render(<ModalStepIndicator steps={STEPS} currentStep={2} />);
    for (const b of stepButtons()) {
      expect(b.className).not.toContain('rounded-full');
      expect(b.className).toContain('rounded-xl');
    }
  });

  it('a step carries the SAME corner as a Button, because that is what it is', () => {
    render(<ModalStepIndicator steps={STEPS} currentStep={1} />);
    // Read off buttonVariants rather than hardcoded: the day the Button rung
    // moves, the steps must move with it instead of silently drifting.
    const buttonRadius = buttonVariants().split(/\s+/).find((c) => c.startsWith('rounded'));
    const stepRadius = stepButtons()[0].className.split(/\s+/).filter((c) => c.startsWith('rounded'));

    expect(buttonRadius).toBeDefined();
    expect(stepRadius).toEqual([buttonRadius]);
  });

  it('never takes a corner that reaches half its own height, which redraws the capsule', () => {
    // The step is py-1.5 around a text-sm line: 32px. `rounded-2xl` is 16px,
    // exactly half, so the previous "square" class still rendered a pill. This
    // is the arithmetic that made the earlier fix a no-op on screen.
    const px: Record<string, number> = {
      'rounded-md': 6, 'rounded-lg': 8, 'rounded-xl': 12, 'rounded-2xl': 16, 'rounded-3xl': 24,
    };
    const STEP_HEIGHT_PX = 32;

    render(<ModalStepIndicator steps={STEPS} currentStep={1} />);
    const radius = stepButtons()[0].className.split(/\s+/).find((c) => c.startsWith('rounded'));

    expect(radius).toBeDefined();
    expect(px[radius as string]).toBeDefined();
    expect(px[radius as string] / STEP_HEIGHT_PX).toBeLessThan(0.5);
  });

  it('the connector is not a capsule either', () => {
    const { container } = render(<ModalStepIndicator steps={STEPS} currentStep={2} />);
    for (const c of Array.from(container.querySelectorAll('div.w-8'))) {
      expect(c.className).not.toContain('rounded-full');
    }
  });
});

describe('ModalStepIndicator - state', () => {
  it('marks the current step for assistive tech, not only with colour', () => {
    render(<ModalStepIndicator steps={STEPS} currentStep={2} />);
    const [first, current, last] = stepButtons();
    expect(current.getAttribute('aria-current')).toBe('step');
    expect(first.getAttribute('aria-current')).toBeNull();
    expect(last.getAttribute('aria-current')).toBeNull();
  });

  it('shows the accent fill on the current step, the done tint behind it, the idle fill ahead', () => {
    render(<ModalStepIndicator steps={STEPS} currentStep={2} />);
    const [done, current, upcoming] = stepButtons();
    expect(done.className).toContain('bg-emerald-500/20');
    expect(current.className).toContain('bg-[var(--accent-primary)]');
    expect(upcoming.className).toContain('bg-theme-tertiary');
  });

  it('swaps a completed step icon for a check', () => {
    const { container } = render(<ModalStepIndicator steps={STEPS} currentStep={3} />);
    // lucide renders the icon name as a class on the <svg>.
    expect(container.querySelectorAll('svg.lucide-check')).toHaveLength(2);
  });
});

describe('ModalStepIndicator - which steps can be jumped to', () => {
  it('lets any step be clicked by default, which is what the reversible wizards do', () => {
    const onStepClick = vi.fn();
    render(<ModalStepIndicator steps={STEPS} currentStep={1} onStepClick={onStepClick} />);
    const [, , third] = stepButtons();
    expect((third as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(third);
    expect(onStepClick).toHaveBeenCalledWith(3);
  });

  it('refuses the steps ahead when the caller says the wizard builds forward', () => {
    const onStepClick = vi.fn();
    render(
      <ModalStepIndicator
        steps={STEPS}
        currentStep={2}
        onStepClick={onStepClick}
        isStepEnabled={(step) => step <= 2}
      />,
    );
    const [first, current, ahead] = stepButtons();
    expect((first as HTMLButtonElement).disabled).toBe(false);
    expect((current as HTMLButtonElement).disabled).toBe(false);
    expect((ahead as HTMLButtonElement).disabled).toBe(true);
    expect(ahead.className).toContain('cursor-not-allowed');

    fireEvent.click(ahead);
    expect(onStepClick).not.toHaveBeenCalled();

    fireEvent.click(first);
    expect(onStepClick).toHaveBeenCalledWith(1);
  });

  it('does not offer a hover surface on a step that cannot be reached', () => {
    render(
      <ModalStepIndicator steps={STEPS} currentStep={1} isStepEnabled={(step) => step <= 1} />,
    );
    const ahead = stepButtons()[2];
    expect(ahead.className).not.toContain('hover:bg-theme-secondary');
    expect(ahead.className).not.toContain('cursor-pointer');
  });

  it('renders exactly the steps it is given, so a two-step modal shows two', () => {
    render(<ModalStepIndicator steps={STEPS.slice(0, 2)} currentStep={1} />);
    expect(stepButtons()).toHaveLength(2);
  });
});
