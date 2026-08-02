'use client';

import * as React from 'react';
import { NodeCreatorPanel } from '@/app/workflows/builder/components/NodeCreatorPanel';
import { requestCreateNode, publishNodeCreatorVisibility } from '@/lib/workflow/nodeCreatorBus';

interface NodeCreatorPanelContentProps {
  workflowId: string;
}

/**
 * "Add Node" tab body: the node palette, rendered inside the workflow panel
 * instead of floating over the ReactFlow canvas.
 *
 * Picking an item does not create the node here - the builder owns the canvas.
 * The pick is forwarded through the node-creator bus, which is also what a
 * drag-and-drop onto the canvas ends up doing (the drag payload is unchanged,
 * so dragging a row from the side panel onto the canvas still works).
 */
export function NodeCreatorPanelContent({ workflowId }: NodeCreatorPanelContentProps) {
  // Tell the builder whether the palette is on screen: on mobile the side panel
  // is a full-screen overlay, and the builder drops the node selection then.
  // MOUNTED is the signal - the tab body only exists while its tab is selected,
  // so there is no hidden-but-mounted state to distinguish.
  React.useEffect(() => {
    publishNodeCreatorVisibility({ workflowId, open: true });
    return () => publishNodeCreatorVisibility({ workflowId, open: false });
  }, [workflowId]);

  const handleSelectNode = React.useCallback((item: string | unknown) => {
    requestCreateNode({ workflowId, item });
  }, [workflowId]);

  return (
    <div className="flex-1 min-h-0 flex flex-col" data-node-creator-tab>
      <NodeCreatorPanel
        embedded
        isOpen
        onSelectNode={handleSelectNode}
        currentWorkflowId={workflowId}
      />
    </div>
  );
}
