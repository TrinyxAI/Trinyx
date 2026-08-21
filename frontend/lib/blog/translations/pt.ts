import type { BlogTranslation } from '../i18n';
import theNicheDataAdvantage from '../content/pt/the-niche-data-advantage';
import chatToWorkflowNoCode from '../content/pt/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from '../content/pt/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from '../content/pt/workflow-beats-do-everything-agent';
import capAiAgentCostBudgets from '../content/pt/cap-ai-agent-cost-budgets';
import sizeAnAiAgentBudget from '../content/pt/size-an-ai-agent-budget';
import aiAgentAuditTrail from '../content/pt/ai-agent-audit-trail';
import aiAgentAuditLogRetention from '../content/pt/ai-agent-audit-log-retention';

export const ptBlog: BlogTranslation = {
  ui: {
    eyebrow: "Notas de terreno", blogTitle: "Blog", lead: "Guias práticos de automatização com IA: quanto custam mesmo os agentes, o que registar, e como transformar um conjunto de dados num workflow que se executa a si próprio.", latest: "Mais recente", readThePost: "Ler o artigo", readMore: "Ler mais", allPosts: "Todos os artigos", minRead: "min de leitura", by: "Por", and: "e", ctaTitle: "Transforme os seus dados de nicho numa automatização a funcionar", ctaText: "Descreva a tarefa no chat e o Trinyx constrói o workflow à sua frente.", startFree: "Comece grátis", metaTitle: "Blog - Trinyx", metaDescription: "Guias práticos de automatização com IA e dados de nicho: quanto custam mesmo os agentes de IA, o que registar em cada execução, e como transformar um conjunto de dados num workflow que se executa a si próprio.",
  },
  posts: {
    "the-niche-data-advantage": { title: "Dados de nicho: quando um conjunto pequeno vence um grande", excerpt: "Ter dados não é uma vantagem defensável. Mantê-los atualizados aproxima-se disso. Como distinguir, em cinco perguntas, um conjunto de dados de nicho que vale a pena.", coverAlt: "Um portátil a mostrar um painel de análise com gráficos, um mapa e métricas", content: theNicheDataAdvantage },
    "chat-to-workflow-no-code": { title: "Automatização com IA sem código: de uma frase a um workflow a funcionar", excerpt: "Descreva a tarefa em linguagem corrente e obtenha um workflow que pode ver, executar e alterar. Sem nós para ligar à mão e sem uma caixa negra em que confiar às cegas.", coverAlt: "Uma mão a escrever uma mensagem num telemóvel que mostra uma conversa de chat", content: chatToWorkflowNoCode },
    "from-dataset-to-live-workflow": { title: "Como transformar um conjunto de dados num workflow que corre sozinho", excerpt: "Seis passos de um ficheiro verificado à mão até uma vigilância de preços que se atualiza, decide e pergunta antes de agir. Mais as quatro armadilhas que falham em silêncio.", coverAlt: "Uma mão a desenhar num quadro branco um diagrama de workflow com caixas ligadas e setas", content: fromDatasetToLiveWorkflow },
    "workflow-beats-do-everything-agent": { title: "Workflow de IA ou agente de IA: quanto custa cada um", excerpt: "Num pedido de suporte, um agente custa cerca de 19 cêntimos e um workflow dividido cerca de 2. Porque existe a diferença, quando encolhe, e quando o agente é a escolha certa.", coverAlt: "Um único braço robótico num suporte, a representar um agente autónomo", content: workflowBeatsDoEverythingAgent },
    "cap-ai-agent-cost-budgets": { title: "Como evitar que um agente de IA gaste demais", excerpt: "Um alerta não é um limite, e a maioria dos tetos de despesa dos fornecedores só envia um aviso. Como é um teto a sério e como provar que o seu recusaria uma chamada.", coverAlt: "Moedas espalhadas numa secretária ao lado de um caderno e uma caneta para orçamentar", content: capAiAgentCostBudgets },
    "size-an-ai-agent-budget": { title: "Que orçamento dar a um agente de IA?", excerpt: "Quanto custa mesmo um passo, que margem acrescentar, e porque limitar iterações limita mal o dinheiro. Uma forma prática de chegar a um número defensável.", coverAlt: "Mãos a usar uma calculadora ao lado de gráficos impressos enquanto analisam dados", content: sizeAnAiAgentBudget },
    "ai-agent-audit-trail": { title: "O que registar em cada execução de um agente de IA", excerpt: "O seu painel não é um registo de auditoria, e o rastreio padrão de IA não guarda prompts. O que anotar por execução e por passo para poder responder mais tarde.", coverAlt: "Uma lupa e uma calculadora pousadas sobre documentos impressos", content: aiAgentAuditTrail },
    "ai-agent-audit-log-retention": { title: "Durante quanto tempo guardar os registos de um agente de IA?", excerpt: "Guarde o esqueleto pequeno durante anos e o conteúdo volumoso durante meses. O que o regulamento europeu de IA exige mesmo e porque quase nenhum agente está abrangido.", coverAlt: "Uma mão a desenhar num quadro branco um diagrama de workflow com caixas ligadas e setas", content: aiAgentAuditLogRetention },
  },
};
