import type { BlogTranslation } from '../i18n';
import theNicheDataAdvantage from '../content/es/the-niche-data-advantage';
import chatToWorkflowNoCode from '../content/es/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from '../content/es/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from '../content/es/workflow-beats-do-everything-agent';
import capAiAgentCostBudgets from '../content/es/cap-ai-agent-cost-budgets';
import sizeAnAiAgentBudget from '../content/es/size-an-ai-agent-budget';
import aiAgentAuditTrail from '../content/es/ai-agent-audit-trail';
import aiAgentAuditLogRetention from '../content/es/ai-agent-audit-log-retention';

export const esBlog: BlogTranslation = {
  ui: {
    eyebrow: "Notas de campo", blogTitle: "Blog", lead: "Guías prácticas de automatización con IA: lo que cuestan de verdad los agentes, qué registrar y cómo convertir un conjunto de datos en un flujo de trabajo que corre por sí solo.", latest: "Lo último", readThePost: "Leer el artículo", readMore: "Leer más", allPosts: "Todos los artículos", minRead: "min de lectura", by: "Por", and: "y", ctaTitle: "Convierte tus datos de nicho en una automatización que funciona", ctaText: "Describe la tarea en el chat y Trinyx construye el flujo de trabajo ante tus ojos.", startFree: "Empieza gratis", metaTitle: "Blog - Trinyx", metaDescription: "Guías prácticas de automatización con IA y datos de nicho: lo que cuestan de verdad los agentes, qué registrar en cada ejecución y cómo convertir un conjunto de datos en un flujo de trabajo que corre por sí solo.",
  },
  posts: {
    "the-niche-data-advantage": { title: "Datos de nicho: cuándo un conjunto pequeño gana a uno grande", excerpt: "Tener datos no es una ventaja defendible. Mantenerlos al día se acerca más. Cómo distinguir un conjunto de datos de nicho que merece la pena, en cinco preguntas.", coverAlt: "Un portátil que muestra un panel de análisis con gráficos, un mapa y métricas", content: theNicheDataAdvantage },
    "chat-to-workflow-no-code": { title: "Automatización con IA sin código: de una frase a un flujo que funciona", excerpt: "Describe la tarea en lenguaje sencillo y obtén un flujo de trabajo que puedes ver, ejecutar y cambiar. Sin nodos que conectar a mano y sin una caja negra en la que confiar a ciegas.", coverAlt: "Una mano escribiendo un mensaje en un teléfono que muestra una conversación de chat", content: chatToWorkflowNoCode },
    "from-dataset-to-live-workflow": { title: "Cómo convertir un conjunto de datos en un flujo que se ejecuta solo", excerpt: "Seis pasos para pasar de un fichero que revisas a mano a una vigilancia de precios que se actualiza, decide y pregunta antes de actuar. Más las cuatro trampas que fallan en silencio.", coverAlt: "Una mano dibujando en una pizarra un diagrama de flujo de trabajo con cajas conectadas y flechas", content: fromDatasetToLiveWorkflow },
    "workflow-beats-do-everything-agent": { title: "Flujo de trabajo con IA o agente de IA: qué cuesta cada uno", excerpt: "En un ticket de soporte, un agente cuesta unos 19 céntimos y un flujo dividido unos 2. Por qué existe la diferencia, cuándo se reduce y cuándo el agente es la opción correcta.", coverAlt: "Un único brazo robótico sobre un soporte, que representa un agente autónomo", content: workflowBeatsDoEverythingAgent },
    "cap-ai-agent-cost-budgets": { title: "Cómo evitar que un agente de IA gaste de más", excerpt: "Una alerta no es un límite, y la mayoría de los topes de gasto de los proveedores solo envían un aviso. Cómo es un techo de verdad y cómo demostrar que el tuyo rechazaría una llamada.", coverAlt: "Monedas esparcidas sobre un escritorio junto a un cuaderno y un bolígrafo para hacer presupuestos", content: capAiAgentCostBudgets },
    "size-an-ai-agent-budget": { title: "¿Cuánto presupuesto darle a un agente de IA?", excerpt: "Lo que cuesta realmente un paso, cuánto margen añadir y por qué limitar iteraciones limita mal el dinero. Una forma práctica de llegar a una cifra defendible.", coverAlt: "Manos usando una calculadora junto a gráficos impresos mientras analizan datos", content: sizeAnAiAgentBudget },
    "ai-agent-audit-trail": { title: "Qué registrar en cada ejecución de un agente de IA", excerpt: "Tu panel no es un registro de auditoría, y el trazado estándar de IA no guarda prompts por defecto. Qué anotar por ejecución y por paso para poder responder después.", coverAlt: "Una lupa y una calculadora apoyadas sobre documentos impresos", content: aiAgentAuditTrail },
    "ai-agent-audit-log-retention": { title: "¿Cuánto tiempo conservar los registros de un agente de IA?", excerpt: "Guarda el esqueleto pequeño durante años y el contenido voluminoso durante meses. Qué exige de verdad el reglamento europeo de IA y por qué casi ningún agente está afectado.", coverAlt: "Una mano dibujando en una pizarra un diagrama de flujo de trabajo con cajas conectadas y flechas", content: aiAgentAuditLogRetention },
  },
};
