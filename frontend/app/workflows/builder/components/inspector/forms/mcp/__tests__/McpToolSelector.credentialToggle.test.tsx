// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

// Surface the key as the value so the assertions read as intent, not as prose.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// The credential picker pulls in apiClient; nothing here is about the picker itself,
// only about whether it is the control on screen.
vi.mock('../../../CredentialSection', () => ({
  CredentialSection: () => <div data-testid="credential-picker" />,
}));

// ExpressionField owns a heavy editor and a popover. Stub it flat so the two text
// channels stay distinguishable: `description` is always visible, `infoContent` is
// behind the info icon, and putting the wrong one in the wrong slot is the mistake.
vi.mock('../../../ExpressionField', () => ({
  ExpressionField: (props: any) => (
    <div data-testid="expression-field">
      <span data-testid="expr-description">{props.description}</span>
      <span data-testid="expr-info">{props.infoContent}</span>
      <input
        data-testid="expr-input"
        placeholder={props.placeholder}
        value={props.value ?? ''}
        onChange={(e) => props.onChange(e.target.value)}
      />
    </div>
  ),
}));

import { McpToolSelector } from '../McpToolSelector';
import { isDynamicCredential, toggleCredentialMode } from '../credentialSelectorMode';

/**
 * The panel wiring for "which account does this step run on".
 *
 * <p>The helpers behind it are unit-tested and the shared toggle row is tested on its
 * own, but neither says the control is actually WIRED: the switch could reflect the
 * wrong flag, write to the wrong field, stay live in run mode, or put the security
 * sentence behind the info icon where an author who never clicks it would not see it.
 * Those are the failures that survive green helper tests.
 */
describe('McpToolSelector credential mode toggle', () => {
  afterEach(cleanup);

  const baseProps = (toolData: any, isRunMode = false) => ({
    node: { id: 'n1', data: { toolData } } as any,
    data: { toolData } as any,
    isToolNode: true,
    isApiNode: false,
    isRunMode,
    isDark: false,
    mcpNavigationLevel: 'tools' as const,
    mcpSearchQuery: '',
    setMcpSearchQuery: vi.fn(),
    shouldLoadApis: false,
    apiInitialLoading: false,
    mcpLoadingApis: false,
    mcpApis: [],
    apiHasMore: false,
    apiLoadMoreRef: null,
    mcpLoadingTools: false,
    toolInitialLoading: false,
    mcpApiTools: [],
    toolHasMore: false,
    toolLoadMoreRef: null,
    loadingToolDetails: false,
    toolParameters: [],
    toolCredentials: [{ name: 'api_key', isRequired: true }],
    allRequiredCredentialsConfigured: true,
    setAllRequiredCredentialsConfigured: vi.fn(),
    handleMouseEnter: vi.fn(),
    handleMouseLeave: vi.fn(),
    handleMcpApiClick: vi.fn(),
    handleMcpToolSelect: vi.fn(),
    effectiveShowOptionalParams: false,
    effectiveSetShowOptionalParams: vi.fn(),
    findUnknownVariables: () => [],
    connectionProps: {},
  });

  const toggle = () => screen.getByTestId('mcp-credential-selector-toggle').querySelector('[role="switch"]')!;

  it('shows the picker and an OFF switch when the step has no selector', () => {
    render(<McpToolSelector {...baseProps({ toolId: 't1' })} onUpdate={vi.fn()} />);

    expect(toggle().getAttribute('aria-checked')).toBe('false');
    expect(screen.getByTestId('credential-picker')).toBeTruthy();
    expect(screen.queryByTestId('expression-field')).toBeNull();
  });

  it('shows the expression field and an ON switch when the step carries a selector', () => {
    render(
      <McpToolSelector
        {...baseProps({ toolId: 't1', credentialSelector: '{{item.ig_account}}' })}
        onUpdate={vi.fn()}
      />,
    );

    expect(toggle().getAttribute('aria-checked')).toBe('true');
    expect(screen.getByTestId('expression-field')).toBeTruthy();
    // One question, one control: a visible picker showing one account while the run
    // used another is the confusion this replaces.
    expect(screen.queryByTestId('credential-picker')).toBeNull();
  });

  it('writes the toggled toolData through onUpdate, not some other shape', () => {
    const onUpdate = vi.fn();
    render(<McpToolSelector {...baseProps({ toolId: 't1' })} onUpdate={onUpdate} />);

    fireEvent.click(toggle());

    expect(onUpdate).toHaveBeenCalledTimes(1);
    const written = onUpdate.mock.calls[0][0];
    expect(written.toolData).toEqual(toggleCredentialMode({ toolId: 't1' }));
    expect(isDynamicCredential(written.toolData)).toBe(true);
    // Turning the mode on must not throw away what the step already had.
    expect(written.toolData.toolId).toBe('t1');
  });

  it('does not write anything in run mode', () => {
    // The inspector is read-only during a run; a live switch there would edit the
    // workflow from a screen the author is using to watch it.
    const onUpdate = vi.fn();
    render(<McpToolSelector {...baseProps({ toolId: 't1' }, true)} onUpdate={onUpdate} />);

    fireEvent.click(toggle());
    expect(onUpdate).not.toHaveBeenCalled();
  });

  it('keeps the security line visible and the long explanation behind the info icon', () => {
    render(
      <McpToolSelector
        {...baseProps({ toolId: 't1', credentialSelector: '' })}
        onUpdate={vi.fn()}
      />,
    );

    // Moving the whole explanation into the popover would have moved this with it, and
    // an author who never opens a popover still has to read who picks the account.
    expect(screen.getByTestId('expr-description').textContent).toBe('expressionCaution');
    expect(screen.getByTestId('expr-info').textContent).toBe('expressionHelp');
  });

  it('offers a placeholder the engine can actually resolve', () => {
    // {{trigger.output.account}} matches neither supported trigger shape, so the one
    // value the field offers to copy would resolve to nothing, which fails the step.
    render(
      <McpToolSelector
        {...baseProps({ toolId: 't1', credentialSelector: '' })}
        onUpdate={vi.fn()}
      />,
    );

    expect(screen.getByTestId('expr-input').getAttribute('placeholder')).toBe('{{item.ig_account}}');
  });

  it('changes its help line with the state, rather than printing one for both', () => {
    const { rerender } = render(
      <McpToolSelector {...baseProps({ toolId: 't1' })} onUpdate={vi.fn()} />,
    );
    expect(screen.getByText('toggleHelpOff')).toBeTruthy();

    rerender(
      <McpToolSelector
        {...baseProps({ toolId: 't1', credentialSelector: '{{item.a}}' })}
        onUpdate={vi.fn()}
      />,
    );
    expect(screen.getByText('toggleHelpOn')).toBeTruthy();
  });

  it('still offers the control on a credential-less tool that already has a selector', () => {
    // Gated on credentials alone, a step given an expression on a credential-less
    // endpoint showed a permanent canvas error telling the author to remove something
    // the screen offered no way to remove.
    render(
      <McpToolSelector
        {...{ ...baseProps({ toolId: 't1', credentialSelector: '{{item.a}}' }), toolCredentials: [] }}
        onUpdate={vi.fn()}
      />,
    );

    expect(screen.getByTestId('mcp-credential-selector-toggle')).toBeTruthy();
  });
});
