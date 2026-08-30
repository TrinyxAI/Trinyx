-- Merge node docs: say what happens when NO incoming branch was taken.
--
-- The docs stopped at "Waits for all predecessors to be COMPLETED or SKIPPED before
-- continuing", which reads as "and then it continues" - so an agent could reasonably place a
-- step it always wants to run on a merge fed only by exclusive branches. It does not run: a
-- merge whose predecessors ALL ended SKIPPED is itself SKIPPED, and that skip cascades to
-- everything below it. Verified on prod run run_<id> epoch 152, where the
-- opposite behaviour ran an agent on an item no branch had routed to it.
--
-- Same sentence added to the fork docs, where a reader lands when planning the branch/join pair.
UPDATE node_type_documentation
SET description = 'Merge: Wait for ALL incoming branches to complete (SYNCHRONIZATION ONLY). '
                  || 'Counterpart to Fork: Fork creates N branches, Merge waits for all N. '
                  || 'No data transformation - just synchronization. Waits for all predecessors to be '
                  || 'COMPLETED or SKIPPED before continuing. If EVERY predecessor ended SKIPPED, no branch '
                  || 'reached this node: the merge is SKIPPED too, and every node after it is SKIPPED as well. '
                  || 'So do not put a step that must always run on a merge fed only by mutually exclusive '
                  || 'branches - give it its own path from a node that always completes.',
    updated_at = NOW()
WHERE type = 'merge';

UPDATE node_type_documentation
SET description = 'Fork: Split into N parallel branches that ALL execute simultaneously. Each branch runs '
                  || 'independently. Has ports: branch_0, branch_1, ... Use Merge to wait for all branches to '
                  || 'complete. Unlike Decision (one branch), Fork runs ALL branches. A Merge fed by branches '
                  || 'that ALL ended SKIPPED is SKIPPED itself, along with everything after it.',
    updated_at = NOW()
WHERE type = 'fork';
