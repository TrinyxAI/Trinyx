import type { BlogTranslation } from '../i18n';
import theNicheDataAdvantage from '../content/de/the-niche-data-advantage';
import chatToWorkflowNoCode from '../content/de/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from '../content/de/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from '../content/de/workflow-beats-do-everything-agent';
import capAiAgentCostBudgets from '../content/de/cap-ai-agent-cost-budgets';
import sizeAnAiAgentBudget from '../content/de/size-an-ai-agent-budget';
import aiAgentAuditTrail from '../content/de/ai-agent-audit-trail';
import aiAgentAuditLogRetention from '../content/de/ai-agent-audit-log-retention';

export const deBlog: BlogTranslation = {
  ui: {
    eyebrow: "Notizen aus der Praxis", blogTitle: "Blog", lead: "Praktische Leitfäden zur KI-Automatisierung: was Agenten wirklich kosten, was zu protokollieren ist und wie aus einem Datensatz ein Workflow wird, der sich selbst betreibt.", latest: "Neueste", readThePost: "Beitrag lesen", readMore: "Weiterlesen", allPosts: "Alle Beiträge", minRead: "Min. Lesezeit", by: "Von", and: "und", ctaTitle: "Verwandle deine Nischendaten in eine funktionierende Automatisierung", ctaText: "Beschreibe die Aufgabe im Chat, und LiveContext baut den Workflow vor deinen Augen.", startFree: "Kostenlos starten", metaTitle: "Blog - LiveContext", metaDescription: "Praktische Leitfäden zu KI-Automatisierung und Nischendaten: was KI-Agenten wirklich kosten, was pro Lauf zu protokollieren ist und wie aus einem Datensatz ein Workflow wird, der sich selbst betreibt.",
  },
  posts: {
    "the-niche-data-advantage": { title: "Nischendaten: wann ein kleiner Datensatz einen großen schlägt", excerpt: "Daten zu besitzen ist kein Burggraben. Sie aktuell zu halten kommt dem näher. Wie Sie in fünf Fragen erkennen, welcher Nischendatensatz sich lohnt.", coverAlt: "Ein Laptop zeigt ein Analyse-Dashboard mit Diagrammen, einer Karte und Kennzahlen", content: theNicheDataAdvantage },
    "chat-to-workflow-no-code": { title: "No-Code-KI-Automatisierung: von einem Satz zum laufenden Workflow", excerpt: "Beschreiben Sie die Aufgabe in Alltagssprache und erhalten Sie einen Workflow, den Sie sehen, ausführen und ändern können. Keine Nodes von Hand verkabeln, keine Blackbox.", coverAlt: "Eine Hand tippt eine Nachricht auf einem Telefon, das eine Chat-Unterhaltung zeigt", content: chatToWorkflowNoCode },
    "from-dataset-to-live-workflow": { title: "So wird aus einem Datensatz ein Workflow, der sich selbst ausführt", excerpt: "Sechs Schritte von der handgeprüften Datei zur Preisüberwachung, die sich aktualisiert, entscheidet und vor dem Handeln nachfragt. Plus die vier Fallen, die still scheitern.", coverAlt: "Eine Hand zeichnet ein Workflow-Diagramm aus verbundenen Kästen und Pfeilen auf einem Whiteboard", content: fromDatasetToLiveWorkflow },
    "workflow-beats-do-everything-agent": { title: "KI-Workflow oder KI-Agent: was jeder wirklich kostet", excerpt: "Bei einem Support-Ticket kostet ein Agent rund 19 Cent, ein aufgeteilter Workflow rund 2. Warum der Abstand entsteht, wann er schrumpft und wann der Agent richtig ist.", coverAlt: "Ein einzelner Roboterarm auf einem Ständer, der einen autonomen Agenten darstellt", content: workflowBeatsDoEverythingAgent },
    "cap-ai-agent-cost-budgets": { title: "So verhindern Sie, dass ein KI-Agent zu viel ausgibt", excerpt: "Ein Alarm ist kein Limit, und die meisten Ausgabendeckel der Anbieter senden nur eine Benachrichtigung. Wie eine echte Decke aussieht und wie Sie Ihre prüfen.", coverAlt: "Münzen verstreut auf einem Schreibtisch neben einem Notizbuch und Stift für die Budgetplanung", content: capAiAgentCostBudgets },
    "size-an-ai-agent-budget": { title: "Wie viel Budget braucht ein KI-Agent?", excerpt: "Was ein Schritt wirklich kostet, wie viel Puffer nötig ist und warum ein Iterationslimit Geld schlecht begrenzt. Ein praktischer Weg zu einer belastbaren Zahl.", coverAlt: "Hände nutzen einen Taschenrechner neben gedruckten Diagrammen bei der Datenanalyse", content: sizeAnAiAgentBudget },
    "ai-agent-audit-trail": { title: "Was Sie bei jedem KI-Agentenlauf protokollieren sollten", excerpt: "Ihr Dashboard ist kein Prüfprotokoll, und Standard-Tracing speichert keine Prompts. Was Sie je Lauf und je Schritt festhalten, um später Rede und Antwort zu stehen.", coverAlt: "Eine Lupe und ein Taschenrechner liegen auf gedruckten Dokumenten", content: aiAgentAuditTrail },
    "ai-agent-audit-log-retention": { title: "Wie lange sollten Sie KI-Agenten-Protokolle aufbewahren?", excerpt: "Das kleine Skelett jahrelang, den umfangreichen Inhalt monatelang. Was die EU-KI-Verordnung wirklich verlangt und warum die meisten Agenten gar nicht betroffen sind.", coverAlt: "Eine Hand zeichnet ein Workflow-Diagramm aus verbundenen Kästen und Pfeilen auf einem Whiteboard", content: aiAgentAuditLogRetention },
  },
};
