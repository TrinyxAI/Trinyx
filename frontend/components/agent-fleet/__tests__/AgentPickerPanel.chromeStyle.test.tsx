/**
 * @vitest-environment jsdom
 *
 * The agent picker is what opens when you press Edit on the fleet canvas, so its
 * close button is the "close" a user meets right after that Edit. It was the last
 * round control on that path, and it was TWO controls: a `rounded-full` pill
 * floating at `-left-10` (desktop only, because a negative offset clips off the
 * canvas on a phone) plus a second round one inside the panel for mobile.
 *
 * Pinned here: one close button for every screen size, square, and a panel that
 * is a chrome surface rather than a `rounded-[32px]` capsule.
 */
import '@testing-library/jest-dom/vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// Renders no text: the agent name must be found through the row's own label, not
// through the avatar's alt text.
vi.mock('@/components/agents', () => ({
  AvatarDisplay: ({ name }: { name: string }) => <span data-testid="avatar" data-name={name} />,
}));

import { AgentPickerPanel } from '../AgentPickerPanel';
import { canvasChromeSurfaceClass } from '@/components/ui/canvas-chrome';

const AGENTS = [
  { id: 'a1', name: 'Scout', description: 'finds things' },
  { id: 'a2', name: 'Writer' },
];

function renderPanel(onClose = vi.fn(), onSelectAgent = vi.fn()) {
  const utils = render(
    <AgentPickerPanel isOpen agents={AGENTS} onClose={onClose} onSelectAgent={onSelectAgent} />,
  );
  const panel = utils.container.querySelector('[data-agent-picker-panel]') as HTMLElement;
  const close = screen.getByTitle('common.close');
  return { ...utils, panel, close, onClose, onSelectAgent };
}

describe('AgentPickerPanel - the close button of the fleet edit flow', () => {
  it('renders exactly ONE close button, not one per breakpoint', () => {
    const { container } = renderPanel();
    // Every button that contains only the X icon and no text.
    const closers = Array.from(container.querySelectorAll('button')).filter(
      (b) => b.querySelector('svg.lucide-x') && !b.textContent?.trim(),
    );
    expect(closers).toHaveLength(1);
  });

  it('is square, and no longer the pill it was', () => {
    const { close } = renderPanel();
    expect(close.className).toContain('rounded-xl');
    expect(close.className).not.toContain('rounded-full');
  });

  it('is in flow, so it can never be hidden or clipped on a small screen', () => {
    const { close } = renderPanel();
    // The old desktop-only pill was `hidden sm:flex absolute top-0 -left-10`.
    expect(close.className).not.toContain('absolute');
    expect(close.className).not.toContain('hidden');
    expect(close.className).not.toContain('-left-10');
    expect(close.closest('[data-agent-picker-panel]')).not.toBeNull();
  });

  it('still closes the panel', () => {
    const { close, onClose } = renderPanel();
    fireEvent.click(close);
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('AgentPickerPanel - the panel surface', () => {
  it('is the shared canvas chrome surface, not a hardcoded capsule', () => {
    const { panel } = renderPanel();
    for (const token of canvasChromeSurfaceClass.split(/\s+/)) {
      expect(panel.className, `panel lost ${token}`).toContain(token);
    }
    expect(panel.className).toContain('rounded-2xl');
    expect(panel.className).not.toContain('rounded-[32px]');
    // The old surface ignored the palette entirely.
    expect(panel.className).not.toContain('bg-white/80');
    expect(panel.className).not.toContain('dark:bg-gray-800/80');
  });
});

describe('AgentPickerPanel - behaviour is unchanged by the restyle', () => {
  it('lists the agents and reports the one that was picked', () => {
    const { onSelectAgent } = renderPanel();
    expect(screen.getByText('Scout')).toBeInTheDocument();
    expect(screen.getByText('Writer')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Writer'));
    expect(onSelectAgent).toHaveBeenCalledWith(expect.objectContaining({ id: 'a2' }));
  });

  it('still filters on the search field', () => {
    renderPanel();
    fireEvent.change(screen.getByPlaceholderText('common.search...'), { target: { value: 'wri' } });
    expect(screen.queryByText('Scout')).toBeNull();
    expect(screen.getByText('Writer')).toBeInTheDocument();
  });

  it('renders nothing when closed', () => {
    const { container } = render(
      <AgentPickerPanel isOpen={false} agents={AGENTS} onClose={vi.fn()} onSelectAgent={vi.fn()} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
