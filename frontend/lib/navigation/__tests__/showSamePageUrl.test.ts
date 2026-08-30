// @vitest-environment jsdom
/**
 * Changing the address of the page already on screen.
 *
 * The defect it exists for: Next drops a `router.push`/`replace` that only REMOVES query
 * params from the pathname the page was loaded at - which is what "back to the plain list",
 * "back to the default tab" and "strip the callback param" all ask for. From a page opened
 * directly on such a URL the click did nothing at all, with no error.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { samePageUrl, showSamePageUrl } from '../showSamePageUrl';

const push = vi.fn();
const replace = vi.fn();
const realPush = window.history.pushState;
const realReplace = window.history.replaceState;

beforeEach(() => {
  push.mockClear();
  replace.mockClear();
  window.history.pushState = push as unknown as typeof window.history.pushState;
  window.history.replaceState = replace as unknown as typeof window.history.replaceState;
  window.location.hash = '';
});
afterEach(() => {
  window.history.pushState = realPush;
  window.history.replaceState = realReplace;
});

describe('showSamePageUrl', () => {
  it('records a step the user chose, so Back can return from it', () => {
    showSamePageUrl('/en/app/agent?view=fleet', '/en/app/agent');

    expect(push).toHaveBeenCalledWith(null, '', '/en/app/agent?view=fleet');
    expect(replace).not.toHaveBeenCalled();
  });

  it('drops the last query param, which no router push could do', () => {
    showSamePageUrl('/en/app/agent', '/en/app/agent?view=fleet');

    expect(push).toHaveBeenCalledWith(null, '', '/en/app/agent');
  });

  it('corrects an address that was never valid in place, so Back cannot return to it', () => {
    showSamePageUrl('/en/app/workflow', '/en/app/workflow?folder=gone', 'replace');

    expect(replace).toHaveBeenCalledWith(null, '', '/en/app/workflow');
    expect(push).not.toHaveBeenCalled();
  });

  it('does nothing for the address already shown, so no step costs an extra Back', () => {
    showSamePageUrl('/en/app/workflow?q=a', '/en/app/workflow?q=a');

    expect(push).not.toHaveBeenCalled();
    expect(replace).not.toHaveBeenCalled();
  });

  it('keeps the anchor, because the page is not changing', () => {
    window.location.hash = '#members';

    showSamePageUrl('/en/app/settings/organization', '/en/app/settings/organization?invite', 'replace');

    expect(replace).toHaveBeenCalledWith(null, '', '/en/app/settings/organization#members');
  });

  it('leaves an anchor the caller asked for alone', () => {
    window.location.hash = '#members';

    showSamePageUrl('/en/app/settings/organization#security', '/en/app/settings/organization');

    expect(push).toHaveBeenCalledWith(null, '', '/en/app/settings/organization#security');
  });
});

describe('samePageUrl', () => {
  it('is the pathname alone when the page carries no query', () => {
    expect(samePageUrl('/en/app/workflow', new URLSearchParams())).toBe('/en/app/workflow');
  });

  it('carries the whole query the page is showing', () => {
    expect(samePageUrl('/en/app/workflow', new URLSearchParams('folder=f1&q=alpha')))
      .toBe('/en/app/workflow?folder=f1&q=alpha');
  });
});
