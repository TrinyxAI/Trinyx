/**
 * The run address is what survives a reload, so it is built from the CURRENT
 * pathname: a hard-coded `/app/...` would drop the locale segment, and a
 * pathname that is not this workflow's page must be left alone rather than
 * rewritten into a silent navigation.
 *
 * `workflowIdFromPathname` answers the other half: which provider, among the
 * several mounted at once, the URL is speaking for.
 */
import { describe, expect, it } from 'vitest';
import { runRoutePathFor, workflowIdFromPathname } from '@/lib/workflow/runRoutePath';

describe('runRoutePathFor', () => {
  it('adds the run route to the edit page', () => {
    expect(runRoutePathFor('/app/workflow/wf-1', 'wf-1', 'run-9')).toBe('/app/workflow/wf-1/run/run-9');
  });

  it('swaps the run of a run page', () => {
    expect(runRoutePathFor('/app/workflow/wf-1/run/run-1', 'wf-1', 'run-9')).toBe('/app/workflow/wf-1/run/run-9');
  });

  it('keeps the locale segment', () => {
    expect(runRoutePathFor('/fr/app/workflow/wf-1/run/run-1', 'wf-1', 'run-9')).toBe('/fr/app/workflow/wf-1/run/run-9');
  });

  it('replaces anything hanging under the previous run along with it', () => {
    expect(runRoutePathFor('/app/workflow/wf-1/run/run-1/step/s3', 'wf-1', 'run-9'))
      .toBe('/app/workflow/wf-1/run/run-9');
  });

  it('returns null for another workflow than the one being bound', () => {
    expect(runRoutePathFor('/app/workflow/wf-2/run/run-1', 'wf-1', 'run-9')).toBeNull();
  });

  it('returns null on a workflow id that merely starts the same', () => {
    expect(runRoutePathFor('/app/workflow/wf-12/run/run-1', 'wf-1', 'run-9')).toBeNull();
  });

  it('returns null off the workflow page (an application or chat surface)', () => {
    expect(runRoutePathFor('/app/applications/pub-1', 'wf-1', 'run-9')).toBeNull();
    expect(runRoutePathFor('/app/c/conv-1', 'wf-1', 'run-9')).toBeNull();
  });

  it('refuses a prefix that is not a bare locale segment', () => {
    // Anything deeper embedding the same path is a different page - rewriting it
    // would move the user somewhere they never asked to go.
    expect(runRoutePathFor('/embed/preview/app/workflow/wf-1', 'wf-1', 'run-9')).toBeNull();
  });

  it('returns null on missing inputs', () => {
    expect(runRoutePathFor(null, 'wf-1', 'run-9')).toBeNull();
    expect(runRoutePathFor('/app/workflow/wf-1', null, 'run-9')).toBeNull();
    expect(runRoutePathFor('/app/workflow/wf-1', 'wf-1', null)).toBeNull();
  });
});

describe('workflowIdFromPathname', () => {
  it('reads the workflow of an edit page and of a run page', () => {
    expect(workflowIdFromPathname('/app/workflow/wf-1')).toBe('wf-1');
    expect(workflowIdFromPathname('/app/workflow/wf-1/run/run-2')).toBe('wf-1');
  });

  it('reads it through the locale segment', () => {
    expect(workflowIdFromPathname('/fr/app/workflow/wf-7/run/run-2')).toBe('wf-7');
  });

  it('says nothing for a page that is not a workflow page', () => {
    // Those providers (application, shared link) keep the historical behaviour,
    // which is exactly why "no workflow named" must not read as "workflow null".
    expect(workflowIdFromPathname('/app/applications/pub-1')).toBeNull();
    expect(workflowIdFromPathname('/s/token-1')).toBeNull();
    expect(workflowIdFromPathname('/app/workflow')).toBeNull();
    expect(workflowIdFromPathname(null)).toBeNull();
  });

  it('is not fooled by a path that merely contains the route', () => {
    expect(workflowIdFromPathname('/embed/app/workflow/wf-1')).toBeNull();
  });
});
