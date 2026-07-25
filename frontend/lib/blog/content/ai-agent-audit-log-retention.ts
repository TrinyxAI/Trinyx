// Markdown body for "How long should you keep AI agent logs?".
// Plain string module.
//
// PUBLIC register (rewritten 2026-07-24): the retention half of the audit pair
// (the what-to-log half is ai-agent-audit-trail.ts). Written for someone setting
// a retention policy, not for counsel. The legal substance is unchanged and was
// verified against the consolidated AI Act text and the EU AI Act service desk
// on 2026-07-23:
//   - Art. 12 record-keeping is HIGH-RISK ONLY.
//   - The 6-month log floor is Art. 19(1) (providers) and Art. 26(6)
//     (deployers), owed twice by two parties; 10 years is the Art. 18
//     DOCUMENTATION floor, a different regime.
//   - The explanation right is Art. 86, not Art. 12.
//   - The Digital Omnibus deferred high-risk application to 2 December 2027
//     (stand-alone) and August 2028 (embedded in regulated products); any
//     source citing August 2026 for high-risk is stale.
// The article's spine is that MOST readers are OUT OF SCOPE. Do not edit that
// into manufactured compliance urgency, and keep the "not legal advice" line.
// Storage figures model 10k runs/day: skeleton ~31 GB/yr, duplicated tool
// results ~84 GB/yr, duplicated system prompts ~21 GB/yr. They are a model, not
// a measurement.
const content = `"How long do we keep the logs?" usually gets answered with a number somebody remembers from another job. Ninety days. A year. Seven years, because that sounds safe.

There is a better way to decide, and it starts by noticing that you are not keeping one thing. You are keeping two, and they have very different costs.

## The short version

- Split the record in two: a small skeleton, and the bulky content.
- Keep the skeleton for years. It is cheap, and you cannot add it back later.
- Keep the content for months. It is nearly all the storage and nearly all the risk.
- Most AI agents are not covered by the AI Act's logging duties at all.
- Keeping everything forever is not the safe option. It is a different problem.

## Two clocks, not one

Almost every retention argument dissolves once you stop treating the record as a single thing.

| Layer | What is in it | How long | Why |
|---|---|---|---|
| Skeleton | Ids, timestamps, status, model, costs, which branch ran, fingerprints of payloads, who approved | Years | Tiny, and it answers most questions on its own |
| Content | Prompts, answers, tool arguments, tool results, error messages | Months | Nearly all your storage, and nearly all your personal-data exposure |

The hinge between them is the fingerprint. Keep a hash of every payload in the skeleton and you can still prove, years later, exactly what was sent and returned, without keeping a single word of it.

That is what makes a long window defensible rather than a liability.

## The arithmetic makes the decision for you

Take a busy system: ten thousand agent runs a day. Here is roughly where the bytes go per year. Treat these as a model rather than a measurement, and add a little for real-world overhead.

| What | Per year | What to do with it |
|---|---|---|
| Skeleton, all runs and all steps | about 31 GB | Keep for years. This is the cheap insurance |
| Duplicated tool results | about 84 GB | Store once, reference it |
| Duplicated system prompts | about 21 GB | Store once per version, reference by hash |

The skeleton costs a few dollars a year in block storage. Almost every retention debate is really an argument about the content layer, which is exactly the layer you have good reasons to keep short.

Two cheap wins hide in that table. The same system prompt stored on every run, sometimes more than once per run, is pure duplication. So are tool results copied into several places. Fix those and the storage question mostly goes away on its own.

## What the law actually requires

This is not legal advice, and none of the regimes below should be flattened into one number that applies to you. But the shape is worth knowing, because most articles get it wrong in the same two ways.

**The EU AI Act's six-month floor applies only to high-risk systems.** For those, the provider and the deployer each carry their own separate six-month minimum, each limited to the logs under their own control. It is owed twice, by two different parties, not shared between them.

**Six months is the floor for logs. Ten years is the floor for documentation.** Two distinct regimes, constantly conflated. Keeping your design documentation for a decade says nothing about how long you keep run records.

**And the part most readers need:** high-risk means a safety component of a regulated product, or one of the specific areas the Act lists, such as biometrics, critical infrastructure, employment decisions, access to essential services or law enforcement. A coding assistant, an internal research agent, a document-drafting agent, a support triage agent: none of those are in it.

There is also a separate right worth knowing about, because it is the one that actually forces per-decision explanation: someone significantly affected by a decision made on the basis of a high-risk system's output can ask for an explanation of that system's role. That is a different obligation from logging, and again it only bites for high-risk systems.

One more thing to check if you have been quoting dates: the timeline moved. High-risk obligations were deferred to 2 December 2027 for stand-alone systems, and to August 2028 for AI embedded in regulated products. Any article still citing August 2026 for high-risk is out of date.

So if you are out of scope, build the record for the questions you will actually be asked: a customer dispute, an incident review, an argument about a bill, a security investigation. Then let six months be a floor you happen to clear rather than a programme of work.

## The deletion request that arrives tomorrow

Now the collision. You want a record that lasts years. Somebody has the right to ask you to delete their data.

Four things make that survivable.

**A pseudonymous reference is not anonymity.** If a token can be linked back to a person using information you hold elsewhere, it is still personal data. Store the mapping separately, and do not tell yourself the trail is anonymous.

**Keeping everything forever is not the compliant answer.** The same sentence that sets a minimum also defers to data-protection law. Over-retention is its own problem, not a safe default.

**Delete the operational layer, keep the ledger.** Split what a deletion request can take (content and operational rows) from what has to survive (billing ledgers, security records), and make sure the surviving layer holds no payload and no direct identifiers.

**Watch for data that survives the delete.** The classic failure: large payloads live in file storage and the database row keeps only a pointer. Delete the row and the file stays behind, unreferenced and invisible to any later audit of what you hold. Make the file the deletion target and reconcile leftovers on a schedule.

One pattern worth building if you can: when content is erased, leave a tombstone holding the fingerprint and the size. A later reader can then tell that something was there, how big it was, and that it was removed under a rights request rather than lost.

## The mistake you cannot undo

Every other retention error is fixable. This one is not: **you cannot lengthen retention retroactively.**

The day you discover the window you needed was longer than your purge job, the data is already gone. The correction hurts in the other direction too: one team raising a lifecycle log from 30 days to a year hit a twelvefold backlog on the first purge afterwards.

So set the skeleton to the longest window you can plausibly imagine needing, on day one. At around 31 GB a year it is the cheapest insurance in the system. Then tune the content window, which is the part that is both expensive and reversible.

Two smaller ones in the same family. Check that your documented retention matches your configured retention: a comment saying "30 days" above a setting that defaults to a year is how the two silently diverge. And keep everyday queries off the detail rows, with day-level summaries for the common questions, or your record ends up technically complete and practically unusable.

## Questions people ask

### What is a reasonable default if I am not regulated?

Skeleton for a few years, content for three to six months. That covers disputes, incident reviews and billing arguments without holding a warehouse of personal data.

### Do I have to keep prompts and answers?

For as long as you might need to explain a specific decision, yes. After that the fingerprint carries the evidence and the text is just exposure.

### Does the six-month rule apply to my chatbot?

Almost certainly not. It applies to high-risk systems as the Act defines them, and ordinary internal or productivity agents are not on that list. Check the list rather than assuming either way.

### Where does the storage actually go?

Payloads. Tool results and prompts dominate, especially when they are duplicated across several places. The structured skeleton is a rounding error next to them.

### Can I just keep everything and decide later?

That is the option that feels safe and is not. Long-lived payload is a standing liability, and it is the first thing a deletion request will find.

## What to do next

Write down two numbers, one for the skeleton and one for the content, and make the skeleton one generous. Then confirm your record actually holds what those windows are meant to protect: [what to log for every AI agent run](/blog/ai-agent-audit-trail).
`;

export default content;
