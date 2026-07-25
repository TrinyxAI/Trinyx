// Spanish translation of ai-agent-audit-log-retention (public register,
// 2026-07-24). Keep the "not legal advice" line and the out-of-scope spine.
const content = `"¿Cuánto tiempo guardamos los registros?" suele responderse con una cifra que alguien recuerda de otro trabajo. Noventa días. Un año. Siete años, porque suena prudente.

Hay una forma mejor de decidirlo, y empieza por notar que no estás guardando una cosa. Estás guardando dos, y cuestan cosas muy distintas.

## En resumen

- Divide el registro en dos: un esqueleto pequeño y el contenido voluminoso.
- Guarda el esqueleto durante años. Es barato y no se puede añadir después.
- Guarda el contenido durante meses. Es casi todo el almacenamiento y casi todo el riesgo.
- La mayoría de los agentes de IA no están cubiertos por las obligaciones de registro del reglamento europeo de IA.
- Guardarlo todo para siempre no es la opción segura. Es otro problema distinto.

## Dos relojes, no uno

Casi toda la discusión sobre conservación se disuelve en cuanto dejas de tratar el registro como una sola cosa.

| Capa | Qué contiene | Cuánto tiempo | Por qué |
|---|---|---|---|
| Esqueleto | Identificadores, marcas de tiempo, estado, modelo, costes, rama tomada, huellas de los contenidos, quién aprobó | Años | Diminuto, y responde por sí solo a casi todas las preguntas |
| Contenido | Prompts, respuestas, argumentos y resultados de herramientas, mensajes de error | Meses | Casi todo el almacenamiento y casi toda la exposición de datos personales |

La bisagra entre ambas es la huella. Guarda una huella de cada contenido en el esqueleto y podrás demostrar, años después, exactamente qué se envió y qué se devolvió, sin conservar ni una palabra.

Eso es lo que hace defendible una conservación larga en vez de peligrosa.

## La aritmética decide por ti

Tomemos un sistema con carga: diez mil ejecuciones de agentes al día. Estos son, a grandes rasgos, los bytes al año. Trátalos como un modelo y no como una medición, y añade algo por la realidad.

| Qué | Al año | Qué hacer con ello |
|---|---|---|
| Esqueleto, todas las ejecuciones y pasos | unos 31 GB | Guardarlo años. Es el seguro barato |
| Resultados de herramientas duplicados | unos 84 GB | Guardar una vez y referenciar |
| Prompts de sistema duplicados | unos 21 GB | Guardar una vez por versión y referenciar por huella |

El esqueleto cuesta unos pocos euros al año de almacenamiento. Casi todo el debate sobre conservación trata en realidad de la capa de contenido, justo la que tienes buenas razones para mantener corta.

En esa tabla se esconden dos victorias fáciles. El mismo prompt de sistema guardado en cada ejecución, a veces varias veces por ejecución, es pura duplicación. Los resultados de herramientas copiados en varios sitios también. Arregla esos dos y la pregunta del almacenamiento se resuelve casi sola.

## Qué exige realmente la ley

Esto no es asesoramiento jurídico, y ninguno de los regímenes de abajo debe reducirse a una cifra única aplicable a ti. Pero conviene conocer la forma, porque casi todos los artículos se equivocan de las mismas dos maneras.

**El suelo de seis meses del reglamento europeo de IA solo afecta a los sistemas de alto riesgo.** Para esos, el proveedor y el responsable del despliegue tienen cada uno su propio mínimo de seis meses, limitado a los registros bajo su propio control. Se debe dos veces, por dos partes distintas, y no se comparte.

**Seis meses es el suelo para los registros. Diez años es el suelo para la documentación.** Dos regímenes distintos que se confunden constantemente. Conservar la documentación de diseño una década no dice nada sobre cuánto conservar los registros de ejecución.

**Y la parte que interesa a la mayoría:** alto riesgo significa componente de seguridad de un producto regulado, o uno de los ámbitos concretos que enumera el reglamento, como biometría, infraestructuras críticas, decisiones de empleo, acceso a servicios esenciales o aplicación de la ley. Un asistente de programación, un agente interno de investigación, un agente que redacta documentos, un agente que clasifica soporte: ninguno está en esa lista.

Existe además un derecho aparte que conviene conocer, porque es el que de verdad obliga a explicar una decisión: una persona afectada de forma significativa por una decisión tomada a partir de la salida de un sistema de alto riesgo puede pedir una explicación del papel de ese sistema. Es una obligación distinta del registro y, de nuevo, solo aplica a sistemas de alto riesgo.

Un apunte más si venías citando fechas: el calendario se movió. Las obligaciones de alto riesgo se aplazaron al 2 de diciembre de 2027 para los sistemas independientes y a agosto de 2028 para la IA integrada en productos regulados. Cualquier artículo que siga citando agosto de 2026 para alto riesgo está desactualizado.

Así que si estás fuera del ámbito, construye el registro para las preguntas que sí te harán: una disputa con un cliente, una revisión de incidente, una discusión sobre una factura, una investigación de seguridad. Y deja que los seis meses sean un suelo que superas por casualidad y no un proyecto.

## La solicitud de borrado que llega mañana

Y llega la colisión. Quieres un registro que dure años. Alguien tiene derecho a pedirte que borres sus datos.

Cuatro cosas lo hacen llevadero.

**Una referencia seudonimizada no es anonimato.** Si un identificador puede volver a ligarse a una persona con información que tienes en otro sitio, sigue siendo dato personal. Guarda la correspondencia aparte y no te cuentes que el registro es anónimo.

**Guardarlo todo para siempre no es la respuesta conforme.** La misma frase que fija un mínimo remite también a la normativa de protección de datos. La sobreconservación es un problema en sí, no un valor por defecto seguro.

**Borra la capa operativa, conserva el libro mayor.** Separa lo que una solicitud de borrado puede llevarse (contenido y filas operativas) de lo que debe sobrevivir (registros de facturación y de seguridad), y asegúrate de que la capa superviviente no lleva contenido ni identificadores directos.

**Vigila los datos que sobreviven al borrado.** El fallo clásico: los contenidos grandes viven en almacenamiento de ficheros y la fila de base de datos solo guarda un puntero. Borras la fila y el fichero se queda, sin referencias, invisible a cualquier auditoría posterior de lo que tienes. Haz del fichero el objetivo del borrado y concilia los restos periódicamente.

Un patrón que conviene construir si puedes: cuando se borre un contenido, deja una lápida con la huella y el tamaño. Un lector posterior sabrá que había algo, de qué tamaño era y que se retiró por una solicitud y no por pérdida.

## El error que no se puede deshacer

Todos los demás errores de conservación se arreglan. Este no: **la conservación no se alarga con efecto retroactivo.**

El día que descubres que la ventana necesaria era más larga que tu purga, el dato ya no está. La corrección duele también en el otro sentido: un equipo que subió un registro de ciclo de vida de 30 días a un año se encontró con un atasco doce veces mayor en la primera purga posterior.

Así que ajusta el esqueleto a la ventana más larga que puedas imaginar razonablemente, desde el primer día. A unos 31 GB al año es el seguro más barato del sistema. Después afina la ventana del contenido, que es la parte cara y reversible.

Dos errores menores de la misma familia. Comprueba que tu conservación documentada coincide con la configurada: un comentario que dice "30 días" sobre un ajuste cuyo valor por defecto es un año es como divergen ambas en silencio. Y mantén las consultas del día a día fuera de las filas de detalle, con resúmenes diarios para las preguntas frecuentes, o tu registro acabará técnicamente completo y prácticamente inservible.

## Preguntas frecuentes

### ¿Qué valor por defecto es razonable si no estoy regulado?

Esqueleto unos años, contenido de tres a seis meses. Eso cubre disputas, revisiones de incidentes y discusiones de facturas sin mantener un almacén de datos personales.

### ¿Tengo que guardar prompts y respuestas?

Mientras puedas necesitar explicar una decisión concreta, sí. Después, la huella lleva la prueba y el texto es solo exposición.

### ¿La regla de los seis meses aplica a mi chatbot?

Casi con seguridad no. Aplica a los sistemas de alto riesgo tal como los define el reglamento, y los agentes internos o de productividad corrientes no están en esa lista. Consulta la lista en vez de suponer, en cualquiera de los dos sentidos.

### ¿Adónde se va realmente el almacenamiento?

A los contenidos. Los resultados de herramientas y los prompts dominan, sobre todo cuando están duplicados en varios sitios. El esqueleto estructurado es despreciable al lado.

### ¿Puedo guardarlo todo y decidir más adelante?

Esa es la opción que parece segura y no lo es. Un contenido conservado mucho tiempo es un pasivo permanente, y es lo primero que encontrará una solicitud de borrado.

## El siguiente paso

Escribe dos cifras, una para el esqueleto y otra para el contenido, y que la del esqueleto sea generosa. Después comprueba que tu registro contiene de verdad lo que esas ventanas pretenden proteger: [qué registrar en cada ejecución de un agente de IA](/es/blog/ai-agent-audit-trail).
`;

export default content;
