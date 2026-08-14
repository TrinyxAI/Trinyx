import { describe, expect, it } from 'vitest';
import { CLOUD_NO_SUBSCRIPTION, cloudSubscriptionPays } from '../cloud-link.service';

/**
 * A self-hosted install that relays its calls to a linked cloud account is
 * refused unless that account has an active PAID plan. The server decides this
 * ({@code InternalAuthController.ceLinkEntitlements}), and the rule is not
 * "has a plan": Free is excluded too, for the same reason it is on the cloud
 * itself, its monthly credits fund workflow runs and not the platform's
 * provider keys.
 *
 * <p>Restating only half of that rule in the UI is what left an install linked
 * to a Free account with no warning at all, right up to the moment every
 * relayed call, every generation included, came back refused.
 */
describe('cloudSubscriptionPays', () => {
  it('says no for a FREE cloud account, which the server refuses exactly like no account', () => {
    expect(cloudSubscriptionPays('FREE')).toBe(false);
  });

  it('says no whatever case the plan code arrives in', () => {
    // The value crosses a service boundary, so its casing is not this side's
    // to guarantee.
    expect(cloudSubscriptionPays('free')).toBe(false);
    expect(cloudSubscriptionPays('Free')).toBe(false);
  });

  it('says no for the no-subscription sentinel', () => {
    expect(cloudSubscriptionPays(CLOUD_NO_SUBSCRIPTION)).toBe(false);
  });

  it('says no when the plan is unknown, rather than assuming the good case', () => {
    // An absent answer is not a paid one, and guessing it means the warning is
    // hidden from precisely the install that needed it.
    expect(cloudSubscriptionPays(undefined)).toBe(false);
    expect(cloudSubscriptionPays(null)).toBe(false);
    expect(cloudSubscriptionPays('')).toBe(false);
  });

  it('says yes for the paid plans, which is what makes the switch usable at all', () => {
    expect(cloudSubscriptionPays('STARTER')).toBe(true);
    expect(cloudSubscriptionPays('PRO')).toBe(true);
    expect(cloudSubscriptionPays('TEAM')).toBe(true);
    expect(cloudSubscriptionPays('ENTERPRISE')).toBe(true);
    // PAYG pays as well: it is a balance the account topped up on purpose.
    expect(cloudSubscriptionPays('PAYG')).toBe(true);
  });
});
