// @vitest-environment jsdom
/**
 * Canvas toolbar chrome: the cursor-mode select replaced the old box-selection
 * toggle (edit mode only), and run mode gains the trigger picker + the
 * pin-as-production affordance. These tests pin that per-mode control set and
 * the select's write-through.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

const mockOnCursorModeChange = vi.fn();
// Both run controls gate THEMSELVES off (no fireable trigger / already on the
// pinned run), so the tests can drive that state through these flags.
let renderRunTrigger = true;
let renderPin = true;

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
// Radix's Select needs layout + pointer APIs jsdom does not provide (its
// SelectContentImpl throws on open), so the real dropdown is unusable here and
// is covered by the Playwright spec instead. This stub keeps the boundary that
// belongs to CanvasToolbar: item value in -> onCursorModeChange out.
vi.mock('@/components/ui/select', async () => {
  const react = await import('react');
  const PickCtx = react.createContext<(value: string) => void>(() => {});
  return {
    Select: ({ onValueChange, children }: { onValueChange: (v: string) => void; children: React.ReactNode }) =>
      react.createElement(PickCtx.Provider, { value: onValueChange }, children),
    SelectTrigger: ({ children, ...props }: { children: React.ReactNode }) =>
      react.createElement('button', { type: 'button', ...props }, children),
    SelectContent: ({ children }: { children: React.ReactNode }) => react.createElement('div', null, children),
    SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => {
      const pick = react.useContext(PickCtx);
      return react.createElement('button', { type: 'button', role: 'option', onClick: () => pick(value) }, children);
    },
  };
});
vi.mock('reactflow', () => ({
  Panel: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('../CanvasRunTriggerButton', () => ({
  CanvasRunTriggerButton: () => (renderRunTrigger ? <div data-testid="run-trigger-button" /> : null),
}));
vi.mock('../nodes/TriggerNodePinButton', () => ({
  TriggerNodePinButton: ({ workflowId, variant }: { workflowId: string; variant?: string }) =>
    renderPin ? <div data-testid="pin-button" data-workflow-id={workflowId} data-variant={variant} /> : null,
}));

import { CanvasToolbar } from '../CanvasToolbar';

const baseProps = () => ({
  isRunMode: false,
  canUndo: false,
  canRedo: false,
  isInteractive: true,
  isLockFocused: false,
  cursorMode: 'pan' as const,
  isSettingsOpen: false,
  nodes: [],
  showRunControls: false,
  workflowId: 'wf-1',
  onZoomIn: vi.fn(),
  onZoomOut: vi.fn(),
  onFitView: vi.fn(),
  onAutoLayout: vi.fn(),
  onToggleInteractivity: vi.fn(),
  onCursorModeChange: mockOnCursorModeChange,
  onToggleSettings: vi.fn(),
});

beforeEach(() => {
  mockOnCursorModeChange.mockReset();
  renderRunTrigger = true;
  renderPin = true;
});

describe('CanvasToolbar', () => {
  it('offers the cursor-mode select in edit mode, labelled with the active mode', () => {
    render(<CanvasToolbar {...baseProps()} />);
    const select = screen.getByTestId('canvas-cursor-mode-select');
    expect(select.getAttribute('title')).toBe('cursorModePan');
    // Icon-only trigger: the accessible name has to carry the active mode too.
    expect(select.getAttribute('aria-label')).toBe('cursorMode: cursorModePan');
  });

  it('labels the select with the selection mode when box selection is active', () => {
    render(<CanvasToolbar {...baseProps()} cursorMode="selection" />);
    const select = screen.getByTestId('canvas-cursor-mode-select');
    expect(select.getAttribute('title')).toBe('cursorModeSelection');
    expect(select.getAttribute('aria-label')).toBe('cursorMode: cursorModeSelection');
  });

  it('reports the picked cursor mode to the canvas', () => {
    render(<CanvasToolbar {...baseProps()} />);
    fireEvent.click(screen.getByRole('option', { name: 'cursorModeSelection' }));
    expect(mockOnCursorModeChange).toHaveBeenCalledWith('selection');
  });

  it('reports the switch back to the hand mode', () => {
    render(<CanvasToolbar {...baseProps()} cursorMode="selection" />);
    fireEvent.click(screen.getByRole('option', { name: 'cursorModePan' }));
    expect(mockOnCursorModeChange).toHaveBeenCalledWith('pan');
  });

  it('swaps the trigger icon with the mode, the only at-a-glance indicator', () => {
    const { unmount } = render(<CanvasToolbar {...baseProps()} />);
    const handIcon = screen.getByTestId('canvas-cursor-mode-select').querySelector('svg');
    expect(handIcon?.getAttribute('class')).toMatch(/hand/);
    unmount();

    render(<CanvasToolbar {...baseProps()} cursorMode="selection" />);
    const pointerIcon = screen.getByTestId('canvas-cursor-mode-select').querySelector('svg');
    // The selection-box-with-arrow icon, not a bare pointer: it has to read as
    // "draw a selection" at a glance.
    expect(pointerIcon?.getAttribute('class')).toMatch(/square-dashed-mouse-pointer/);
  });

  it('puts the cursor-mode select first in the edit-mode toolbar', () => {
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    const groups = Array.from(container.querySelectorAll('.flex.items-center.gap-1.border-r'));
    expect(groups[0]?.querySelector('[data-testid="canvas-cursor-mode-select"]')).toBeTruthy();
  });

  it('offers exactly the two cursor modes', () => {
    render(<CanvasToolbar {...baseProps()} />);
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual([
      'cursorModePan',
      'cursorModeSelection',
    ]);
  });

  it('hides the cursor-mode select in run mode (nothing to select there)', () => {
    render(<CanvasToolbar {...baseProps()} isRunMode showRunControls />);
    expect(screen.queryByTestId('canvas-cursor-mode-select')).toBeNull();
  });

  it('shows the trigger picker and the toolbar pin button in run mode', () => {
    render(<CanvasToolbar {...baseProps()} isRunMode showRunControls />);
    expect(screen.getByTestId('run-trigger-button')).toBeTruthy();
    const pin = screen.getByTestId('pin-button');
    expect(pin.getAttribute('data-variant')).toBe('toolbar');
    expect(pin.getAttribute('data-workflow-id')).toBe('wf-1');
  });

  it('hides both run controls in edit mode', () => {
    render(<CanvasToolbar {...baseProps()} />);
    expect(screen.queryByTestId('run-trigger-button')).toBeNull();
    expect(screen.queryByTestId('pin-button')).toBeNull();
  });

  it('hides the run controls in the read-only preview even while in run mode', () => {
    // Marketplace preview: the visitor can neither fire a trigger nor pin.
    render(<CanvasToolbar {...baseProps()} isRunMode showRunControls={false} />);
    expect(screen.queryByTestId('run-trigger-button')).toBeNull();
    expect(screen.queryByTestId('pin-button')).toBeNull();
  });

  it('omits the pin button when no workflow id is known', () => {
    render(<CanvasToolbar {...baseProps()} isRunMode showRunControls workflowId={undefined} />);
    expect(screen.getByTestId('run-trigger-button')).toBeTruthy();
    expect(screen.queryByTestId('pin-button')).toBeNull();
  });

  it('collapses the run-controls group when both controls gate themselves off', () => {
    // Terminal run already on the pinned version: a bare separator with padding
    // would otherwise be left hanging at the head of the toolbar.
    renderRunTrigger = false;
    renderPin = false;
    render(<CanvasToolbar {...baseProps()} isRunMode showRunControls />);

    // Both controls portal their menus/modals to document.body, so an
    // element-empty group really is an empty separator on screen.
    expect(screen.getByTestId('canvas-toolbar-run-controls').childElementCount).toBe(0);
  });

  it('keeps the run-controls group visible while at least one control renders', () => {
    renderPin = false;
    render(<CanvasToolbar {...baseProps()} isRunMode showRunControls />);
    expect(screen.getByTestId('canvas-toolbar-run-controls').childElementCount).toBe(1);
  });
});
