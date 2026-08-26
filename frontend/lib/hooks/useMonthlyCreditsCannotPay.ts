'use client';

import { useCreditBalance } from '@/lib/hooks/smart-hooks-complete';
import { IS_CE } from '@/lib/edition';

/**
 * Whether this account's credits cannot pay for anything but a workflow node.
 *
 * <p><b>The rule, once.</b> On the Free plan the monthly grant is scoped to
 * workflow orchestration: the server funds only the `WORKFLOW_NODE` source
 * types from it, and everything else (a chat turn, an agent's tokens, a
 * generation on the platform key, a web search) must come out of the separate
 * pay-as-you-go bucket. So a Free account can hold a healthy balance and still
 * be refused the moment it picks a model, which reads as a bug rather than as a
 * plan boundary. Every surface that offers such a choice asks this question, so
 * it is answered in one place.
 *
 * <p><b>Read from the server, never inferred.</b> `monthlyCreditsAreWorkflowOnly`
 * is the server's own answer, derived from the plan code. Deriving it here from
 * `subBalance > 0 && paygBalance === 0` looks equivalent and is not: it is true
 * of a paying subscriber who simply has not topped up, and would put an upgrade
 * badge in front of someone who already pays.
 *
 * <p><b>Blocked means blocked now.</b> The scoping alone is not enough: a Free
 * account with pay-as-you-go credits in hand can pay. The badge appears only
 * when the bucket that must pay is empty.
 *
 * <p>Never true on CE, where credits are unlimited and the rule does not exist.
 * The field is absent there, which reads as `false`, but the edition is checked
 * anyway so a future wire change cannot switch a paywall on in a self-hosted
 * install.
 */
export interface MonthlyCreditsVerdict {
  /**
   * True when a paid plan or a top-up is required before this can run.
   *
   * <p>The only field, deliberately: "still loading" is not a second state a
   * caller has to handle, because an unanswered balance already reads as not
   * blocked. A surface that branched on it would be choosing between two ways
   * of saying nothing.
   */
  blocked: boolean;
}

export function useMonthlyCreditsCannotPay(): MonthlyCreditsVerdict {
  const { paygBalance, monthlyCreditsAreWorkflowOnly } = useCreditBalance();

  const blocked = !IS_CE
    && monthlyCreditsAreWorkflowOnly
    // `null` is "not answered yet", which is not "empty": treating it as empty
    // would flash a paywall on every page load before the balance lands.
    && paygBalance != null
    && paygBalance <= 0;

  return { blocked };
}
