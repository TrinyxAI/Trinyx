// Spanish translation of size-an-ai-agent-budget (public register, 2026-07-24).
const content = `Puedes poner un presupuesto a un agente de IA. Lo difícil es saber qué cifra escribir en la casilla. Demasiado alta y nunca detiene nada. Demasiado baja y mata trabajo que iba bien.

Así se llega a una cifra defendible, sin necesidad de una carrera de estadística.

## En resumen

- Parte de lo que cuesta realmente un paso, no de lo que te parece prudente.
- Añade margen según el uso de herramientas: unas 2x para un paso de una sola llamada, 3x a 4x para uno con muchas herramientas.
- Limitar cuántas vueltas puede dar un agente es una pésima forma de limitar el dinero.
- En los pasos baratos, limita la entrada. En los caros, limita el dinero.
- El presupuesto de una ejecución no es la suma de los presupuestos de sus pasos, porque los pasos se repiten.

## Primero, saber lo que cuesta un paso

Los costes varían entre tipos de trabajo mucho más de lo que se espera. Son ejemplos de un modelo construido, no medidas de producción, pero lo importante es la distancia entre ellos.

| Tipo de paso | Qué hace | Coste típico por ejecución |
|---|---|---|
| Clasificar | Lee un mensaje, devuelve una etiqueta | unos 0,0003 $ |
| Redactar con búsqueda | Recupera un documento, escribe una respuesta | unos 0,013 $ |
| Investigación con varias herramientas | Unas seis llamadas a herramientas y un resumen | unos 0,27 $ |
| Resumir un documento largo | Una lectura grande, una respuesta | unos 0,04 $ |
| Paso de navegador | Una docena de acciones de página, cada una con su captura | unos 1,67 $ |

Entre un paso de clasificación y uno de navegador hay más de mil veces de diferencia. Un presupuesto único para ambos no significa nada, y por eso los presupuestos van por paso y no por agente.

## Tu margen no es 2x

Casi todo el mundo coge el coste típico y lo dobla. Es más o menos correcto para un paso que hace una llamada y se detiene. Es muy incorrecto para cualquier cosa que use herramientas.

La razón es que cada resultado de herramienta se arrastra a todas las llamadas siguientes, así que el coste no crece al ritmo del número de llamadas. Crece más rápido. Doblar las llamadas a herramientas en un paso intensivo puede cuadruplicar aproximadamente su coste.

| Tipo de paso | Si da el doble de pasos de lo habitual | Margen a prever |
|---|---|---|
| Una llamada, sin herramientas | Alrededor del doble del coste | 2x |
| Redacción con una o dos búsquedas | Unas tres veces y media | 3x a 4x |
| Investigación o navegación intensivas | Unas cuatro veces | 3x a 4x |

La conclusión práctica es la misma en todos los casos: "subimos un poco el máximo de iteraciones" no es un cambio pequeño. Es la decisión de cuadruplicar aproximadamente el techo.

![La vista de métricas de agentes de LiveContext: una fila de resumen con ejecuciones totales, tokens, llamadas a herramientas y tasa de éxito, sobre una tabla por agente con ejecuciones, tokens, llamadas a herramientas, créditos gastados, modelo, duración y tasa de éxito.](/blog/cap-ai-agent-cost-budgets-metrics.png)

*Gasto, tokens y llamadas a herramientas por agente, con ejecuciones reales. Esta es la entrada del dimensionado: la cifra que fijes debe salir de tu propia distribución, no de una intuición.*

## Por qué un tope de iteraciones limita mal el dinero

Muchas herramientas solo permiten limitar el número de vueltas. Parece un límite. Haz las cuentas y apenas lo es.

| Paso | Coste esperado | Coste si llega a un tope de 100 vueltas |
|---|---|---|
| Investigación con varias herramientas | unos 0,27 $ | unos 47 $ |
| Paso de navegador | unos 1,67 $ | unos 101 $ |

Un tope que permite sesenta veces la factura esperada no te protege de nada. Si tu único control es un contador de vueltas, ponlo cerca de lo que el trabajo real necesita (unas pocas llamadas para búsquedas simples, diez o quince para una comparación) en vez de en una cifra redonda como 100.

## Pasos baratos: limita la entrada. Pasos caros: limita el dinero.

Hay un suelo por debajo del cual un límite en dinero no puede funcionar físicamente.

Un presupuesto solo puede rechazar la llamada *siguiente*, así que necesita espacio para varias llamadas antes del techo. Regla aproximada: el presupuesto debe valer al menos tres veces la mayor llamada posible del paso. Por debajo, la primera llamada puede reventar el techo y el presupuesto nunca llega a actuar.

En los pasos baratos ese suelo queda por encima de lo que cuesta el paso, así que un límite en dinero es teatro. Lo que sí funciona ahí es limitar lo que entra: acota cuánto texto se le entrega al paso y cuánto puede escribir de vuelta. Con eso, la peor llamada cae un orden de magnitud y el suelo baja con ella.

| Tipo de paso | El control que funciona | Por qué |
|---|---|---|
| Clasificación, búsquedas cortas | Limitar el tamaño de entrada | El paso ya está acotado; un límite en dinero no muerde |
| Trabajo con documentos largos | Limitar el tamaño de entrada | Una sola llamada grande: la entrada *es* el coste |
| Investigación, navegación, todo lo que itera | Limitar el dinero | El coste viene de la repetición, que solo el dinero acota |

## El presupuesto de ejecución no es la suma de los pasos

Aquí es donde suele romperse un dimensionado cuidadoso.

Los pasos se repiten. Un paso dentro de un bucle sobre cincuenta elementos se ejecuta cincuenta veces. Una rama que se abre se ejecuta una vez por rama. Así que el techo de la ejecución hay que calcularlo por el camino más caro del flujo, contando repeticiones, y no sumando un presupuesto por cada paso dibujado en el lienzo.

Y cuando una ejecución se abre en paralelo, recházala antes de empezar en vez de interrumpirla a mitad. Cortar una apertura en marcha deja un subconjunto arbitrario de ramas terminadas, y cuáles sobreviven depende del orden en que arrancaron. Rechazar de entrada deja algo que se puede reintentar.

## Cómo elegir la cifra

1. **Reúne unas cuantas ejecuciones reales.** De cada paso: tokens de entrada, tokens de salida, cuántas llamadas a herramientas, qué modelo y cómo terminó.
2. **No dimensiones sobre la media.** Los costes están sesgados: la mayoría de las ejecuciones son baratas y unas pocas caras, así que la media queda muy por debajo del centro del riesgo. Dimensionar sobre ella mata cerca de un tercio del trabajo legítimo.
3. **Sé honesto con tu muestra.** Hacen falta unos cientos de ejecuciones antes de hablar de un peor caso sin sonrojarse. Por debajo, dimensiona sobre el peor caso estructural (la mayor llamada que el modelo puede hacer físicamente) en vez de fingir que tienes una distribución.
4. **Vigila la acumulación.** Un límite que mata el 5 % de los pasos suena tolerable, hasta que tienes diez pasos: eso es un 40 % de ejecuciones tocando algún límite. Los límites por paso deben ser mucho más holgados que tu tolerancia a nivel de ejecución.
5. **Pruébalo.** Sobrealimenta a propósito un paso y comprueba que obtienes un rechazo limpio que nombra el límite. Un límite sin probar es una intuición con un número encima.

## Preguntas frecuentes

### ¿Qué presupuesto inicial es razonable para un agente?

Toma el coste esperado de su paso más caro, multiplícalo por tres o cuatro si usa herramientas y aplícalo por paso. Después fija un presupuesto de ejecución por el camino más largo, contando todo lo que itera.

### ¿Por qué no poner un presupuesto generoso y olvidarlo?

Porque un presupuesto generoso solo salta después del daño. El valor de un techo es la ejecución que rechaza, y uno puesto a sesenta veces el coste esperado no rechazará nada que valga la pena.

### Mi agente llega siempre a su presupuesto. ¿Lo subo o lo arreglo?

Mira qué ha cambiado antes de subir nada. Llegar al límite suele significar que la entrada ha crecido o que el agente ha empezado a dar vueltas, y ambas cosas piden arreglo, no financiación.

### ¿Presupuesto por paso o basta con uno por agente?

Por paso, si los pasos son de naturaleza distinta. Entre clasificar y navegar hay mil veces de diferencia de coste, y una sola cifra no puede ser correcta para ambos.

### ¿Cada cuánto revisar estas cifras?

Cada vez que cambies de modelo, el tamaño de los prompts o lo que el paso tiene permitido hacer. Las tres cosas mueven el coste, y un presupuesto ajustado a la forma del trimestre pasado o gotea o estrangula.

## El siguiente paso

Dimensionar solo sirve si el límite puede detener de verdad una ejecución. Comprueba primero ese lado: [cómo evitar que un agente gaste de más](/es/blog/cap-ai-agent-cost-budgets) explica de qué está hecho un techo real y cómo demostrar que el tuyo funciona.
`;

export default content;
