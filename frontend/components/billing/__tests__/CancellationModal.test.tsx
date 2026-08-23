// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

const cancelSubscription = vi.fn();
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: {
    cancelSubscription: (...args: unknown[]) => cancelSubscription(...args),
  },
}));

const labels: Record<string, string> = {
  titleReason: 'Cancel Subscription',
  reasonSubtitle: 'Why are you leaving?',
  'reasons.tooExpensive': "It's too expensive",
  'reasons.notUsing': "I'm not using it enough",
  'reasons.missingFeatures': 'Missing features I need',
  'reasons.switching': 'Switching to a competitor',
  'reasons.other': 'Other reason',
  feedbackLabel: 'Feedback',
  feedbackPlaceholder: 'Tell us more',
  cancel: 'Cancel',
  continue: 'Continue',
  titleRetention: 'Before you go',
  'retention.tooExpensive.title': 'Consider a lower tier',
  'retention.tooExpensive.message': 'You can choose a lower tier.',
  'retention.tooExpensive.action': 'Keep my plan',
  back: 'Back',
  continueWithCancellation: 'Continue with cancellation',
  titleConfirm: 'Confirm cancellation',
  effectiveDate: 'Effective {date}',
  whatHappens: 'What happens',
  'changes.accessRemoved': 'Access ends on that date',
  'changes.dataRetained': 'Data is retained',
  'changes.reactivateAnytime': 'You can reactivate',
  keepMyPlan: 'Keep my plan',
  processing: 'Processing',
  confirmCancellation: 'Confirm cancellation',
  titleSuccess: 'Cancellation scheduled',
  successMessage: 'Ends {date}',
  successReactivate: 'You can reactivate before then.',
  done: 'Done',
  endOfBillingPeriod: 'end of billing period',
  errorGeneric: 'Could not cancel',
};
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) => {
    let value = labels[key] ?? key;
    for (const [name, replacement] of Object.entries(params ?? {})) {
      value = value.replaceAll(`{${name}}`, String(replacement));
    }
    return value;
  },
}));

import CancellationModal from '../CancellationModal';

function renderModal(overrides: Record<string, unknown> = {}) {
  const props = {
    isOpen: true,
    onClose: vi.fn(),
    onSuccess: vi.fn(),
    planName: 'Pro',
    currentPeriodEnd: '2027-08-23T05:23:00Z',
    ...overrides,
  };
  return { ...render(<CancellationModal {...props} />), props };
}

function reachConfirmation() {
  fireEvent.click(screen.getByText("It's too expensive"));
  fireEvent.click(screen.getByText('Continue'));
  fireEvent.click(screen.getByText('Continue with cancellation'));
}

describe('CancellationModal', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);

  it('closes from the reason step without submitting', () => {
    const { props } = renderModal();
    fireEvent.click(screen.getByText('Cancel'));
    expect(props.onClose).toHaveBeenCalledOnce();
    expect(cancelSubscription).not.toHaveBeenCalled();
  });

  it('submits reason and feedback, shows the effective date, then refreshes the parent', async () => {
    cancelSubscription.mockResolvedValue({
      success: true,
      effectiveDate: '2027-08-23T05:23:00Z',
    });
    const { props } = renderModal();
    fireEvent.change(screen.getByPlaceholderText('Tell us more'), {
      target: { value: 'Missing a smaller paid tier' },
    });
    reachConfirmation();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm cancellation' }));

    await waitFor(() => expect(screen.getByText('Cancellation scheduled')).toBeTruthy());
    expect(cancelSubscription).toHaveBeenCalledOnce();
    expect(cancelSubscription).toHaveBeenCalledWith(
      'too_expensive',
      'Missing a smaller paid tier',
    );
    expect(screen.getByText(/Ends/)).toBeTruthy();

    fireEvent.click(screen.getByText('Done'));
    expect(props.onSuccess).toHaveBeenCalledOnce();
    expect(props.onClose).toHaveBeenCalledOnce();
  });

  it('keeps the confirmation usable after a failed request', async () => {
    cancelSubscription.mockRejectedValue(new Error('Stripe unavailable'));
    renderModal();
    reachConfirmation();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm cancellation' }));

    await waitFor(() => expect(screen.getByText('Stripe unavailable')).toBeTruthy());
    expect(screen.getByRole('button', { name: 'Confirm cancellation' })).toBeTruthy();
  });
});
