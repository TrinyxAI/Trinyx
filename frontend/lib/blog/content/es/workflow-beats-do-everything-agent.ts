// Spanish translation of workflow-beats-do-everything-agent (public register,
// 2026-07-24). Keep the cents consistent with the English source.
const content = `Un agente de IA que lo hace todo casi siempre cuesta más que el mismo trabajo dividido en unos pocos pasos estrechos. Cuánto más depende de una sola cosa: cuántas veces da vueltas el agente antes de terminar. En un trabajo corto, apenas hay diferencia. En uno largo y sinuoso, el agente puede costar veinte o treinta veces más.

Esa es la versión honesta. Y antes, la cifra que tuvimos que retirar.

## En resumen

- La diferencia de coste es real, pero depende casi por completo de cuántos pasos da el agente.
- En un ticket de soporte típico, un agente cuesta unos 19 céntimos y un flujo dividido unos 2.
- Activa la caché y el agente baja a unos 9 céntimos, lo que reduce la diferencia a la mitad.
- Trabajo corto o abierto: construye el agente. Trabajo repetido con forma conocida: construye el flujo.
- La fiabilidad y el esfuerzo de construcción suelen pesar más que la factura de tokens.

## La afirmación que borramos

Un borrador anterior de este artículo decía que un flujo dividido sale "unas diez veces más barato" que un agente que lo hace todo. Lo borramos. No había ningún cálculo detrás ni ninguna fuente, solo una cifra que sonaba bien.

Tampoco hay un estudio limpio con el que sustituirla. Nadie ha publicado el mismo trabajo real, construido de las dos formas, con los costes medidos en paralelo. Incluso la guía de Anthropic, [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents), dedica dos frases al tema y ninguna cifra: los agentes "cambian latencia y coste por mejor rendimiento" y su autonomía "implica costes mayores". Cierto, pero no es un número con el que planificar.

Así que todo lo que sigue está calculado a partir de supuestos que puedes comprobar, no tomado del titular de otro.

## Por qué cuesta más el agente

Una sola idea lo explica todo. Un modelo de IA no tiene memoria entre llamadas. Cada vez que el agente da otro paso, hay que entregarle de nuevo toda la conversación: las instrucciones iniciales, todas las herramientas que podría usar y todo lo ocurrido hasta ese momento.

La primera vuelta es barata. La segunda relee la primera. La tercera relee las dos anteriores. En la octava, el agente paga por releer una pila creciente de su propio trabajo, una y otra vez. El coste no se suma en línea recta: hace bola de nieve.

Un flujo dividido evita la bola de nieve. Cada paso recibe solo lo que necesita, lo hace y entrega un resultado pequeño y limpio al siguiente. El paso cuatro nunca relee los pasos uno a tres. No hay pila que crezca.

Ese es todo el mecanismo. El resto es ponerle euros.

## Un ejemplo real: clasificación de soporte

Tomemos un trabajo habitual. Llega un ticket de soporte y quieres clasificarlo, consultar la cuenta del cliente, buscar en tus artículos de ayuda, redactar una respuesta y revisarla antes de enviarla.

| Enfoque | Coste por ticket |
|---|---|
| Un agente que lo hace todo | unos 0,19 $ |
| Flujo dividido | unos 0,023 $ |

Construido como un único agente, ese ticket cuesta unos 19 céntimos. Construido como flujo (cuatro pasos pequeños de IA más dos consultas normales sin IA), el mismo ticket cuesta poco más de 2 céntimos. Unas ocho veces menos.

¿De dónde sale la diferencia? El agente da unas ocho vueltas para completar el trabajo y cada vuelta relee una transcripción más gorda que la anterior. El flujo hace el mismo trabajo real en cuatro pasos enfocados, ninguno de los cuales carga con el equipaje de los otros. Misma respuesta al final, factura muy distinta. (Los precios usados son las [tarifas públicas de los modelos](https://platform.claude.com/docs/en/about-claude/pricing); los tuyos serán otros.)

Una nota justa antes de dar por buenas esas ocho veces: los dos enfoques tienen que escribir igualmente la respuesta final, y escribir cuesta lo mismo en ambos casos. Ese borrador final es buena parte de los 2 céntimos del flujo, y por eso la diferencia es de unas ocho veces y no de unas ochenta.

![Una ejecución de flujo de Trinyx en vista de observabilidad: el grafo ejecutado con una marca verde en cada nodo, junto a un inspector que lista la época, sus marcas de inicio y fin, y el estado, la duración y el coste de cada nodo.](/blog/ai-agent-audit-trail-run.png)

*Una ejecución terminada, paso a paso, con la duración y el coste de cada uno. Esa vista por pasos es lo que hace explicable la factura en vez de una suma única.*

## Depende sobre todo de cuántos pasos

La cifra de ocho veces no es una ley. Es lo que sale cuando el agente da ocho vueltas. Cambia el número de vueltas y cambia todo el cuadro.

| Pasos que da el agente | Cuánto más cuesta el agente, aproximadamente |
|---|---|
| 2 | prácticamente igual (1,3x) |
| 8 | unas 8x más |
| 20 | unas 37x más |

Lee esa tabla como el verdadero titular. Un múltiplo de coste sin número de pasos no significa nada. Si alguien te dice "los agentes cuestan 10x", tu primera pregunta debería ser: ¿en un trabajo de cuántos pasos?

Aquí también hay un matiz de honestidad. La última fila solo cuenta si el trabajo necesita de verdad veinte pasos. Un agente que se enreda en veinte vueltas para hacer lo que un flujo limpio hace en cuatro no es caro, está perdido, y eso es un problema de calidad antes que de coste.

## Cuándo el agente único es lo correcto

Dividir no siempre gana, y fingir lo contrario sería otro argumento de venta.

| Situación | Construye esto | Por qué |
|---|---|---|
| Trabajo corto, dos o tres pasos | Un agente | La diferencia es mínima y un flujo cuesta tiempo de montaje |
| Trabajo abierto, imposible de guionizar | Un agente | No conoces los pasos hasta que estás dentro |
| Cada paso necesita el mismo documento grande | Un agente | Un flujo acaba reenviándolo en cada paso |
| Trabajo repetido con forma conocida | Flujo | El volumen amortiza la estructura enseguida |
| Todo lo que no debe improvisar su ruta | Flujo | Las ramas están fijadas, no se eligen al vuelo |

En el caso abierto, la autonomía compra resultados reales: Anthropic observó que un equipo de agentes en paralelo [superaba a un agente único en torno a un 90 % en preguntas de investigación difíciles](https://www.anthropic.com/engineering/multi-agent-research-system), consumiendo muchos más tokens para lograrlo. Cuando la respuesta importa más que la factura, págalo a propósito.

## Pon el agente en caché y la diferencia se encoge

Esta es la concesión que la mayoría de los argumentarios de "los flujos son 10x más baratos" se saltan. Esa bola de nieve de relectura tiene un remedio estándar, la caché: el proveedor deja que el modelo relea texto ya visto con un descuento fuerte.

Pon bien el agente en caché y su coste en nuestro ejemplo baja de unos 19 céntimos a unos 9 por ticket. La diferencia frente al flujo pasa de unas ocho veces a menos de cuatro. Sigue habiendo diferencia, pero mucho menor, y una comparación honesta tiene que valorar al agente así y no en su peor versión sin caché.

Dos cosas que la caché no hace. Ayuda poco en pasos muy cortos, porque hay un tamaño mínimo por debajo del cual el descuento no se aplica. Y no acorta la conversación, solo abarata releerla, así que un agente desbocado puede seguir llenando su ventana de contexto y perder el hilo.

## Lo que realmente decide

Da un paso atrás: la diferencia de coste, aun siendo real, casi nunca es lo que debería decidir.

Suelen pesar más otras dos cifras. La primera es la fiabilidad: si un enfoque acierta más a menudo y alguien tiene que arreglar cada fallo a mano, incluso una ligera ventaja de acierto vale mucho más que unos céntimos por ticket. La segunda es el esfuerzo de construcción: un flujo cuidado de varios pasos exige trabajo real de construir y mantener, mientras que un agente único con unas pocas herramientas se monta mucho antes. Con miles de tickets al día, el flujo amortiza ese esfuerzo rápido. Con unas decenas, nunca.

Así que el orden de las preguntas es: ¿el trabajo tiene forma conocida, lo vas a ejecutar en volumen y cuál de los dos falla menos? El múltiplo de coste solo importa después, y para entonces suele limitarse a confirmar lo que ya dijeron las dos primeras.

## Preguntas frecuentes

### ¿Un flujo siempre es más barato que un agente?

No. En un trabajo de dos pasos la diferencia es casi nula, y si cada paso necesita el mismo documento grande, el flujo puede costar más porque lo reenvía cada vez.

### ¿Por qué se encarece un agente según avanza?

Porque arrastra toda su conversación a cada paso nuevo. El paso ocho paga por releer del uno al siete: los pasos finales son los caros.

### ¿La caché iguala a agentes y flujos?

Reduce la diferencia a la mitad en nuestro ejemplo, no la cierra. La caché abarata la relectura, pero el agente sigue releyendo mucho más texto que cualquier paso de un flujo.

### ¿Cómo hago este cálculo para mi caso?

Mide tres cosas antes de citarte ninguna cifra: el tamaño real de tus prompts y datos, cuántos pasos da de verdad el agente en trabajo real (tus registros lo saben) y con qué frecuencia acierta cada enfoque. La diferencia de coste sale de ahí.

### ¿Puedo combinar los dos?

Sí, y la mayoría de los buenos sistemas lo hacen. Fija la estructura como flujo y deja que un agente pequeño se ocupe del único paso que de verdad exige criterio.

## Para curiosos

La única línea de matemáticas tras la bola de nieve: la lectura total de un agente crece aproximadamente con el número de pasos multiplicado por sí mismo, mientras que la de un flujo crece en línea recta. Por eso se separan más cuanto más largo es el trabajo.

## El siguiente paso

Saca de tus registros el número de pasos de un trabajo real y vuelve a leer la tabla de arriba con esa cifra. Elijas la forma que elijas, ponle antes un techo: [cómo limitar lo que puede gastar un agente](/es/blog/cap-ai-agent-cost-budgets).
`;

export default content;
