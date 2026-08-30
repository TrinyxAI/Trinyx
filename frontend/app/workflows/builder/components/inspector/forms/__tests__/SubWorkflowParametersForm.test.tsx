// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { SubWorkflowParametersForm } from '../SubWorkflowParametersForm';

// Surface the i18n key as its own value so we can assert labels/placeholders.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// The real editor is a contenteditable with a data-placeholder, which none of the
// value-based queries below can drive. Same stand-in the other form specs use.
vi.mock('@/components/ui/expression-editor', () => ({
  ExpressionEditor: ({ value, onChange, placeholder, readOnly }: any) => (
    <textarea
      placeholder={placeholder}
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value)}
      readOnly={readOnly}
    />
  ),
}));

afterEach(cleanup);

/** The expression plumbing every field-with-an-editor needs; inert here. */
const connectionProps = {
  connections: [],
  draggingFromHandle: null,
  hoveredTargetHandle: null,
  handleHandleClick: vi.fn(),
  handleHandleMouseDown: vi.fn(),
  handleHandleMouseUp: vi.fn(),
  handleSetHandleRef: vi.fn(),
};

function renderForm(data: any, onUpdate = vi.fn()) {
  const node = { id: 'core:publish_video', data } as any;
  render(
    <SubWorkflowParametersForm
      node={node}
      data={data}
      onUpdate={onUpdate}
      connectionProps={connectionProps}
      findUnknownVariables={() => []}
    />,
  );
  return onUpdate;
}

describe('SubWorkflowParametersForm', () => {
  it('renders a STRING inputMapping without crashing (regression: plan/MCP nodes store a SpEL string, the old array-map crashed the builder)', () => {
    // Pre-fix this threw "f.map is not a function" and unmounted the whole canvas.
    expect(() =>
      renderForm({ subWorkflowId: 'wf-1', subWorkflowInputMapping: '{{core:publish_input.output}}' })
    ).not.toThrow();
    const field = screen.getByDisplayValue('{{core:publish_input.output}}');
    expect(field).toBeTruthy();
  });

  it('coerces a legacy array-shaped inputMapping to an empty string instead of crashing', () => {
    expect(() =>
      renderForm({ subWorkflowId: 'wf-1', subWorkflowInputMapping: [{ id: 'a', key: 'k', value: 'v' }] as any })
    ).not.toThrow();
    // The expression field renders empty rather than trying to .map the array.
    const field = screen.getByPlaceholderText('inputMappingPlaceholder') as HTMLTextAreaElement;
    expect(field.value).toBe('');
  });

  it('edits inputMapping as a single expression string', () => {
    const onUpdate = renderForm({ subWorkflowId: 'wf-1', subWorkflowInputMapping: '' });
    fireEvent.change(screen.getByPlaceholderText('inputMappingPlaceholder'), {
      target: { value: '{{trigger.output.payload}}' },
    });
    expect(onUpdate).toHaveBeenCalledWith(
      expect.objectContaining({ subWorkflowInputMapping: '{{trigger.output.payload}}' })
    );
  });

  it('reads the timeout from subWorkflowTimeoutSeconds (the key the importer/export use)', () => {
    renderForm({ subWorkflowId: 'wf-1', subWorkflowTimeoutSeconds: 600 });
    expect(screen.getByDisplayValue('600')).toBeTruthy();
  });

  it('caps the timeout at the value the backend will actually honour', () => {
    // The node parks a pooled worker for this long, so the backend clamps it. Without the same
    // clamp here the field would show a number the run silently ignores.
    const onUpdate = renderForm({ subWorkflowId: 'wf-1', subWorkflowTimeoutSeconds: 300 });
    fireEvent.change(screen.getByPlaceholderText('timeoutPlaceholder'), { target: { value: '86400' } });
    expect(onUpdate).toHaveBeenCalledWith(
      expect.objectContaining({ subWorkflowTimeoutSeconds: 1500 })
    );
  });

  it('leaves a timeout under the cap untouched', () => {
    const onUpdate = renderForm({ subWorkflowId: 'wf-1', subWorkflowTimeoutSeconds: 300 });
    fireEvent.change(screen.getByPlaceholderText('timeoutPlaceholder'), { target: { value: '600' } });
    expect(onUpdate).toHaveBeenCalledWith(
      expect.objectContaining({ subWorkflowTimeoutSeconds: 600 })
    );
  });

  it('advertises the cap on the input so the field itself refuses more', () => {
    renderForm({ subWorkflowId: 'wf-1', subWorkflowTimeoutSeconds: 300 });
    const field = screen.getByPlaceholderText('timeoutPlaceholder') as HTMLInputElement;
    expect(field.getAttribute('max')).toBe('1500');
  });
});
