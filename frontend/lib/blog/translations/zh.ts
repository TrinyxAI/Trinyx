import type { BlogTranslation } from '../i18n';
import theNicheDataAdvantage from '../content/zh/the-niche-data-advantage';
import chatToWorkflowNoCode from '../content/zh/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from '../content/zh/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from '../content/zh/workflow-beats-do-everything-agent';
import capAiAgentCostBudgets from '../content/zh/cap-ai-agent-cost-budgets';
import sizeAnAiAgentBudget from '../content/zh/size-an-ai-agent-budget';
import aiAgentAuditTrail from '../content/zh/ai-agent-audit-trail';
import aiAgentAuditLogRetention from '../content/zh/ai-agent-audit-log-retention';

export const zhBlog: BlogTranslation = {
  ui: {
    eyebrow: "实战手记", blogTitle: "Blog", lead: "AI 自动化的实用指南：智能体到底要花多少钱、该记录些什么，以及如何把一个数据集变成能自行运行的工作流。", latest: "最新", readThePost: "阅读文章", readMore: "阅读更多", allPosts: "全部文章", minRead: "分钟阅读", by: "作者", and: "和", ctaTitle: "把你的利基数据变成一个能用的自动化", ctaText: "在聊天里描述这份任务，Trinyx 就在你眼前构建出工作流。", startFree: "免费开始", metaTitle: "Blog - Trinyx", metaDescription: "AI 自动化与利基数据的实用指南：AI 智能体到底要花多少钱、每次运行该记录些什么，以及如何把一个数据集变成能自行运行的工作流。",
  },
  posts: {
    "the-niche-data-advantage": { title: "利基数据：小数据集什么时候能胜过大数据集", excerpt: "拥有数据本身不是护城河，把它保持最新才更接近。用五个问题分辨哪份利基数据集值得投入。", coverAlt: "一台笔记本电脑展示着带有图表、地图和指标的分析仪表盘", content: theNicheDataAdvantage },
    "chat-to-workflow-no-code": { title: "无代码 AI 自动化：从一句话到可运行的工作流", excerpt: "用日常语言描述任务，得到一个可以看见、运行和修改的工作流。不用手工连节点，也不必盲信一个黑盒。", coverAlt: "一只手在手机上输入消息，屏幕上显示着一段聊天对话", content: chatToWorkflowNoCode },
    "from-dataset-to-live-workflow": { title: "如何把数据集变成自行运行的工作流", excerpt: "六个步骤，从手动核对的文件到会自动刷新、判断并在行动前征询的价格监控。外加四个静默失败的陷阱。", coverAlt: "一只手在白板上画出由方块和箭头连成的工作流示意图", content: fromDatasetToLiveWorkflow },
    "workflow-beats-do-everything-agent": { title: "AI 工作流还是 AI 智能体：各自到底花多少钱", excerpt: "一个客服工单上，单个智能体约 19 美分，拆分后的工作流约 2 美分。差距为何存在、何时缩小，以及什么时候该选智能体。", coverAlt: "一个立在支架上的单臂机械臂，代表一个自主智能体", content: workflowBeatsDoEverythingAgent },
    "cap-ai-agent-cost-budgets": { title: "如何避免 AI 智能体超支", excerpt: "告警不是限制，多数服务商的支出上限只发一条通知。真正的天花板长什么样，以及如何证明你的上限会拒绝一次调用。", coverAlt: "散落在桌面上的硬币，旁边有一本笔记本和一支笔用于做预算", content: capAiAgentCostBudgets },
    "size-an-ai-agent-budget": { title: "一个 AI 智能体该给多少预算？", excerpt: "一步到底花多少钱、该加多少余量，以及为什么限制迭代次数控不住钱。一套得出可辩护数字的实用方法。", coverAlt: "双手在打印的图表旁使用计算器分析数据", content: sizeAnAiAgentBudget },
    "ai-agent-audit-trail": { title: "每次 AI 智能体运行该记录什么", excerpt: "你的面板不是审计记录，标准 AI 追踪默认不保存提示词。按运行和按步骤该记下什么，才能在事后说得清楚。", coverAlt: "一只放大镜和一个计算器搁在打印的文件上", content: aiAgentAuditTrail },
    "ai-agent-audit-log-retention": { title: "AI 智能体日志该保留多久？", excerpt: "小小的骨架保留数年，庞大的内容保留数月。欧盟《人工智能法案》到底要求什么，以及为什么多数智能体根本不在范围内。", coverAlt: "一只手在白板上画出由方块和箭头连成的工作流示意图", content: aiAgentAuditLogRetention },
  },
};
