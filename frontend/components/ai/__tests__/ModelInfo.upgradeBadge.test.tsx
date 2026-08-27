// @vitest-environment jsdom
/**
 * The upgrade label reaches every LLM picker through ONE row.
 *
 * <p>Seven surfaces let a reader choose a model that spends pay-as-you-go
 * credits: the chat composer menu, the agent dialog, the chat config panel, and
 * the agent / classify / guardrail / browser-agent node inspectors. All seven
 * draw their rows with `ModelOptionDisplay`, so the marker is placed there once
 * rather than seven times. This pins that arrangement, and pins that the row
 * stays PRESENTATIONAL: it takes the verdict as a value and asks nobody, so it
 * renders standalone and costs one query per list rather than one per option.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

import type { AIModel } from '@/hooks/useModels';
import { ModelOptionDisplay } from '../ModelInfo';

const model: AIModel = {
  id: 'gpt-x',
  name: 'GPT X',
  provider: 'openai',
  pricing: { input: 5, output: 25 },
};

afterEach(cleanup);

describe('ModelOptionDisplay - the upgrade marker', () => {
  it('marks a model this account cannot pay for', () => {
    render(<ModelOptionDisplay model={model} upgradeRequired />);

    expect(screen.getByText('label')).toBeInTheDocument();
    // Beside the facts about the model, not in place of them: the reader still
    // needs the price to decide whether to pay it.
    expect(screen.getByText('GPT X')).toBeInTheDocument();
  });

  it('leaves the row untouched for an account that can pay', () => {
    render(<ModelOptionDisplay model={model} />);

    expect(screen.queryByText('label')).not.toBeInTheDocument();
    expect(screen.getByText('GPT X')).toBeInTheDocument();
  });

  it('spends no width on the word, in either variant', () => {
    // The row already carries tier, capabilities, context and price, and the
    // ~280px inspector rows carry the same. So the marker is a lock in both,
    // and the word stays for a screen reader, which reads the option's text
    // rather than its width.
    for (const variant of ['default', 'compact'] as const) {
      const { unmount } = render(
        <ModelOptionDisplay model={model} variant={variant} upgradeRequired />,
      );
      expect(screen.getByText('label')).toHaveClass('sr-only');
      unmount();
    }
  });

  it('regression: renders with no query client behind it', () => {
    // The verdict arrives as a prop. When the row asked for it itself, every
    // consumer of this presentational component suddenly required a
    // QueryClientProvider, and two unrelated pricing suites had to stub a hook
    // module wholesale to keep rendering a row.
    expect(() => render(<ModelOptionDisplay model={model} upgradeRequired />)).not.toThrow();
  });
});
