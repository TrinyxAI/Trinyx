/**
 * @vitest-environment jsdom
 *
 * The restart affordance has to exist on EVERY node renderer, not just the generic one.
 *
 * It first shipped gated in `FlowNode` alone, so decision, switch, merge, fork, agent,
 * approval and ten more node types stayed locked out of automatic mode: a user whose
 * `core:decision` took the wrong branch saw no restart button and concluded the feature did
 * not exist. All of them now route through one predicate; this pins the predicate AND the fact
 * that every renderer calls it, which is the part a per-file change silently loses.
 */
import { describe, it, expect } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import { showsNodeRunActions } from '../shared';

const status = (over: Partial<Parameters<typeof showsNodeRunActions>[0]> = {}) => ({
  isStepByStepMode: false,
  canRerun: false,
  isRunning: false,
  ...over,
});

describe('showsNodeRunActions', () => {
  it('shows the bar while the user is stepping the run', () => {
    expect(showsNodeRunActions(status({ isStepByStepMode: true }))).toBe(true);
  });

  it('shows the bar on a settled node of an automatic run, so it can be restarted', () => {
    expect(showsNodeRunActions(status({ canRerun: true }))).toBe(true);
  });

  it('hides it on a node that is neither steppable nor restartable', () => {
    expect(showsNodeRunActions(status())).toBe(false);
  });

  it('hides it on a RUNNING node: NodePlayButton renders no restart there, only a spinner', () => {
    expect(showsNodeRunActions(status({ canRerun: true, isRunning: true }))).toBe(false);
  });

  it('still shows it on a running node while stepping, where the play/spinner belongs', () => {
    expect(showsNodeRunActions(status({ isStepByStepMode: true, isRunning: true }))).toBe(true);
  });
});

/**
 * Every renderer that draws a NodeBottomBar must gate it on the shared predicate. Checked on
 * the source rather than by rendering seventeen components: the failure mode is someone adding
 * a node type (or reverting one file) with the old `isStepByStepMode &&` gate, which renders
 * perfectly and just never offers a restart.
 */
describe('every node renderer routes its run-action bar through the shared predicate', () => {
  const dir = path.resolve(__dirname, '..');
  const renderers = fs.readdirSync(dir)
    .filter(f => f.endsWith('.tsx'))
    .filter(f => fs.readFileSync(path.join(dir, f), 'utf8').includes('<NodeBottomBar'));

  it('finds the renderers to check', () => {
    expect(renderers.length).toBeGreaterThanOrEqual(14);
  });

  it.each(renderers)('%s does not gate its bottom bar on isStepByStepMode alone', file => {
    const src = fs.readFileSync(path.join(dir, file), 'utf8');
    const badGate = /\{\s*(?:[!\w.]+\s*&&\s*)*\w+\.isStepByStepMode\s*&&\s*\(/;
    expect(
      badGate.test(src),
      `${file} still gates its NodeBottomBar on isStepByStepMode; use showsNodeRunActions(...) `
      + 'so the restart affordance reaches this node type too',
    ).toBe(false);
  });
});
