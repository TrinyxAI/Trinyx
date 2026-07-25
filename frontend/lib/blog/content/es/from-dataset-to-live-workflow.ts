// Spanish translation of from-dataset-to-live-workflow (public register,
// 2026-07-24). Structure identical to the English source.
const content = `Un conjunto de datos no sirve de nada hasta que algo lo lee con regularidad, decide qué ha cambiado y actúa. Así se pasa de un fichero que revisas a mano a un flujo de trabajo que se revisa solo.

El ejemplo que recorre todo el artículo es una vigilancia de precios: seguir unos cuantos productos, detectar cuándo uno se mueve y avisar a alguien antes de que cueste dinero. La forma sirve para cualquier cosa que tenga un ritmo.

## En resumen

- Elige una fuente que cambie con un ritmo que puedas predecir.
- Límpiala una sola vez, justo donde entra, para que todos los pasos siguientes puedan fiarse de ella.
- Calcula la decisión primero y ramifica sobre la decisión, no sobre los valores en bruto.
- Pon una aprobación humana delante de todo lo que no se pueda deshacer.
- Escribe el resultado de vuelta, para que la siguiente ejecución sepa qué hizo la anterior.

## La construcción, en seis pasos

| Paso | Qué hace | Por qué está ahí |
|---|---|---|
| 1. Programación | Se dispara cada hora | El ritmo. Nadie tiene que acordarse de empezar |
| 2. Lectura | Consulta la fuente en vivo | Aquí entra el dato fresco |
| 3. Limpieza | Lo reduce siempre a los mismos pocos campos | Todo lo posterior deja de adivinar |
| 4. Búsqueda | Comprueba si ya has visto este elemento | Evita duplicados y da la cifra anterior |
| 5. Decisión | ¿Se ha movido más de un 5 %? | La pregunta de verdad |
| 6. Aprobar y actuar | Una persona confirma, y entonces salen el aviso y la escritura | La parte irreversible, controlada |

![El constructor de flujos de LiveContext mostrando en el lienzo el grafo de vigilancia de precios con ocho nodos: un disparador horario, una llamada HTTP, un nodo de código, una búsqueda en tabla y una decisión que separa un SKU nuevo de uno conocido, después una decisión de movimiento de precio, una puerta de aprobación y la escritura protegida.](/blog/from-dataset-to-live-workflow-builder.png)

*Toda la construcción en un solo lienzo: del disparador horario de la izquierda a la escritura con aprobación de la derecha.*

## Paso 1: elige una fuente con ritmo

Automatiza datos que cambien con un ritmo que puedas nombrar. No "cada semana", sino "un CSV por proveedor, por correo, cada lunes antes de las 9". Esa precisión decide tu disparador.

Si la fuente casi nunca cambia, no necesitas un flujo de trabajo. Necesitas una consulta, y te ahorrarás el esfuerzo.

## Pasos 2 y 3: leer y limpiar una sola vez

Las fuentes en bruto son desordenadas. Los nombres de columna varían, las fechas llegan en tres formatos, un proveedor escribe "precio unitario" y otro "precio/ud".

Haz la limpieza en un único sitio, justo donde entra el dato. Decide primero la forma que quieres (para la vigilancia de precios: producto, precio, moneda, visto-el) y haz que cada fuente produzca esa forma y nada más. Todos los pasos posteriores se simplifican, porque pueden fiarse de su entrada.

Un aviso que pilla a todo el mundo: una lectura fallida suele llegar disfrazada de éxito. Muchos servicios devuelven un mensaje de error dentro de una respuesta perfectamente normal. Comprueba que lo que ha vuelto es realmente el dato antes de pasarlo, o el fallo bajará en silencio por todo el flujo.

## Pasos 4 y 5: decidir y luego ramificar

El objetivo del flujo es una decisión, así que hazla explícita.

La trampa es ramificar sobre el valor en bruto. No te importa que el precio sea 12,40. Te importa si ha subido más de tu tolerancia desde la última vez. Calcula eso primero y ramifica sobre la respuesta.

También tiene una cara muy práctica. Los filtros que parecen numéricos a menudo se comparan como texto por dentro, y el texto no se ordena como los números: "100" va antes que "9". Un filtro de "precio mayor que 9" puede saltarse en silencio el 100 que te interesaba. Recupera el valor anterior, haz el cálculo en un paso de decisión explícito y ramifica sobre eso.

## Paso 6: controla lo irreversible

El último paso debe hacer algo real: enviar el aviso, actualizar la fila, abrir el ticket, preparar el pedido.

Cuando esa acción es cara o no tiene vuelta atrás, pon una aprobación humana delante. La ejecución se detiene, espera a una persona y luego continúa exactamente donde se quedó. Las acciones baratas y reversibles pueden ir sin supervisión. Todo lo que llega a un cliente o gasta dinero pasa por una puerta.

Dos cosas que conviene saber sobre la pausa. Aprobar dos veces no rompe nada: vale la primera respuesta. Y la siguiente ejecución programada no atropella una decisión que alguien está pensando: cada ejecución conserva sus propios resultados.

## La única protección que hace segura una ejecución repetida

Un disparador horario repite la misma lectura cada hora. Sin protección, inserta la misma fila cada hora y tu tabla se llena de duplicados.

El patrón que lo arregla, en cualquier herramienta: **buscar primero, decidir después, escribir al final**. Busca el elemento. Si el recuento es cero, es nuevo, así que escríbelo. Si no, ya existe, así que actualízalo. Nunca insertes sin condición cuando el mismo elemento se puede volver a leer.

Esa búsqueda cumple doble función. Es tu protección contra duplicados y también de donde sale la cifra de la semana pasada, que es lo que hace respondible la pregunta "¿se ha movido?".

## Cuatro trampas que cuestan una tarde

| Trampa | Lo que ves | Lo que ocurre en realidad |
|---|---|---|
| Resultado vacío silencioso | Un paso no devuelve nada, sin error | El dato está un nivel más adentro de lo esperado |
| Lectura fallida con aspecto normal | Todo lo posterior está mal | El error venía dentro de una respuesta normal |
| Número comparado como texto | Un umbral se salta casos en silencio | "100" se ordena antes que "9" |
| Duplicados cada hora | La tabla crece sin parar | Falta la protección de buscar antes de escribir |

Ninguno de estos casos lanza un error. Por eso cuestan una tarde.

## Prueba cada rama antes de darlo por bueno

No lo publiques solo por el camino feliz. Provoca cada caso a propósito y comprueba qué hizo realmente el flujo.

| Prueba | Qué provocas | Qué debe pasar |
|---|---|---|
| Elemento nuevo | Un elemento sin historial | Exactamente una fila escrita |
| Sin cambios | Un elemento conocido, precio estable | Nada enviado, nada escrito |
| Cambio real | Un elemento conocido, precio un 10 % arriba | La ejecución se detiene para aprobación |
| Rechazo | Rechaza la aprobación | Ni aviso ni escritura |
| Ejecutar dos veces | Vuelve a disparar la programación | El número de filas no cambia |

Si el caso de "cambio real" termina sin detenerse, tu umbral se está evaluando en un sitio que no pretendías. Es el fallo que conviene cazar antes de la puesta en marcha y no después.

## Preguntas frecuentes

### ¿Cada cuánto debe ejecutarse?

Al ritmo de la fuente. Cada hora para precios, cada día para un informe, cada semana para un fichero de proveedor. Ejecutarlo más a menudo de lo que cambia el dato cuesta llamadas y no aporta nada.

### ¿Dónde guardo el histórico?

En una tabla que el propio flujo lee y escribe. Eso convierte una serie de ejecuciones sueltas en algo con memoria: sabe qué ha tratado ya y tiene la cifra de ayer para comparar.

### ¿Qué pasa si una ejecución falla a mitad?

La ejecución se detiene en el paso que ha fallado y el registro muestra cuál era y qué había recibido. Corriges ese paso y la relanzas, en vez de razonar sobre el conjunto.

### ¿Hace falta una persona en el proceso?

Para todo lo irreversible, sí, al menos hasta que te fíes. Enviar automáticamente a partir de una lectura errónea es como la automatización se gana mala fama. Empieza con la puerta y quítala más adelante si los hechos lo respaldan.

## El siguiente paso

Elige una fuente que ya revises a mano cada semana. Anota la decisión que alimenta, el umbral que usas y qué haces cuando salta. Ese es el flujo, y ya lo has diseñado. Después mira [qué registrar](/es/blog/ai-agent-audit-trail) para poder responder de lo que hizo.
`;

export default content;
