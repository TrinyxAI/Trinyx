// @vitest-environment jsdom
/**
 * The rule behind every upgrade badge in the app, tested where it is stated.
 *
 * <p>It decides whether a Free account's credits can pay for what a model does.
 * Getting it wrong in either direction is expensive: too eager and it puts a
 * paywall in front of a paying customer, too shy and the reader picks a model
 * that is about to be refused.
 */
import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const balance = vi.hoisted(() => ({
  paygBalance: null as number | null,
  monthlyCreditsAreWorkflowOnly: false,
  isLoading: false,
}));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useCreditBalance: () => balance,
}));

const edition = vi.hoisted(() => ({ isCe: false }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() { return edition.isCe; },
}));

import { useMonthlyCreditsCannotPay } from '../useMonthlyCreditsCannotPay';

function verdict() {
  return renderHook(() => useMonthlyCreditsCannotPay()).result.current.blocked;
}

beforeEach(() => {
  balance.paygBalance = null;
  balance.monthlyCreditsAreWorkflowOnly = false;
  balance.isLoading = false;
  edition.isCe = false;
});
afterEach(() => vi.clearAllMocks());

describe('useMonthlyCreditsCannotPay', () => {
  it('blocks a Free account whose top-up bucket is empty', () => {
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;

    expect(verdict()).toBe(true);
  });

  it('blocks one whose top-up bucket has gone negative', () => {
    // Post-flight LLM debits are allowed to overdraw, so a Free account can
    // land below zero and stay there. Below zero is still "cannot pay".
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = -12;

    expect(verdict()).toBe(true);
  });

  it('clears a Free account that has topped up', () => {
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 250;

    expect(verdict()).toBe(false);
  });

  it('regression: clears a PAYING account with no top-up, which is the ordinary case', () => {
    // A monthly balance and an empty top-up bucket is exactly what a PRO or
    // TEAM subscriber looks like. Inferring the rule from the two numbers
    // instead of reading the server's flag badged every one of them.
    balance.monthlyCreditsAreWorkflowOnly = false;
    balance.paygBalance = 0;

    expect(verdict()).toBe(false);
  });

  it('regression: says nothing while the balance is still on its way', () => {
    // `null` is "not answered yet", not "empty". Read as empty, it flashed a
    // paywall on every page load before the balance landed.
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = null;

    expect(verdict()).toBe(false);
  });

  it('regression: never blocks on a self-hosted install', () => {
    // CE has unlimited credits and no plans. Guarded on the edition as well as
    // the wire field, so a future change to the balance payload cannot switch a
    // paywall on in someone else's install.
    edition.isCe = true;
    balance.monthlyCreditsAreWorkflowOnly = true;
    balance.paygBalance = 0;

    expect(verdict()).toBe(false);
  });
});
