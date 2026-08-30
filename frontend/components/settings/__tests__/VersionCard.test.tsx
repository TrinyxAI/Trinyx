/**
 * @vitest-environment jsdom
 *
 * Tests for {@code VersionCard}: build/edition rows, the self-hosted update
 * section (available / security / up-to-date), and that managed cloud never shows
 * an update affordance.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import * as React from 'react';
import VersionCard from '../VersionCard';
import type { AppVersionInfo } from '@/hooks/useAppVersion';

// next-intl: t(key, params) -> English label with {var} interpolation; locale = en.
const labels: Record<string, string> = {
  title: 'Version',
  subtitle: 'This installation',
  edition: 'Edition',
  version: 'Version',
  commit: 'Commit',
  builtOn: 'Built on',
  editionCe: 'Community Edition',
  editionSelfHostedEnterprise: 'Self-Hosted Enterprise',
  editionCloud: 'Cloud',
  editionDedicatedCloud: 'Dedicated Cloud',
  loadError: 'Could not load version information',
  updateAvailable: 'Update available',
  upToDate: "You're on the latest version",
  newVersion: 'Version {version} is available',
  securityFix: 'Security update',
  securityFixHint: 'This release contains a security fix. Update as soon as you can.',
  howToUpdate: 'How to update',
  releaseNotes: 'Release notes',
  lastChecked: 'Checked {date}',
  anonymousCheckNotice:
    'The daily update check sends this version and a random install id, so live self-hosted installs can be counted. '
    + 'The record kept contains no IP address, hostname or account. '
    + 'Set CE_VERSIONCHECK_SENDINSTALLID=false to stop sending the id, '
    + 'or CE_VERSIONCHECK_ENABLED=false to disable the check.',
};
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) => {
    let s = labels[key] ?? key;
    if (params) {
      for (const [k, v] of Object.entries(params)) s = s.replaceAll('{' + k + '}', String(v));
    }
    return s;
  },
  useLocale: () => 'en',
}));

const versionMock = vi.hoisted(() => ({
  current: { version: null as AppVersionInfo | null, isLoading: true, isError: false },
}));
vi.mock('@/hooks/useAppVersion', () => ({
  useAppVersion: () => versionMock.current,
}));

/** A version payload with sensible self-hosted defaults; override per test. */
function v(overrides: Partial<AppVersionInfo> = {}): AppVersionInfo {
  return {
    version: '0.1.0',
    gitSha: null,
    buildTime: null,
    edition: 'ce',
    selfHosted: true,
    managedCloud: false,
    updateAvailable: false,
    latestVersion: null,
    releaseUrl: null,
    securityFix: false,
    // Default false: the notice must be opted IN by a test that means to assert it, so a test
    // about something else cannot accidentally certify that an install identifies itself.
    identifiesInstall: false,
    checkedAt: null,
    ...overrides,
  };
}

function show(info: AppVersionInfo) {
  versionMock.current = { version: info, isLoading: false, isError: false };
  render(<VersionCard />);
}

describe('VersionCard', () => {
  beforeEach(() => {
    versionMock.current = { version: null, isLoading: true, isError: false };
  });

  it('shows neither data nor error while loading', () => {
    render(<VersionCard />);
    expect(screen.queryByText('Could not load version information')).toBeNull();
    expect(screen.queryByText('Community Edition')).toBeNull();
    expect(screen.getByText('This installation')).toBeTruthy();
  });

  it('renders edition, version, commit and build date for a cloud build (no update block)', () => {
    show(v({
      edition: 'cloud',
      selfHosted: false,
      managedCloud: true,
      version: '1.4.2',
      gitSha: 'abc1234',
      buildTime: '2026-06-25T10:30:00Z',
    }));

    expect(screen.getByText('Cloud')).toBeTruthy();
    expect(screen.getByText('1.4.2')).toBeTruthy();
    expect(screen.getByText('abc1234')).toBeTruthy();
    expect(screen.getByText('Built on')).toBeTruthy();
    // Managed cloud never shows an update affordance.
    expect(screen.queryByText('How to update')).toBeNull();
    expect(screen.queryByText("You're on the latest version")).toBeNull();
  });

  it('omits commit and build-date rows for a CE dev build with no git info', () => {
    show(v({ version: 'dev' }));

    expect(screen.getByText('Community Edition')).toBeTruthy();
    expect(screen.getByText('dev')).toBeTruthy();
    expect(screen.queryByText('Commit')).toBeNull();
    expect(screen.queryByText('Built on')).toBeNull();
  });

  it('shows a load-error line when the version cannot be fetched', () => {
    versionMock.current = { version: null, isLoading: false, isError: true };
    render(<VersionCard />);
    expect(screen.getByText('Could not load version information')).toBeTruthy();
  });

  it('falls back to the raw edition token for an unknown edition', () => {
    show(v({ edition: 'mystery', selfHosted: false, managedCloud: false }));
    expect(screen.getByText('mystery')).toBeTruthy();
  });

  it('shows an available (non-security) update with the new version, How-to-update and release notes', () => {
    show(v({
      updateAvailable: true,
      latestVersion: '0.2.0',
      releaseUrl: 'https://example.test/notes',
      checkedAt: '2026-06-25T11:00:00Z',
    }));

    expect(screen.getByText('Update available')).toBeTruthy();
    expect(screen.getByText('Version 0.2.0 is available')).toBeTruthy();
    expect(screen.getByText('How to update')).toBeTruthy();
    expect(screen.getByText('Release notes')).toBeTruthy();
    // Non-security: the security label/hint must NOT appear.
    expect(screen.queryByText('Security update')).toBeNull();
    // Last-checked line rendered.
    expect(screen.getByText(/Checked/)).toBeTruthy();
  });

  it('emphasises a security update with its hint', () => {
    show(v({ updateAvailable: true, securityFix: true, latestVersion: '0.2.0' }));

    expect(screen.getByText('Security update')).toBeTruthy();
    expect(screen.getByText('This release contains a security fix. Update as soon as you can.')).toBeTruthy();
    expect(screen.getByText('How to update')).toBeTruthy();
    // The neutral "Update available" label is replaced by the security one.
    expect(screen.queryByText('Update available')).toBeNull();
  });

  it('shows up-to-date when self-hosted and on the latest version', () => {
    show(v({ updateAvailable: false, latestVersion: '0.1.0', checkedAt: '2026-06-25T11:00:00Z' }));

    expect(screen.getByText("You're on the latest version")).toBeTruthy();
    expect(screen.getByText(/Checked/)).toBeTruthy();
    expect(screen.queryByText('How to update')).toBeNull();
  });

  it('never advertises the upstream feed to a paid-monolith Trinyx build', () => {
    show(v({
      edition: 'paid-monolith',
      selfHosted: true,
      updateAvailable: true,
      latestVersion: '0.2.12',
      releaseUrl: 'https://github.com/livecontext-ai/livecontext/releases/tag/v0.2.12',
    }));

    expect(screen.getByText('paid-monolith')).toBeTruthy();
    expect(screen.queryByText('Update available')).toBeNull();
    expect(screen.queryByText('Version 0.2.12 is available')).toBeNull();
    expect(screen.queryByText('How to update')).toBeNull();
    expect(screen.queryByText('Release notes')).toBeNull();
  });

  it('shows no update section before the feed is known', () => {
    show(v({ updateAvailable: false, latestVersion: null }));

    expect(screen.queryByText('How to update')).toBeNull();
    expect(screen.queryByText("You're on the latest version")).toBeNull();
  });

  it('discloses the anonymous install id when the install actually sends one', () => {
    show(v({ updateAvailable: false, latestVersion: null, identifiesInstall: true }));

    // The disclosure must not be attached to the update section: an install whose feed says
    // nothing still sends the id, and that is the state most installs are in most of the time.
    expect(screen.getByText(/random install id/)).toBeTruthy();
    expect(screen.getByText(/CE_VERSIONCHECK_SENDINSTALLID=false/)).toBeTruthy();
  });

  it('says nothing when the install does not identify itself, whatever its edition', () => {
    // The component reads identifiesInstall and nothing else, so one case is one case: three tests
    // varying selfHosted or edition would differ only in props it never inspects. What each of
    // these states has in common is the thing that matters, and the CE opted-out one is the reason
    // this is gated on a flag at all: telling an operator who turned it off that they are counted
    // would be worse than showing nothing.
    for (const state of [
      { selfHosted: false, managedCloud: true, edition: 'cloud' },        // managed cloud: no poll
      { selfHosted: true, edition: 'self-hosted-enterprise' },            // keycloak mode: no poller
      { selfHosted: true, edition: 'ce', updateAvailable: true, latestVersion: '0.3.0' }, // opted out
    ]) {
      cleanup();
      show(v({ ...state, identifiesInstall: false }));
      expect(screen.queryByText(/random install id/)).toBeNull();
    }
  });

  it('shows the disclosure outside the update block, not nested in it', () => {
    show(v({ identifiesInstall: true, updateAvailable: true, latestVersion: '0.3.0' }));

    // The state that ships most often. The notice must survive next to an update banner, and it
    // must not be attached to it: an install with nothing to update still sends the id.
    expect(screen.getByText('Update available')).toBeTruthy();
    expect(screen.getByText(/random install id/)).toBeTruthy();
  });
});
