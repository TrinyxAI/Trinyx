import { describe, expect, it } from 'vitest';
import {
  isSettingsNavItemVisible,
  settingsNavItems,
} from '@/components/settings/settingsNavItems';

const billing = settingsNavItems.find(
  item => item.href === '/app/settings/billing',
)!;

describe('settings billing navigation', () => {
  it('is visible in paid monolith with embedded auth', () => {
    expect(isSettingsNavItemVisible(billing, {
      isAdmin: false,
      isCe: true,
      billingEnabled: true,
    })).toBe(true);
  });

  it('is hidden in free Community Edition', () => {
    expect(isSettingsNavItemVisible(billing, {
      isAdmin: false,
      isCe: true,
      billingEnabled: false,
    })).toBe(false);
  });
});
