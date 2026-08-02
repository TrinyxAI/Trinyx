// @vitest-environment jsdom
/**
 * The bottom toolbar is one of the three canvas-chrome surfaces that moved off
 * the pill look onto the shared Button system. What has to hold, in BOTH modes:
 *
 *  - every control is the shared `canvasChromeCompactButtonClass`, not a local copy;
 *  - the two stateful controls (lock, settings) express their state through it;
 *  - nothing round is left behind;
 *  - the card is bounded by the CANVAS and scrolls, so a phone-width canvas can
 *    still reach every control instead of having it clipped off the edge.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
// Radix's Select cannot open in jsdom; only its trigger matters here.
vi.mock('@/components/ui/select', async () => {
  const react = await import('react');
  return {
    Select: ({ children }: { children: React.ReactNode }) => react.createElement('div', null, children),
    SelectTrigger: ({ children, ...props }: { children: React.ReactNode }) =>
      react.createElement('button', { type: 'button', ...props }, children),
    SelectContent: () => null,
    SelectItem: () => null,
  };
});
// Keep the Panel's className: it carries the width bound under test.
vi.mock('reactflow', () => ({
  Panel: ({ children, className }: { children: React.ReactNode; className?: string }) => (
    <div data-testid="canvas-toolbar-panel" className={className}>{children}</div>
  ),
}));
vi.mock('../CanvasRunTriggerButton', () => ({ CanvasRunTriggerButton: () => null }));
vi.mock('../nodes/TriggerNodePinButton', () => ({ TriggerNodePinButton: () => null }));

import {
  CANVAS_CHROME_CARD_PADDING_PX,
  CANVAS_CHROME_TOP_ROW_HEIGHT_PX,
  canvasChromeCompactButtonClass,
  canvasChromeSurfaceClass,
} from '@/components/ui/canvas-chrome';
import { CanvasToolbar } from '../CanvasToolbar';

const baseProps = () => ({
  isRunMode: false,
  canUndo: true,
  canRedo: true,
  isInteractive: true,
  isLockFocused: false,
  cursorMode: 'pan' as const,
  isSettingsOpen: false,
  nodes: [],
  showRunControls: false,
  workflowId: 'wf-1',
  onUndo: vi.fn(),
  onRedo: vi.fn(),
  onZoomIn: vi.fn(),
  onZoomOut: vi.fn(),
  onFitView: vi.fn(),
  onAutoLayout: vi.fn(),
  onToggleInteractivity: vi.fn(),
  onCursorModeChange: vi.fn(),
  onToggleSettings: vi.fn(),
});

/** The toolbar card - the Panel's only child. */
const card = (container: HTMLElement) =>
  container.querySelector('[data-testid="canvas-toolbar-panel"] > div') as HTMLElement;

/** Every icon control except the cursor-mode select (a Radix trigger). */
const iconButtons = (container: HTMLElement) =>
  Array.from(container.querySelectorAll<HTMLElement>('button[title]')).filter(
    (b) => b.getAttribute('data-testid') !== 'canvas-cursor-mode-select',
  );

describe('CanvasToolbar - canvas chrome style', () => {
  it('draws the card as the shared square chrome surface', () => {
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    for (const token of canvasChromeSurfaceClass.split(/\s+/)) {
      expect(card(container).className, `card lost ${token}`).toContain(token);
    }
  });

  it('gives every control the shared chrome button style, not a local copy', () => {
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    const buttons = iconButtons(container);
    // Undo, redo, zoom in/out, fit, auto-layout, lock, settings, AI assistant.
    expect(buttons).toHaveLength(9);
    // None of them is in its active state under these props.
    for (const button of buttons) {
      expect(button.className, `${button.getAttribute('title')} is off the shared style`)
        .toBe(canvasChromeCompactButtonClass(false));
    }
  });

  it('leaves nothing round behind, in either mode', () => {
    for (const isRunMode of [false, true]) {
      const { container, unmount } = render(<CanvasToolbar {...baseProps()} isRunMode={isRunMode} showRunControls={isRunMode} />);
      expect(container.innerHTML, `run mode: ${isRunMode}`).not.toContain('rounded-full');
      unmount();
    }
  });

  it('paints the lock button as active only while the lock is focused', () => {
    const { rerender } = render(<CanvasToolbar {...baseProps()} />);
    const lock = () => screen.getByTitle('disableInteractivity');
    expect(lock().className).toBe(canvasChromeCompactButtonClass(false));

    rerender(<CanvasToolbar {...baseProps()} isLockFocused />);
    expect(lock().className).toBe(canvasChromeCompactButtonClass(true));
  });

  it('paints the settings button as active only while the settings panel is open', () => {
    const { rerender } = render(<CanvasToolbar {...baseProps()} />);
    const settings = () => screen.getByTitle('settings');
    expect(settings().className).toBe(canvasChromeCompactButtonClass(false));

    rerender(<CanvasToolbar {...baseProps()} isSettingsOpen />);
    expect(settings().className).toBe(canvasChromeCompactButtonClass(true));
  });

  it('bounds its width by the CANVAS, never the viewport', () => {
    // `100vw` ignored the side panel: with one open the toolbar could be wider
    // than the canvas it belongs to and run underneath the panel.
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    const panel = screen.getByTestId('canvas-toolbar-panel');
    expect(panel.className).toContain('max-w-[calc(100%-1rem)]');
    expect(container.innerHTML).not.toContain('100vw');
  });

  it('scrolls horizontally rather than clipping controls on a narrow canvas', () => {
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    expect(card(container).className).toContain('overflow-x-auto');
    expect(card(container).className).toContain('max-w-full');
  });

  it('keeps the cursor-mode select at the same height and shape as the buttons', () => {
    // It is a Radix trigger, so it cannot take the helper directly - which is
    // exactly why its geometry has to be pinned against the helper's. A taller
    // select would push the card past the shared chrome height on its own.
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    const select = screen.getByTestId('canvas-cursor-mode-select');
    expect(select.className).toContain('h-7');
    expect(select.className).toContain('min-h-7');
    expect(select.className).not.toContain('h-9');
    expect(select.className).toContain('rounded-xl');
    expect(select.className).not.toContain('rounded-full');
    expect(card(container).className).toContain('items-center');
  });

  it('lands on the shared chrome height, like the mode toggle and the run bar', () => {
    // p-1 (4+4) around 28px controls = 36px. The toolbar used to be a 48px slab
    // beside a 36px run bar, which is what made it read as a different size.
    const { container } = render(<CanvasToolbar {...baseProps()} />);
    expect(card(container).className).toContain('p-1');
    expect(card(container).className).not.toContain('py-1.5');
    expect(CANVAS_CHROME_CARD_PADDING_PX * 2 + 28).toBe(CANVAS_CHROME_TOP_ROW_HEIGHT_PX);
    for (const button of iconButtons(container)) {
      expect(button.className, `${button.getAttribute('title')} is not a compact control`).toContain('h-7');
    }
  });
});
