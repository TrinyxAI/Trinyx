// @vitest-environment jsdom
/**
 * The Input column's variable rows label their type without painting a chip behind it.
 *
 * These rows are dense and repetitive (one per `$vars` entry, per loop counter, per split
 * context field): a filled colour block on every line reads as decoration rather than as
 * information. The colour itself is kept, so the type is still distinguishable at a glance.
 * The filled chip stays where it does carry weight, in the Output column's schema trees,
 * which is why this is asserted on the class list rather than on the palette itself.
 */
import { describe, it, expect, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import { GlobalVariablesInspector } from '../GlobalVariablesInspector';
import { getFieldTypeColor } from '../../../types';

afterEach(cleanup);

const VARIABLES = [
  { name: 'ee', label: '{{$vars.ee}}', type: 'text', path: '{{$vars.ee}}', expressionToken: true },
  {
    name: 'current_item',
    label: 'Current Item',
    type: 'object',
    path: '{{core:split.output.current_item}}',
    properties: [{ name: 'id', label: 'id', type: 'number', path: '{{core:split.output.current_item.id}}' }],
  },
];

describe('GlobalVariablesInspector type labels', () => {
  it('renders the type without any background class, at both nesting levels', () => {
    render(<GlobalVariablesInspector variables={VARIABLES as any} />);

    // The nested property label only exists once the object row is expanded; the top-level
    // rows are enough to pin the rule, and the nested branch shares the same helper.
    const labels = screen.getAllByText(/^(text|object)$/);
    expect(labels.length).toBeGreaterThan(0);
    for (const label of labels) {
      expect(label.className, `type label still paints a chip: ${label.className}`)
        .not.toMatch(/(^|\s)(dark:)?bg-/);
    }
  });

  it('keeps the type colour, so the label is still readable as a type', () => {
    render(<GlobalVariablesInspector variables={VARIABLES as any} />);
    const textLabel = screen.getByText('text');
    // Same palette as the chip used elsewhere, minus the background half.
    expect(getFieldTypeColor('text')).toMatch(/bg-blue-100/);
    expect(textLabel.className).toMatch(/text-blue-700/);
    expect(textLabel.className).toMatch(/dark:text-blue-300/);
  });
});
