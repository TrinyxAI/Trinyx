// Blog post registry for the public marketing blog (`app/blog`).
//
// Each post body is a Markdown string module under `./content/<slug>.ts`;
// metadata lives in this typed index next to its import. Adding a post is: drop
// a `content/<slug>.ts` file, add its import + one `BLOG_POSTS` entry. Plain
// static imports (no bundler loader, no `.md` file) so they resolve identically
// under Turbopack, webpack and vitest, and are traced into the standalone build
// with no runtime filesystem read.
//
// The pure helpers live in `postUtils.ts` (unit-tested there); this module only
// holds the data and the content imports.

import type { BlogPost } from './postUtils';
import { sortPostsByDateDesc, findPostBySlug } from './postUtils';
import theNicheDataAdvantage from './content/the-niche-data-advantage';
import chatToWorkflowNoCode from './content/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from './content/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from './content/workflow-beats-do-everything-agent';
import capAiAgentCostBudgets from './content/cap-ai-agent-cost-budgets';
import sizeAnAiAgentBudget from './content/size-an-ai-agent-budget';
import aiAgentAuditTrail from './content/ai-agent-audit-trail';
import aiAgentAuditLogRetention from './content/ai-agent-audit-log-retention';

export type { BlogPost } from './postUtils';
export { estimateReadingMinutes, formatBlogDate, formatAuthors } from './postUtils';

// Authoring order here does not matter (getAllPosts sorts newest first). Keep a
// `slug` stable once published, it is the permalink.
const BLOG_POSTS: BlogPost[] = [
  {
    slug: 'the-niche-data-advantage',
    title: 'Niche data: when a small dataset beats a big one',
    date: '2026-07-07',
    excerpt:
      'Owning data is not a moat. Keeping it current is closer to one. How to tell a niche dataset worth building from an expensive one, in five questions.',
    authors: ['theo p.', 'noah_schmidt'],
    tags: ['niche data', 'strategy'],
    cover: '/blog/the-niche-data-advantage.jpg',
    coverAlt: 'A laptop showing an analytics dashboard with charts, a map and metrics',
    content: theNicheDataAdvantage,
  },
  {
    slug: 'chat-to-workflow-no-code',
    title: 'No-code AI automation: from one sentence to a working workflow',
    date: '2026-07-05',
    excerpt:
      'Describe the job in plain language and get a workflow you can see, run and change. No nodes to wire by hand, and no black box to trust blindly.',
    authors: ['Sophie M.', 'Emma R.'],
    tags: ['no-code', 'automation'],
    cover: '/blog/chat-to-workflow-no-code.jpg',
    coverAlt: 'A hand typing a message on a phone showing a chat conversation',
    content: chatToWorkflowNoCode,
  },
  {
    slug: 'from-dataset-to-live-workflow',
    title: 'How to turn a dataset into a workflow that runs itself',
    date: '2026-07-03',
    excerpt:
      'Six steps from a file you check by hand to a price watch that refreshes, decides and asks before it acts. Plus the four traps that fail silently.',
    authors: ['Camille R.', 'noah_schmidt'],
    tags: ['workflows', 'how-to'],
    cover: '/blog/from-dataset-to-live-workflow.jpg',
    coverAlt: 'A hand drawing a workflow diagram of connected boxes and arrows on a whiteboard',
    content: fromDatasetToLiveWorkflow,
  },
  {
    slug: 'workflow-beats-do-everything-agent',
    title: 'AI workflow vs AI agent: what each one really costs',
    date: '2026-07-01',
    excerpt:
      'On a support ticket, one agent runs about 19 cents and a split-up workflow about 2. Why the gap exists, when it shrinks, and when the agent is the right call.',
    authors: ['theo p.', 'nora_a'],
    tags: ['ai agents', 'cost'],
    cover: '/blog/workflow-beats-do-everything-agent.jpg',
    coverAlt: 'A single robotic arm on a stand, representing an autonomous agent',
    content: workflowBeatsDoEverythingAgent,
  },
  {
    slug: 'cap-ai-agent-cost-budgets',
    title: 'How to stop an AI agent from overspending',
    date: '2026-06-24',
    excerpt:
      'An alert is not a limit, and most provider spend caps only send a notification. What a real ceiling looks like, and how to prove yours would refuse a call.',
    authors: ['theo p.', 'ines_l'],
    tags: ['ai agents', 'cost'],
    cover: '/blog/cap-ai-agent-cost-budgets.jpg',
    coverAlt: 'Coins scattered on a desk beside a notebook and pen for budgeting',
    content: capAiAgentCostBudgets,
  },
  {
    slug: 'size-an-ai-agent-budget',
    date: '2026-06-22',
    title: 'How much should you budget for one AI agent?',
    excerpt:
      'What a step actually costs, how much margin to add, and why capping loops is a poor way to cap money. A practical way to get to a number you can defend.',
    authors: ['ines_l', 'nora_a'],
    tags: ['ai agents', 'cost'],
    // Temporary: reuses the calculator cover currently on small-data-sharp-decisions,
    // which this series retires. Swap for a dedicated image when one exists.
    cover: '/blog/small-data-sharp-decisions.jpg',
    coverAlt: 'Hands using a calculator next to printed charts while analyzing data',
    content: sizeAnAiAgentBudget,
  },
  {
    slug: 'ai-agent-audit-trail',
    title: 'What to log for every AI agent run',
    date: '2026-06-20',
    excerpt:
      'Your dashboard is not an audit trail, and standard AI tracing stores no prompts by default. What to record per run and per step so you can answer for it later.',
    authors: ['ines_l', 'noah_schmidt'],
    tags: ['ai agents', 'governance'],
    cover: '/blog/ai-agent-audit-trail.jpg',
    coverAlt: 'A magnifying glass and calculator resting on printed documents',
    content: aiAgentAuditTrail,
  },
  {
    slug: 'ai-agent-audit-log-retention',
    date: '2026-06-18',
    title: 'How long should you keep AI agent logs?',
    excerpt:
      'Keep the small skeleton for years and the bulky content for months. What the EU AI Act really requires, and why most agents are out of scope entirely.',
    authors: ['ines_l', 'nora_a'],
    tags: ['ai agents', 'governance'],
    // Temporary: reuses the from-dataset cover (a whiteboard diagram) until a
    // dedicated image exists. Distinct from the magnifying-glass audit-trail cover.
    cover: '/blog/from-dataset-to-live-workflow.jpg',
    coverAlt: 'A hand drawing a workflow diagram of connected boxes and arrows on a whiteboard',
    content: aiAgentAuditLogRetention,
  },
];

/** All posts, newest first. */
export function getAllPosts(): BlogPost[] {
  return sortPostsByDateDesc(BLOG_POSTS);
}

/** The post for a slug, or `undefined` when no post matches. */
export function getPostBySlug(slug: string): BlogPost | undefined {
  return findPostBySlug(BLOG_POSTS, slug);
}
