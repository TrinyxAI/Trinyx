// Markdown body for "Niche data: when a small dataset actually beats a big one".
// Plain string module (see posts.ts for why the bodies are string modules).
//
// PUBLIC register (rewritten 2026-07-24): written for a founder, ops lead or
// marketer, not a researcher. Scannable, one idea per section, a table or a
// screenshot every screen or two, and a short FAQ at the end for search.
// The evidence is unchanged, only the delivery: the sceptics still go first,
// the worked numbers are still flagged as assumptions, and nothing is claimed
// that the research did not support. Do NOT re-introduce the citation wall,
// the hazard-model formulas or the Chapman estimator; the long-form versions of
// those arguments were deliberately cut, not lost.
const content = `A small dataset you keep current can beat a huge generic one. It can also quietly cost you more than it ever returns. The difference is not the number of rows. It is how fast your data goes wrong, and whether anyone acts on it.

Here is how to tell the two apart before you spend a quarter building the wrong one.

## The short version

- Owning data is not a moat by itself. Keeping it fresh, faster than anyone else bothers to, is closer to one.
- The number that decides everything is how much of your data goes wrong each year. Measure it before you buy anything.
- Data that nobody acts on is a cost, no matter how good it is.
- Small wins when the set is bounded, current, and tied to a decision somebody makes this week.
- Doing nothing is a real option, and below a certain volume it beats both building and buying.

## Start with the case against

The "proprietary data is our moat" story is weaker than it sounds, and the sceptics have the better evidence.

Andreessen Horowitz looked at data network effects and found most of them are really data *scale* effects, which flatten out. In their support-chatbot example, past roughly 40% of queries collected, more data added no advantage at all ([The Empty Promise of Data Moats](https://a16z.com/the-empty-promise-of-data-moats/)).

Bigger and more specialised does not automatically win either. BloombergGPT was trained on 363 billion words of proprietary financial text, and a general model still beat it on the finance benchmarks it was built for. IBM spent years and roughly $4B assembling health data for Watson Health, then sold the assets. Zillow shut its home-buying arm after a $422M quarterly loss in the segment.

| What the evidence says | What it does not settle |
|---|---|
| Data is rarely rare or impossible to copy | Whether *your* first-party records have a substitute |
| More data helps less and less as you add it | Datasets whose value is freshness, not size |
| Generic models beat domain-specific ones on many tasks | Structured lookups, where the data is the answer |

Almost all of that research is about training large models. You are probably not training anything. You are feeding a few thousand rows to an agent, which is a different situation nobody has measured well. That cuts both ways: the case against you is weaker than it looks, and so is the case for you.

## The one number that decides everything

Ask how much of your data becomes wrong over a year. Prices move, people change jobs, listings disappear, rules get amended.

Measure it, do not guess it. Take a sample of records, check them again a few weeks later against something you trust, and count how many changed. That single number tells you three things at once: how often you have to refresh, what the refresh will cost, and how long a competitor's stolen copy of your file stays useful.

| If this much goes wrong per year | Refresh roughly every | A stolen copy stays useful for |
|---|---|---|
| 5% | 12 months | over 13 years |
| 10% | 6 months | about 6 years |
| 30% | 8 weeks | under 2 years |
| 60% | 3 weeks | about 9 months |

Read the last column carefully, because it is the part people get backwards. Slow-moving data is cheap to maintain and trivial to copy. Fast-moving data is expensive to maintain and hard to copy. "Find data that is cheap to keep" and "find data that is defensible" are opposite instructions, and most teams are handed both.

There is one honest caveat on the whole table: the refresh cadence assumes your data ages steadily. Web sources tend to rot fastest in the first year, so refresh sooner than the table suggests on anything you do not control.

![A LiveContext table holding a small niche dataset: six tracked competitor SKUs, each a row with columns for sku, price, title, currency and a last-seen timestamp.](/blog/the-niche-data-advantage-dataset.png)

*A qualified niche dataset is small enough to read row by row. Six tracked products, one price each, and a last-seen timestamp so you can measure how fast it goes stale.*

## Five questions before you invest

Run these in a week. If a candidate source fails question 2 or 4, stop there.

| Question | How to test it | Pass mark |
|---|---|---|
| 1. Can you list all of it? | Collect the same set twice, by two different routes, and see how much overlaps | You can name what is missing |
| 2. Can you check a record is right? | Name the independent source you would check against, and time yourself on ten records | Under ten minutes a record |
| 3. Can you afford the refresh? | Change rate times cost per check, against what the decision is worth per year | Under 15% of the value it drives |
| 4. Does anyone act on it? | Name the decision, who makes it, and how often the data would change their mind | It changes the call at least 1 time in 50 |
| 5. Could a competitor rebuild it? | Price the copy in days of skilled work | Months, not days |

Question 4 kills most candidates, and it is the one people skip. A dataset that never changes anybody's decision is not an asset, it is a subscription.

## Build, buy, or do nothing

Most comparisons pit building against buying and forget the third option. Doing nothing has real value: you keep deciding the way you already do, at zero cost.

Whether building pays comes down to volume. Take an illustrative case: a 4,000-row set, about $30,000 to build, roughly $11,000 a year to keep current, and $60 of value per decision it improves. Those are worked assumptions, not measurements, but the shape they produce is the useful part.

| Decisions per year | Best move |
|---|---|
| Under about 900 | Do nothing |
| About 900 to 1,300 | Build, if you are confident in your numbers |
| Over about 1,300 | Build |

Move any input and the crossover moves with it. The lesson is not the specific number, it is that a low-volume decision almost never repays a dataset, however good the dataset is.

Buying looks best in one specific case: when a vendor is nearly as accurate as you would be on your own narrow slice. Test that before you sign. Sample 200 of their records inside your niche and check them yourself.

## Where niche data really does win

Four situations survive every objection above.

- **You record a decision only you make.** The outcome column cannot be scraped. It has to be earned, one decision at a time.
- **You observe events nobody else can join up.** Others may see the event. Only you hold it joined to your context and your result.
- **The data changes fast and you treat it as a running cost.** Nobody can steal a moving target once. They have to fund the same refresh, forever.
- **The set is small enough to check completely.** At a few thousand rows you can verify everything. At a few hundred thousand, nobody buys that bill.

And where it does not: a vendor already sells it as a product, the data barely changes and is public, the decision volume is too low, or the job is really reasoning rather than lookup.

## Questions people ask

### How much data do I actually need?

Fewer rows than you think, and more freshness than you think. A hundred current, verified rows beat a million stale ones only when the current ones cover the exact decision you are making. Coverage of the decision matters more than row count.

### Is buying a dataset ever the right call?

Yes, when the vendor is close to your own accuracy on your slice and your decision volume sits in the middle band. Buy the bulk that anyone can copy, and build only the column nobody else can produce.

### How do I stop a dataset from quietly going stale?

Put a last-checked timestamp on every row and refresh oldest-first. Random refreshing leaves a tail of very old rows no matter how much you spend, and those are the ones that will embarrass you.

### What is the most common mistake?

Collecting first and finding the decision later. If you cannot name who acts on the data and how often, the answer is not more data.

## What to do next

Spend a week on it. Measure how fast your data goes wrong, run the five questions, and check whether anyone actually changes a decision because of it. If the source qualifies, the next step is wiring it into something that runs itself: [from dataset to live workflow](/blog/from-dataset-to-live-workflow) walks through that build.
`;

export default content;
