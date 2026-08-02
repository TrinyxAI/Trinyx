/**
 * The run panel resolves a step's icon and human label through this store, and
 * several canvases can be mounted at once (the page canvas plus a sub-workflow
 * or application canvas in a side-panel tab). A single global slot meant the last
 * canvas to render won, and the panel labelled steps with another workflow's
 * nodes.
 */
import { afterEach, describe, expect, it } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import {
  clearCanvasNodes,
  getCanvasNodes,
  setCanvasNodes,
} from '@/app/workflows/builder/services/canvasNodesStore';

const node = (id: string): Node<BuilderNodeData> =>
  ({ id, position: { x: 0, y: 0 }, data: { label: id } } as unknown as Node<BuilderNodeData>);

afterEach(() => clearCanvasNodes());

describe('canvasNodesStore', () => {
  it('returns each workflow its OWN nodes when two canvases are mounted', () => {
    setCanvasNodes([node('a1')], 'wf-a');
    setCanvasNodes([node('b1')], 'wf-b');

    expect(getCanvasNodes('wf-a').map(n => n.id)).toEqual(['a1']);
    expect(getCanvasNodes('wf-b').map(n => n.id)).toEqual(['b1']);
  });

  it('never lends another canvas nodes to a workflow that has not published yet', () => {
    setCanvasNodes([node('b1')], 'wf-b');

    // The mount window, where two canvases race. "No nodes yet" is the honest
    // answer; the other canvas's nodes would be the mislabelling this prevents.
    expect(getCanvasNodes('wf-a')).toEqual([]);
  });

  it('keeps the unscoped read on the last publisher for the legacy call sites', () => {
    setCanvasNodes([node('a1')], 'wf-a');
    expect(getCanvasNodes().map(n => n.id)).toEqual(['a1']);
  });

  it('forgets a workflow when its canvas unmounts', () => {
    setCanvasNodes([node('a1')], 'wf-a');
    clearCanvasNodes('wf-a');
    // Otherwise the map keeps a prepared node array per workflow visited in the
    // session, and a stale one could answer for a remounted canvas.
    expect(getCanvasNodes('wf-a')).toEqual([]);
    // The unscoped readers must not keep labelling steps with the graph that
    // just unmounted either.
    expect(getCanvasNodes()).toEqual([]);
  });

  it('leaves the unscoped read alone when a DIFFERENT canvas unmounts', () => {
    setCanvasNodes([node('a1')], 'wf-a');
    setCanvasNodes([node('b1')], 'wf-b');

    clearCanvasNodes('wf-a');

    // wf-b is still on screen and is still the last publisher.
    expect(getCanvasNodes().map(n => n.id)).toEqual(['b1']);
  });

  it('hands the unscoped read to a survivor when the LAST publisher unmounts', () => {
    // Closing a sub-workflow tab that published most recently must not blank the
    // arg-less read while the page canvas is still on screen: the legacy readers
    // (step table, run result modal) resolve icons and labels through it and
    // would fall back to raw aliases, with no event to heal them.
    setCanvasNodes([node('a1')], 'wf-a');
    setCanvasNodes([node('b1')], 'wf-b');

    clearCanvasNodes('wf-b');

    expect(getCanvasNodes().map(n => n.id)).toEqual(['a1']);
  });

  it('clears everything when called without a workflow', () => {
    setCanvasNodes([node('a1')], 'wf-a');
    setCanvasNodes([node('b1')], 'wf-b');

    clearCanvasNodes();

    expect(getCanvasNodes('wf-a')).toEqual([]);
    expect(getCanvasNodes('wf-b')).toEqual([]);
    expect(getCanvasNodes()).toEqual([]);
  });
});
