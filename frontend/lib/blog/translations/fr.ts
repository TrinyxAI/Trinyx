import type { BlogTranslation } from '../i18n';
import theNicheDataAdvantage from '../content/fr/the-niche-data-advantage';
import chatToWorkflowNoCode from '../content/fr/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from '../content/fr/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from '../content/fr/workflow-beats-do-everything-agent';
import capAiAgentCostBudgets from '../content/fr/cap-ai-agent-cost-budgets';
import sizeAnAiAgentBudget from '../content/fr/size-an-ai-agent-budget';
import aiAgentAuditTrail from '../content/fr/ai-agent-audit-trail';
import aiAgentAuditLogRetention from '../content/fr/ai-agent-audit-log-retention';

export const frBlog: BlogTranslation = {
  ui: {
    eyebrow: "Notes de terrain",
    blogTitle: "Blog",
    lead: "Des guides pratiques sur l'automatisation IA : ce qu'elle coûte vraiment, quoi journaliser, et comment transformer un jeu de données en un workflow qui tourne tout seul.",
    latest: "Derniers articles",
    readThePost: "Lire l'article",
    readMore: "Lire la suite",
    allPosts: "Tous les articles",
    minRead: "min de lecture",
    by: "Par",
    and: "et",
    ctaTitle: "Transformez vos données de niche en une automatisation qui fonctionne",
    ctaText: "Décrivez la tâche en discutant et Trinyx construit le workflow sous vos yeux.",
    startFree: "Commencer gratuitement",
    metaTitle: "Blog - Trinyx",
    metaDescription: "Des guides pratiques sur l'automatisation IA et les données de niche : ce que coûtent vraiment les agents, quoi journaliser, et comment transformer un jeu de données en un workflow qui tourne tout seul.",
  },
  posts: {
    "the-niche-data-advantage": { title: "Données de niche : quand un petit jeu de données bat un grand", excerpt: "Posséder des données n'est pas une barrière à l'entrée. Les tenir à jour s'en rapproche. Comment distinguer un jeu de données de niche qui vaut le coup, en cinq questions.", coverAlt: "Un ordinateur portable affichant un tableau de bord analytique avec des graphiques, une carte et des indicateurs", content: theNicheDataAdvantage },
    "chat-to-workflow-no-code": { title: "Automatisation IA no-code : d'une phrase à un workflow qui tourne", excerpt: "Décrivez la tâche en langage courant et obtenez un workflow que vous pouvez voir, exécuter et modifier. Aucun nœud à câbler à la main, aucune boîte noire à croire sur parole.", coverAlt: "Une main tapant un message sur un téléphone affichant une conversation de chat", content: chatToWorkflowNoCode },
    "from-dataset-to-live-workflow": { title: "Transformer un jeu de données en workflow qui tourne tout seul", excerpt: "Six étapes pour passer d'un fichier vérifié à la main à une veille de prix qui se rafraîchit, décide et demande avant d'agir. Plus les quatre pièges qui échouent en silence.", coverAlt: "Une main dessinant un diagramme de workflow fait de boîtes reliées et de flèches sur un tableau blanc", content: fromDatasetToLiveWorkflow },
    "workflow-beats-do-everything-agent": { title: "Workflow IA ou agent IA : ce que chacun coûte vraiment", excerpt: "Sur un ticket de support, un agent revient à environ 19 centimes et un workflow découpé à environ 2. Pourquoi l'écart existe, quand il se réduit, et quand l'agent est le bon choix.", coverAlt: "Un unique bras robotique sur un socle, représentant un agent autonome", content: workflowBeatsDoEverythingAgent },
    "cap-ai-agent-cost-budgets": { title: "Comment empêcher un agent IA de trop dépenser", excerpt: "Une alerte n'est pas une limite, et la plupart des plafonds fournisseur n'envoient qu'une notification. À quoi ressemble un vrai plafond, et comment prouver que le vôtre refuserait un appel.", coverAlt: "Des pièces éparpillées sur un bureau à côté d'un carnet et d'un stylo pour la budgétisation", content: capAiAgentCostBudgets },
    "size-an-ai-agent-budget": { title: "Quel budget accorder à un agent IA ?", excerpt: "Ce que coûte réellement une étape, quelle marge ajouter, et pourquoi plafonner les boucles plafonne mal l'argent. Une méthode concrète pour arriver à un chiffre défendable.", coverAlt: "Des mains utilisant une calculatrice à côté de graphiques imprimés lors d'une analyse de données", content: sizeAnAiAgentBudget },
    "ai-agent-audit-trail": { title: "Quoi journaliser pour chaque exécution d'agent IA", excerpt: "Votre tableau de bord n'est pas une piste d'audit, et le traçage IA standard ne stocke aucun prompt par défaut. Quoi enregistrer par exécution et par étape pour pouvoir en répondre.", coverAlt: "Une loupe et une calculatrice posées sur des documents imprimés", content: aiAgentAuditTrail },
    "ai-agent-audit-log-retention": { title: "Combien de temps garder les journaux d'un agent IA ?", excerpt: "Gardez le petit squelette des années et le contenu volumineux des mois. Ce que le règlement européen sur l'IA exige vraiment, et pourquoi la plupart des agents sont hors périmètre.", coverAlt: "Une main dessinant un diagramme de workflow fait de boîtes reliées et de flèches sur un tableau blanc", content: aiAgentAuditLogRetention },
  },
};
