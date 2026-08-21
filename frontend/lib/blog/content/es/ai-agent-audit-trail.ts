// Spanish translation of ai-agent-audit-trail (public register, 2026-07-24).
const content = `Un agente de IA que funciona en la demo ha demostrado una cosa: que puede funcionar una vez. Producción hace una pregunta más dura. Cuando se equivoque, ¿podrás decir qué pasó y por qué?

Si la respuesta es no, no tienes un sistema que operas. Tienes uno que esperas que salga bien. Lo que cierra esa brecha es un registro de cada ejecución que alguien ajeno a tu equipo pueda leer meses después.

## En resumen

- Tu panel de monitorización no es un registro de auditoría. Otro lector, otro reloj, otras reglas.
- El trazado estándar de IA no guarda ni prompts ni respuestas por defecto. Hay que activarlo.
- Nunca muestrees un registro de auditoría. La ejecución que tendrás que explicar estará en lo que descartaste.
- Registra cada llamada a herramienta y su resultado, la rama tomada, el coste y quién aprobó.
- En las aprobaciones, guarda lo que la persona vio realmente, no solo que pulsó aceptar.

## Un panel no es un registro de auditoría

Se parecen y no son lo mismo. Un panel lo lee su autor, minutos después, con el incidente fresco. Un registro lo lee un tercero indiferente u hostil, meses después, que no puede preguntarte nada.

| | Panel de monitorización | Registro de auditoría |
|---|---|---|
| Quién lo lee | Tú, minutos después | Un tercero, meses después |
| Muestreo | Normal, a menudo del 10 al 20 % | Nunca |
| Contenido de prompts y respuestas | Normalmente desactivado | Activado, mientras dure la conservación |
| Si falla una escritura | Se anota y se sigue | La operación debería fallar |
| Orden | Marcas de tiempo | Un número de secuencia que asignas tú |
| ¿Puede cambiar después? | Sí, por diseño | No, solo se añade |
| Modo de fallo | Depuras más lento | No puedes responder a la pregunta |

![Una ejecución de flujo de Trinyx en vista de observabilidad: el grafo ejecutado con una marca verde en cada nodo, junto a un inspector que lista la época, sus marcas de inicio y fin, y el estado, la duración y el coste de cada nodo.](/blog/ai-agent-audit-trail-run.png)

*Una ejecución en la vista de observabilidad: cada paso, su estado, su duración, su coste. Muy útil, y aun así un panel y no el registro duradero que describe el resto del artículo.*

## "Tenemos trazado" no es lo mismo que "tenemos registro"

Este es el hallazgo que pilla desprevenidas a más equipos.

Las convenciones estándar del sector para trazar llamadas de IA tratan prompts, respuestas, argumentos de herramientas y resultados de herramientas como opcionales, y la posición de la especificación es que las herramientas no deben capturarlos por defecto. Una instalación de trazado recién hecha te da el nombre del modelo, recuentos de tokens, latencia y un motivo de finalización: nada del material que reconstruye una decisión.

Activar la captura de contenido también es más enrevesado que un solo interruptor, al menos en una implementación popular donde hay que habilitar además un segundo ajuste apenas documentado. Comprueba qué guarda de verdad tu instalación en vez de suponerlo, y compruébalo leyendo un registro real de principio a fin.

La otra mitad del mismo problema son los consejos que aparecen en casi todas las guías de observabilidad: muestrea mucho cuando hay volumen y limpia el contenido antes de que llegue al backend. Ambos son sensatos para monitorizar y fatales para auditar. Una muestra del 10 % no vale nada cuando la decisión que debes defender está en el 90 % restante.

## Qué registrar en cada ejecución

Un registro por ejecución. Es la cabecera que se lee primero.

| Qué registrar | Por qué importa |
|---|---|
| Un identificador de ejecución creado al lanzarla | Todo lo demás cuelga de él, y uno creado tarde se pierde |
| Quién o qué la inició, y cómo | Una persona, una programación, un webhook: decide de quién es la responsabilidad |
| Hora de inicio y de fin, dos marcas de tiempo | Una duración no se cuadra con una cronología externa |
| Qué modelo se facturó y cuál se ejecutó realmente | Pueden diferir, y anotar solo uno deja el resto equivocado |
| Los precios vigentes en ese momento | Para que el coste siga teniendo sentido tras un cambio de tarifas |
| Tokens de entrada, de salida, en caché, y el coste | Tu factura y tu aviso temprano |
| El estado y por qué se detuvo | La afirmación que te pedirán defender |
| La configuración y la versión de política vigentes | Si se exigía aprobación, en ese instante |
| Qué versión del software se estaba ejecutando | Si esta ejecución es anterior a la corrección |
| Si se requería aprobación, y su referencia | Vacío debe significar "no requerida", no "desconocido" |

Dos merecen insistencia. **Dos marcas de tiempo, no una duración**, porque solo las marcas se cruzan con los registros de otros. Y **los precios vigentes**, porque precios y nombres de modelo cambian bajo tus pies, y un coste que no puedes reproducir es un coste que no puedes defender.

Algo que no conviene guardar: el prompt de sistema completo en cada ejecución. A diez mil ejecuciones diarias, un prompt de seis kilobytes son unos 20 GB al año de pura duplicación. Guarda cada versión una vez y referénciala.

## Qué registrar en cada paso

Un registro por turno de modelo, llamada a herramienta, decisión o aprobación. Superan a los de ejecución en una proporción de unos veinticinco a uno y llevan casi todo el contenido.

| Qué registrar | Por qué importa |
|---|---|
| El orden real, asignado al escribir | Las marcas de tiempo empatan y se reordenan. Un contador no |
| Si hubo pasos en paralelo | Leer un lote paralelo como una cadena causal es peor que un hueco |
| De qué tipo de paso se trata | Turno de modelo, llamada a herramienta, decisión, aprobación |
| Nombre de la herramienta e identificador de llamada | Relaciona la petición con su resultado pese a los reintentos |
| Los argumentos y el resultado | El contenido real, con el reloj que apliques al contenido |
| Una huella de ambos | Permite probar qué se envió mucho después de borrarlo |
| El tamaño del contenido | Le dice a un lector futuro que hubo truncado y de cuánto |
| Qué rama se tomó | Hace la ejecución reproducible sobre el papel |
| Por qué un paso no se ejecutó | Una rama descartada y una nunca alcanzada son hechos distintos |
| Código de error, separado del mensaje | Los códigos se consultan; los mensajes copian la entrada que falló |
| Si se aplicó ocultación de datos | Si no, un registro de aspecto limpio no prueba nada |

La línea de la huella es la estrella discreta de la tabla. Guardar una huella de lo que entró y salió cuesta unos pocos bytes por paso, y permite conservar pruebas durante años borrando el contenido a los pocos meses. Cuando alguien saca un documento y dice que tu agente lo vio, la huella lo zanja.

Una salvedad, para que nadie se equivoque: una huella de algo adivinable, como un código postal o una fecha de nacimiento, se revierte probando todas las opciones. A esas hay que añadirles una clave guardada aparte.

## El registro de aprobación merece su propia fila

Si una persona aprueba, regístralo como un registro de pleno derecho, no como una marca en la ejecución.

Anota quién aprobó, cuándo, por qué canal, cuánto tiempo tenía antes de expirar y, sobre todo, **qué vio realmente quien aprobó**. Congela ese texto en el momento en que la ejecución se detuvo y guárdalo con el registro. Sin eso, "una persona aprobó" no significa nada, porque nadie puede saber qué estaba aprobando.

Tres trampas pequeñas en la misma zona. Un campo de aprobación vacío tiene que significar "la política vigente no exigía aprobación", lo que obliga a poder recuperar la versión de esa política. Identidades por defecto como "sistema" o "api" nunca deben poder designar a una persona real. Y si tu registro muestra un rol de aprobador, asegúrate de que algo comprobó ese rol, o di claramente en el propio registro que no fue así.

## Dos errores que arruinan un registro en silencio

**Escribirlo sin garantías.** Si la escritura de auditoría se lanza y se olvida, y los fallos se anotan como no críticos, tu registro se adelgaza justo cuando el sistema está bajo presión: es decir, durante los incidentes que te pedirán explicar. La cobertura queda correlacionada con la salud del sistema, la peor propiedad posible. Escribe el registro en la misma transacción que aquello que registra.

**Guardar una duración sin la cronología.** Parece menor hasta que te piden cuadrar tu registro con las marcas de tiempo de los correos de un cliente y no puedes.

## Preguntas frecuentes

### ¿No está registrando ya todo esto mi proveedor de modelos?

Registra su lado de la llamada, durante su periodo de conservación, en su formato, y no puedes consultarlo como prueba. El registro que puedes defender es el que guardas tú.

### ¿No sale caro registrarlo todo?

El esqueleto (identificadores, tiempos, estados, recuentos, huellas, ramas) es diminuto, del orden de unas decenas de gigabytes al año con diez mil ejecuciones diarias. El contenido es la parte cara, y justo por eso va con un reloj más corto. Esa separación es el tema de [cuánto tiempo conservarlo](/es/blog/ai-agent-audit-log-retention).

### ¿Y los datos personales en los registros?

Da por hecho que los hay, sobre todo en los mensajes de error, que copian sistemáticamente la entrada que falló. Mantén los identificadores seudonimizados, pon el contenido en un reloj corto y reduce el registro de larga duración a huellas y códigos.

### ¿Cómo sé si mi registro es suficiente?

Coge una ejecución del mes pasado y reconstrúyela de principio a fin usando solo lo almacenado. Si tienes que relanzar algo o preguntar a un compañero, todavía no es suficiente.

## El siguiente paso

Coge una ejecución real e intenta explicarla solo con el registro. Todo lo que tengas que adivinar es el siguiente campo que añadir. Después decide cuánto debe sobrevivir cada parte: [cuánto tiempo conservar los registros de un agente de IA](/es/blog/ai-agent-audit-log-retention).
`;

export default content;
