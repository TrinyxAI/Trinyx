// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

import { InspectorToggleRow } from '../InspectorToggleRow';

/**
 * The row was module-private in NodeSettingsSection until the mcp account selector
 * needed the same control. These pin what both call sites now depend on, because a
 * change here is no longer local to one panel section.
 */
describe('InspectorToggleRow', () => {
  afterEach(cleanup);

  const base = {
    label: 'Continue on failure',
    help: 'Keep the run going when this node fails.',
    checked: false,
    onChange: vi.fn(),
    disabled: false,
    testId: 'node-settings-continue-on-failure',
  };

  it('renders a switch carrying the label, with the help line under it', () => {
    render(<InspectorToggleRow {...base} onChange={vi.fn()} />);

    const toggle = screen.getByRole('switch', { name: 'Continue on failure' });
    expect(toggle).toBeTruthy();
    expect(toggle.getAttribute('aria-checked')).toBe('false');
    expect(screen.getByText('Keep the run going when this node fails.')).toBeTruthy();
  });

  it('keeps the row testId on the row, which is how existing settings rows are found', () => {
    // The settings section addressed its rows by this id before the extraction; if
    // the id moved onto an inner element those call sites break silently.
    const { container } = render(<InspectorToggleRow {...base} onChange={vi.fn()} />);
    const row = container.querySelector('[data-testid="node-settings-continue-on-failure"]');
    expect(row).toBeTruthy();
    expect(row!.querySelector('[role="switch"]')).toBeTruthy();
  });

  it('reflects a checked value and reports the flipped one when clicked', () => {
    const onChange = vi.fn();
    render(<InspectorToggleRow {...base} checked onChange={onChange} />);

    const toggle = screen.getByRole('switch', { name: 'Continue on failure' });
    expect(toggle.getAttribute('aria-checked')).toBe('true');

    fireEvent.click(toggle);
    expect(onChange).toHaveBeenCalledWith(false);
  });

  it('does not report a change while disabled', () => {
    // Run mode renders the inspector read-only, so a click there must not mutate
    // the node behind the author's back.
    const onChange = vi.fn();
    render(<InspectorToggleRow {...base} disabled onChange={onChange} />);

    fireEvent.click(screen.getByRole('switch', { name: 'Continue on failure' }));
    expect(onChange).not.toHaveBeenCalled();
  });

  it('replaces the help line with blockedReason when the toggle is gated', () => {
    // A gated toggle that still printed the generic help would tell the author what
    // the setting does and never why it is unavailable for this node type.
    render(
      <InspectorToggleRow
        {...base}
        onChange={vi.fn()}
        disabled
        blockedReason="Not available on a trigger node."
      />,
    );

    expect(screen.getByText('Not available on a trigger node.')).toBeTruthy();
    expect(screen.queryByText('Keep the run going when this node fails.')).toBeNull();
    expect(screen.getByRole('switch', { name: 'Continue on failure' })).toBeTruthy();
  });

  it('wraps a gated switch in a focusable span, so the tooltip can be reached at all', () => {
    // A disabled button fires no pointer events, so the tooltip trigger has to sit on a
    // wrapper. Without it the reason renders in a tooltip nobody can open, and the only
    // sign of that is silence.
    const { container } = render(
      <InspectorToggleRow {...base} onChange={vi.fn()} disabled blockedReason="Not here." />,
    );

    const wrapper = container.querySelector(
      '[data-testid="node-settings-continue-on-failure-blocked"]',
    );
    expect(wrapper).toBeTruthy();
    expect(wrapper!.getAttribute('tabindex')).toBe('0');
    expect(wrapper!.querySelector('[role="switch"]')).toBeTruthy();
  });

  it('announces the switch by ariaLabel when the visible label names a subject', () => {
    // "Account used by this step, switch, off" tells a screen-reader user nothing about
    // what "on" would do. The visible label stays a heading; the switch gets its own.
    render(
      <InspectorToggleRow
        {...base}
        onChange={vi.fn()}
        label="Account used by this step"
        ariaLabel="Choose the account at run time"
      />,
    );

    expect(screen.getByRole('switch', { name: 'Choose the account at run time' })).toBeTruthy();
    expect(screen.getByText('Account used by this step')).toBeTruthy();
  });

  it('falls back to the label when no ariaLabel is given, leaving boolean rows unchanged', () => {
    render(<InspectorToggleRow {...base} onChange={vi.fn()} />);

    expect(screen.getByRole('switch', { name: 'Continue on failure' })).toBeTruthy();
  });
});
