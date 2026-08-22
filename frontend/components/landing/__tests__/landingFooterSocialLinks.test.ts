import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const shellSrc = readFileSync(path.resolve(__dirname, '../LandingShell.tsx'), 'utf8');

describe('public landing footer social links', () => {
  const expectedHrefs = [
    'https://www.linkedin.com/in/trinyx-ai-5bb4a5430/',
    'https://x.com/Trinyxai',
    'https://www.instagram.com/trinyx.ai/',
    'https://github.com/eddinerabii/Trinyx',
    'https://www.tiktok.com/@trinyx.ai',
    'https://discord.gg/EykNSkEvM6',
  ];

  for (const href of expectedHrefs) {
    it(`links to ${href}`, () => {
      expect(shellSrc).toContain(`href="${href}"`);
    });
  }

  it('does not publish legacy social-profile URLs', () => {
    const legacyBrand = ['live', 'context'].join('');
    const obsoleteUrls = [
      `linkedin.com/company/${legacyBrand}`,
      `x.com/${legacyBrand}ai`,
      `instagram.com/${legacyBrand}`,
      `tiktok.com/@${legacyBrand}ai`,
      `github.com/${legacyBrand}-ai`,
    ];
    for (const obsoleteUrl of obsoleteUrls) {
      expect(shellSrc.toLowerCase()).not.toContain(obsoleteUrl);
    }
  });

  it('labels the Discord community invite for accessibility', () => {
    expect(shellSrc).toMatch(
      /href="https:\/\/discord\.gg\/EykNSkEvM6"[\s\S]*?aria-label="Discord"/,
    );
  });
});
