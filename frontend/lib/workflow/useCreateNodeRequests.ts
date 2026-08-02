'use client';

import { useEffect } from 'react';
import { CREATE_NODE_REQUEST_EVENT, type CreateNodeRequestDetail } from './nodeCreatorBus';

export interface CreateNodeRequestsOptions {
  /** Canvas this listener belongs to; a request for another workflow is ignored. */
  workflowId?: string;
  /** Read-only surface (run canvas, marketplace preview): never create anything. */
  isPreviewOnly?: boolean;
  onCreate: (item: unknown) => void;
}

/**
 * Bridges a pick made in the side-panel node palette to the canvas that owns the
 * graph. The two live in separate React trees, so the pick travels as a window
 * event and this is the only thing standing between it and a node appearing on
 * the WRONG canvas: a sub-workflow tab and the page canvas are mounted at the
 * same time, and a run canvas or the marketplace preview must not accept one at
 * all.
 *
 * Extracted from the builder so those two guards are testable without mounting
 * the whole editor.
 */
export function useCreateNodeRequests({ workflowId, isPreviewOnly, onCreate }: CreateNodeRequestsOptions): void {
  useEffect(() => {
    if (isPreviewOnly) return;
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<CreateNodeRequestDetail>).detail;
      if (!detail) return;
      if (detail.workflowId && workflowId && detail.workflowId !== workflowId) return;
      onCreate(detail.item);
    };
    window.addEventListener(CREATE_NODE_REQUEST_EVENT, handler);
    return () => window.removeEventListener(CREATE_NODE_REQUEST_EVENT, handler);
  }, [workflowId, isPreviewOnly, onCreate]);
}
