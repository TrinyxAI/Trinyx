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
  getCanvasEdges,
  getCanvasNodes,
  setCanvasEdges,
  setCanvasNodes,
} from '@/app/workflows/builder/services/canvasNodesStore';

const node = (id: string): Node<BuilderNodeData> =>
  ({ id, position: { x: 0, y: 0 }, data: { label: id } } as unknown as Node<BuilderNodeData>);

const edge = (id: string) => ({ id, source: `${id}-s`, target: `${id}-t` } as never);

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

describe('canvasNodesStore - edges', () => {
  it('scopes edges per workflow exactly like nodes', () => {
    // The run panel orders its step list by DAG depth: reading the wrong
    // canvas's wiring reorders the list against a graph nobody is looking at.
    setCanvasEdges([edge('a')], 'wf-a');
    setCanvasEdges([edge('b')], 'wf-b');

    expect(getCanvasEdges('wf-a').map(e => e.id)).toEqual(['a']);
    expect(getCanvasEdges('wf-b').map(e => e.id)).toEqual(['b']);
    expect(getCanvasEdges('wf-never-published')).toEqual([]);
  });

  it('publishes nodes and edges independently, without either clobbering the other', () => {
    // They live in two builder states and ship from two effects; a single
    // combined setter would have each publish overwrite the other half.
    setCanvasNodes([node('a1')], 'wf-a');
    setCanvasEdges([edge('a')], 'wf-a');
    setCanvasNodes([node('a1'), node('a2')], 'wf-a');

    expect(getCanvasEdges('wf-a').map(e => e.id)).toEqual(['a']);
    expect(getCanvasNodes('wf-a').map(n => n.id)).toEqual(['a1', 'a2']);
  });

  it('drops the edges too when a canvas unmounts', () => {
    setCanvasNodes([node('a1')], 'wf-a');
    setCanvasEdges([edge('a')], 'wf-a');

    clearCanvasNodes('wf-a');

    expect(getCanvasEdges('wf-a')).toEqual([]);
    expect(getCanvasEdges()).toEqual([]);
  });

  it('hands the unscoped edge read to a survivor when the last publisher unmounts', () => {
    setCanvasEdges([edge('a')], 'wf-a');
    setCanvasEdges([edge('b')], 'wf-b');

    clearCanvasNodes('wf-b');

    expect(getCanvasEdges().map(e => e.id)).toEqual(['a']);
  });

  it('clears every workflow edges when called without a workflow', () => {
    setCanvasEdges([edge('a')], 'wf-a');
    setCanvasEdges([edge('b')], 'wf-b');

    clearCanvasNodes();

    expect(getCanvasEdges('wf-a')).toEqual([]);
    expect(getCanvasEdges()).toEqual([]);
  });
});
