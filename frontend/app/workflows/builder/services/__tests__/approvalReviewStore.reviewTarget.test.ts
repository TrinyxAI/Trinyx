/**
 * @vitest-environment jsdom
 *
 * `isReviewTargetForNode` is the shared "is THIS node under approval review?"
 * predicate. Two surfaces depend on it and used to inline the same comparison:
 *   - InputColumn, to auto-expand the ancestor groups so their navigators mount;
 *   - InspectorPanel, to treat the node as having run data to inspect.
 *
 * REGRESSION behind the second one: an approval parked on its signal has NO
 * statusCounts of its own, so the inspector opened in Configuration view and the
 * input column rendered the variable picker instead of the reviewed item's
 * upstream rows - the item navigator ("Item 2 / 3") never mounted, which is the
 * one thing the reviewer needs to read before deciding.
 */
import { describe, it, expect, afterEach } from 'vitest';
import {
  clearApprovalReview,
  getApprovalReviewTarget,
  isReviewTargetForNode,
  requestApprovalReview,
} from '../approvalReviewStore';

afterEach(() => clearApprovalReview());

describe('isReviewTargetForNode', () => {
  it('matches the node that owns the review target', () => {
    requestApprovalReview('item-approval', 1, 1);
    expect(isReviewTargetForNode(getApprovalReviewTarget(), 'item-approval')).toBe(true);
  });

  it('does not match a different node while a review is active', () => {
    // The target is global (one review at a time), so every other node must keep
    // its normal layout and its normal Configuration/Run-data default.
    requestApprovalReview('item-approval', 1, 1);
    expect(isReviewTargetForNode(getApprovalReviewTarget(), 'prep')).toBe(false);
  });

  it('is false with no active review', () => {
    expect(isReviewTargetForNode(null, 'item-approval')).toBe(false);
  });

  it('is false for a missing node id rather than matching loosely', () => {
    requestApprovalReview('item-approval', 1, 1);
    expect(isReviewTargetForNode(getApprovalReviewTarget(), undefined)).toBe(false);
    expect(isReviewTargetForNode(getApprovalReviewTarget(), null)).toBe(false);
  });

  it('stops matching once the review is cleared', () => {
    requestApprovalReview('item-approval', 1, 1);
    clearApprovalReview();
    expect(isReviewTargetForNode(getApprovalReviewTarget(), 'item-approval')).toBe(false);
  });
});
