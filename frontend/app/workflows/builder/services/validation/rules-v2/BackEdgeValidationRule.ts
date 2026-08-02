/**
 * Validates back-edges (loop edges) in the workflow.
 *
 * Checks:
 * - Back-edge condition is not empty (warning)
 * - maxIterations is > 0
 * - Target is actually an ancestor of source (prevents invalid back-edges)
 */

import type {
  ValidationRuleName,
  ValidationRuleResult,
  ValidationContext,
  ValidationIssue,
} from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { isAncestor } from '../../../utils/backEdgeDetection';

export class BackEdgeValidationRule extends BaseValidationRule {
  readonly ruleName: ValidationRuleName = 'BackEdge';
  readonly isCritical = false;
  readonly priority = 13;

  validate(context: ValidationContext): ValidationRuleResult {
    const issues: ValidationIssue[] = [];

    for (const edge of context.edges) {
      if (!edge.data?.isBackEdge) continue;

      const edgeKey = `${edge.source}->${edge.target}`;

      // Check maxIterations is valid
      const maxIter = edge.data.backEdgeMaxIterations;
      if (maxIter !== undefined && (typeof maxIter !== 'number' || maxIter < 1)) {
        issues.push(
          this.createError(
            edgeKey,
            'edge' as any,
            'Back-edge maxIterations must be a positive number',
          )
        );
      }

      // Verify target is an ancestor of source (validate it's actually a back-edge)
      const forwardEdges = context.edges.filter((e) => !e.data?.isBackEdge);
      if (!isAncestor(edge.target, edge.source, forwardEdges)) {
        issues.push(
          this.createError(
            edgeKey,
            'edge' as any,
            'Invalid back-edge: target must be an ancestor of source in the graph',
          )
        );
        continue;
      }

      // A loop-back with neither a condition nor a branch port has nothing that can ever stop
      // it, so it runs until the iteration cap - and reaching the cap fails the run. With a
      // branch port the branch selection IS the stop signal, so that case is fine.
      const condition = edge.data.backEdgeCondition;
      const sourceHasPort = !!edge.sourceHandle;
      if ((!condition || String(condition).trim() === '') && !sourceHasPort) {
        issues.push(
          this.createWarning(
            edgeKey,
            'edge' as any,
            'This loop has no way to stop: it has no condition and starts from a node with no branches, so it will run until the iteration limit, which fails the run',
          )
        );
      }
    }

    return this.buildResult(issues);
  }
}
