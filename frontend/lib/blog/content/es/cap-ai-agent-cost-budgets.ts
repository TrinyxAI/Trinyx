// Spanish translation of cap-ai-agent-cost-budgets (public register, 2026-07-24).
const content = `Casi todas las sorpresas de factura con IA tienen la misma causa: un agente sin techo. Dio vueltas, reintentó, arrastró una conversación cada vez mayor, y nadie se enteró hasta que llegó la factura.

El remedio no es un modelo mejor ni un prompt más fino. Es un límite que rechace la siguiente llamada, y la mayoría de las cosas que la gente llama presupuesto no hacen eso.

## En resumen

- Una alerta te dice lo que ya has gastado. No es un límite.
- El límite de gasto de tu proveedor suele ser un aviso, no una parada en seco.
- Ningún presupuesto puede detener la llamada que ya está en curso. El peor caso real es tu presupuesto más una llamada.
- La mayoría de los frameworks de agentes no traen ningún límite de coste, o traen uno que cuenta llamadas en vez de dinero.
- La prueba que importa: ¿ha rechazado algo alguna vez tu límite?

## Una alerta no es un límite

Un monitor actúa cuando el dinero ya se ha ido. Un límite actúa antes de la siguiente llamada y dice que no. Ambos sirven, pero solo uno es un control.

| | Un monitor | Un límite de verdad |
|---|---|---|
| Cuándo actúa | Después de cerrarse la llamada | Antes de empezar la siguiente |
| Qué puede hacer | Avisarte | Rechazar |
| Peor caso | Ilimitado | Una llamada más |
| Para qué sirve | Dimensionar el límite, detectar desvíos | Detener la ejecución |

Aquí tienes una prueba que puedes hacer hoy y que no necesita ningún umbral: saca los rechazos registrados por tu límite actual. ¿Ha rechazado algo alguna vez? Una cifra que no ha rechazado ni una sola llamada no es un control, es un comentario.

![La vista de métricas de agentes de LiveContext: una fila de resumen con ejecuciones totales, tokens, llamadas a herramientas y tasa de éxito, sobre una tabla por agente con ejecuciones, tokens, llamadas a herramientas, créditos gastados, modelo, duración y tasa de éxito.](/blog/cap-ai-agent-cost-budgets-metrics.png)

*El gasto por agente, a posteriori. Justo la vista adecuada para decidir un límite, y justo lo que no debe usarse para detener una ejecución.*

## Qué hace realmente el límite de tu proveedor

Se da por hecho que la cifra del panel del proveedor es un muro. Casi siempre es un timbre.

| Control del proveedor | Qué es en realidad |
|---|---|
| Límite de gasto de proyecto u organización en OpenAI | Un presupuesto blando por defecto: avisa y las peticiones siguen pasando. Existe una parada dura como opción aparte que hay que activar, y entonces rechaza llamadas hasta que subas el límite |
| API de Spend Limits de Anthropic | Solo planes Enterprise, solo mensual, y cubre el uso de las licencias humanas, no el gasto de API de los agentes |
| Tope mensual por nivel en Anthropic | Un techo real, pero de toda la organización y mensual: una ejecución desbocada convierte un fallo de coste en una caída para todos |

Fuentes: la [guía de spend limits de OpenAI](https://developers.openai.com/api/docs/guides/spend-limits), la [API de Spend Limits](https://platform.claude.com/docs/en/manage-claude/spend-limits-api) y los [límites de tasa](https://platform.claude.com/docs/en/api/rate-limits) de Anthropic. La propia documentación de Anthropic va más lejos y desaconseja usar su cifra de gasto como puerta: puede leer cero cuando el dato no está disponible, así que hay que tratarla como informativa.

De ahí salen dos conclusiones. Los límites del proveedor son una red de seguridad, no tu primera línea de defensa. Y un tope mensual de toda la organización tiene la forma equivocada para detener una sola ejecución defectuosa: cuando salta, se lleva por delante todo lo demás.

## No puedes detener la llamada que ya estás haciendo

Esta es la parte que todo artículo honesto sobre presupuestos tiene que decir.

Solo sabes lo que ha costado una llamada cuando ha terminado. Así que ningún presupuesto en ejecución puede impedir que una llamada cara reviente el techo. Solo puede impedir la siguiente. Tu peor caso real es el presupuesto más una llamada.

Eso tiene una consecuencia práctica. Si una sola llamada puede costar la mitad de tu presupuesto, tu presupuesto no puede funcionar. Un techo solo se comporta como un techo cuando es cómodamente mayor que la mayor llamada posible del agente, y una regla de tres veces es un suelo razonable. Dimensionarlo bien es un tema propio: [cuánto presupuestar por agente](/es/blog/size-an-ai-agent-budget) hace los números.

También significa que una buena implementación predice antes de gastar. Mira lo que costaron los últimos pasos, a qué velocidad crecen y cuál es la mayor llamada que ese modelo podría hacer físicamente, y rechaza cuando la proyección rompería el techo. Predecir es todo el truco, porque medir siempre llega tarde.

## Qué limitan de verdad las herramientas populares

Si das por hecho que tu framework te cubre, compruébalo. La mayoría limita algo distinto del dinero y casi todos vienen sin límite.

| Herramienta | Qué limita | Por defecto |
|---|---|---|
| Claude Agent SDK | Dólares por ejecución y turnos | Ambos ilimitados |
| API Messages de Anthropic | Tokens por respuesta | Sin valor por defecto, hay que fijarlo |
| Cuenta de OpenAI | Dólares al mes | Blando, solo aviso |
| OpenAI Agents SDK | Número de turnos | 10 |
| LangGraph | Número de pasos | Documentado como 25 en unos sitios y 1000 en otros |
| Middleware de LangChain | Número de llamadas, sin presupuesto de coste ni tokens | Sin límite |
| Pydantic AI | Tokens, peticiones, llamadas a herramientas | 50 peticiones, sin límite de tokens |
| CrewAI | Iteraciones | 20 o 25, según qué página leas |

Tres cosas que conviene sacar de esa tabla.

**Casi todo viene ilimitado.** La suposición segura es que no tienes ningún techo hasta que lo pongas.

**Contar llamadas no es un presupuesto.** Diez llamadas pueden costar un céntimo o diez euros según cuánto texto lleve cada una. El middleware de LangChain limita número de llamadas y no tiene ningún presupuesto de tokens ni de coste.

**Un límite que no llega a los subagentes es decorativo.** Es la forma más común de que un techo resulte falso: se configura un límite en el padre, este lanza hijos y los hijos corren con los valores por defecto. Hay casos documentados en frameworks muy usados. Si te llevas una sola acción de este artículo, que sea esta: pon un límite al padre, lanza un hijo y demuestra que lo hereda.

## Cuatro reglas para un presupuesto que funcione

1. **Limita dinero o tokens, no pasos.** El precio de un paso flota. El de un euro no.
2. **Da un techo a cada paso y otro a la ejecución completa.** Una ejecución que se abre en cincuenta ramas paralelas puede respetar todos los presupuestos de paso y aun así costar cincuenta veces lo previsto.
3. **Reserva antes de lanzar, no interrumpas a mitad.** Cortar ramas en marcha deja medio resultado arbitrario. Negarse a empezar es explícito y se puede reintentar.
4. **Cuando salte el límite, conserva el trabajo.** Una parada que tira todo lo hecho convierte un problema de coste en una pérdida total, y por eso exactamente los equipos desactivan los límites.

Este último merece una línea propia. Una parada por presupuesto debe devolver lo que el agente produjo, más el detalle de lo gastado y del motivo de la parada, y debe nombrar qué techo saltó. Una parada que solo dice "presupuesto superado" no te da nada con lo que actuar.

## ¿Hasta dónde llega esto en la práctica?

No existe ninguna tasa publicada de con qué frecuencia se desbocan los agentes en producción, así que desconfía de cualquier frecuencia dicha con seguridad. Lo que sí está documentado es el orden de magnitud, y es más modesto que la leyenda.

Los incidentes recogidos se mueven entre cientos y unos pocos miles de dólares: unos 2.150 dólares de gasto no deseado en un caso, 235 dólares en cuatro días por un solo usuario, un exceso del 70 % sobre un presupuesto fijado. Mientras tanto, la historia más republicada del sector, un anónimo "gastamos 47.000 dólares en agentes de IA", no nombra ninguna empresa, no enseña ninguna factura, y sus propias cifras semanales suman 25.658 dólares, no 47.000.

El riesgo real no es una factura espectacular. Es una fuga discreta y recurrente de unos pocos miles, que nadie atribuye a nada, mes tras mes.

## Preguntas frecuentes

### ¿Fijar un máximo de tokens limita mis costes?

Solo el tamaño de cada respuesta. No hace nada contra el número de vueltas del agente, que es de donde sale el gasto desbocado.

### ¿Debo usar el límite de gasto de mi proveedor?

Sí, como red de seguridad, y activa la versión dura si tu proveedor la ofrece. Simplemente no lo tomes por tu control: suele ser mensual, de toda la organización y blando por defecto.

### ¿Qué presupuesto inicial es razonable?

Al menos tres veces la mayor llamada posible del agente; si no, puede reventar antes de tener ocasión de rechazar nada. Empieza ahí y ajusta con ejecuciones reales.

### Mi límite nunca ha saltado. ¿Buena señal?

Significa que no está probado, no que funcione. Pon un presupuesto deliberadamente diminuto en un agente de prueba y comprueba que obtienes un rechazo limpio que nombra el límite que saltó.

### ¿Los detectores de bucles sustituyen a los presupuestos?

No, responden a otra pregunta. Un detector de bucles acota cuántas veces se repite algo. Un presupuesto acota lo que pueden costar esas repeticiones. Quieres los dos.

## El siguiente paso

Comprueba tres cosas esta semana: si tu límite es de dinero y no de número de llamadas, si llega a los subagentes y si ha rechazado algo alguna vez. Después elige la cifra con [cuánto presupuestar por agente de IA](/es/blog/size-an-ai-agent-budget).
`;

export default content;
