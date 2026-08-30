// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import AboutInformationContent from '../AboutInformationContent';

describe('AboutInformationContent public links', () => {
  afterEach(cleanup);

  it('publishes the official Trinyx social profiles', () => {
    render(<AboutInformationContent />);

    const expected: Record<string, string> = {
      LinkedIn: 'https://www.linkedin.com/in/trinyx-ai-5bb4a5430/',
      X: 'https://x.com/Trinyxai',
      Instagram: 'https://www.instagram.com/trinyx.ai/',
      GitHub: 'https://github.com/TrinyxAI/Trinyx',
      TikTok: 'https://www.tiktok.com/@trinyx.ai',
      Discord: 'https://discord.gg/EykNSkEvM6',
    };

    for (const [name, href] of Object.entries(expected)) {
      const link = screen.getByRole('link', { name });
      expect(link).toHaveAttribute('href', href);
      expect(link).toHaveAttribute('target', '_blank');
      expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    }
  });

  it('publishes the official Trinyx email and no legacy LiveContext links', () => {
    const { container } = render(<AboutInformationContent />);

    expect(screen.getByRole('link', { name: /Email/i })).toHaveAttribute(
      'href',
      'mailto:contact@trinyx.fr',
    );
    const retiredAddress = ['trinyx12', 'gmail.com'].join('@');
    expect(container.innerHTML).not.toContain(retiredAddress);
    expect(container.innerHTML).not.toMatch(/livecontext/i);
  });
});
