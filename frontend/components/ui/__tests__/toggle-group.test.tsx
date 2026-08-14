// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ToggleGroup } from '../toggle-group';

/**
 * The control is a radio group by nature: exactly one option is selected and
 * picking another replaces it. Announcing that needs two roles working together
 * (`radiogroup` around `radio`) plus a name for the group, and a `radio` with
 * no owning group is an ARIA violation an audit tool flags.
 *
 * <p>So the roles are opt-in, keyed on the caller supplying the name. That
 * decision is the subject here: a caller that names the group must get the full
 * semantics, and the roughly thirty callers that do not must render EXACTLY as
 * they did before, since none of them asked for anything.
 */
const OPTIONS = [
  { value: 'user', label: 'My credential' },
  { value: 'platform', label: 'Platform' },
];

afterEach(cleanup);

describe('ToggleGroup', () => {
  it('announces itself as a named radio group when a caller names it', () => {
    render(
      <ToggleGroup ariaLabel="Credential source" value="user" onValueChange={() => {}} options={OPTIONS} />,
    );

    const group = screen.getByRole('radiogroup', { name: 'Credential source' });
    expect(group).toBeInTheDocument();
    // Which one is in force is the whole meaning of the control on a screen
    // that decides who pays for a purchase.
    expect(screen.getByRole('radio', { name: 'My credential' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: 'Platform' })).toHaveAttribute('aria-checked', 'false');
  });

  it('leaves an unnamed group exactly as it was: no roles, no checked state', () => {
    // The other callers in the app pass no label. Emitting a radiogroup with no
    // name would announce an anonymous group, and emitting radios without the
    // group would be invalid ARIA. Both are worse than plain buttons.
    render(<ToggleGroup value="user" onValueChange={() => {}} options={OPTIONS} />);

    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument();
    expect(screen.queryAllByRole('radio')).toHaveLength(0);
    expect(screen.getByText('Platform').closest('button')).not.toHaveAttribute('aria-checked');
  });

  it('reports the value picked, named or not', () => {
    const onValueChange = vi.fn();
    render(
      <ToggleGroup ariaLabel="Credential source" value="user" onValueChange={onValueChange} options={OPTIONS} />,
    );

    fireEvent.click(screen.getByText('Platform'));

    expect(onValueChange).toHaveBeenCalledWith('platform');
  });

  it('does not report anything while disabled', () => {
    const onValueChange = vi.fn();
    render(
      <ToggleGroup
        ariaLabel="Credential source"
        value="user"
        onValueChange={onValueChange}
        options={OPTIONS}
        disabled
      />,
    );

    fireEvent.click(screen.getByText('Platform'));

    expect(onValueChange).not.toHaveBeenCalled();
  });
});
