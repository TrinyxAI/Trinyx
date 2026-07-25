// Portuguese translation of ai-agent-audit-trail (public register, 2026-07-24).
const content = `Um agente de IA que funciona na demonstração provou uma coisa: que consegue funcionar uma vez. A produção faz uma pergunta mais dura. Quando ele errar, consegue dizer o que aconteceu e porquê?

Se a resposta for não, não tem um sistema que opera. Tem um sistema em que espera. O que fecha essa falha é um registo de cada execução que alguém de fora da sua equipa consiga ler meses depois.

## Em resumo

- O seu painel de monitorização não é um registo de auditoria. Outro leitor, outro relógio, outras regras.
- O rastreio padrão de IA não guarda prompts nem respostas por omissão. Tem de o ativar.
- Nunca faça amostragem de um registo de auditoria. A execução que terá de explicar estará no que descartou.
- Registe cada chamada a ferramenta e o seu resultado, o ramo seguido, o custo e quem aprovou.
- Nas aprovações, guarde o que a pessoa viu de facto, não apenas que carregou em aceitar.

## Um painel não é um registo de auditoria

São parecidos e não são a mesma coisa. Um painel é lido pelo seu autor, minutos depois, com o incidente fresco. Um registo é lido por um terceiro indiferente ou hostil, meses depois, que não lhe pode fazer perguntas.

| | Painel de monitorização | Registo de auditoria |
|---|---|---|
| Quem o lê | Você, minutos depois | Um terceiro, meses depois |
| Amostragem | Normal, muitas vezes 10 a 20 % | Nunca |
| Conteúdo de prompts e respostas | Normalmente desligado | Ligado, enquanto durar a conservação |
| Se uma escrita falhar | Anota-se e segue-se | A operação devia falhar |
| Ordem | Marcas de tempo | Um número de sequência atribuído por si |
| Pode mudar depois | Sim, por desenho | Não, só se acrescenta |
| Modo de falha | Depura mais devagar | Não consegue responder à pergunta |

![Uma execução de workflow do LiveContext na vista de observabilidade: o grafo executado com um visto verde em cada nó, ao lado de um inspetor que lista a época, as marcas de início e fim, e o estado, a duração e o custo de cada nó.](/blog/ai-agent-audit-trail-run.png)

*Uma execução na vista de observabilidade: cada passo, o seu estado, a sua duração, o seu custo. Muito útil e, ainda assim, um painel e não o registo duradouro que o resto do artigo descreve.*

## "Temos rastreio" não é o mesmo que "temos registo"

É este o achado que apanha mais equipas desprevenidas.

As convenções padrão do setor para rastrear chamadas de IA tratam prompts, respostas, argumentos de ferramentas e resultados de ferramentas como opcionais, e a posição da especificação é que as ferramentas não os devem capturar por omissão. Uma instalação de rastreio acabada de fazer dá-lhe o nome do modelo, contagens de tokens, latência e um motivo de fim: nada do material que reconstrói uma decisão.

Ligar a captura de conteúdo também é mais complicado do que um único interruptor, pelo menos numa implementação popular onde é preciso ativar ainda uma segunda definição mal documentada. Confirme o que a sua instalação guarda mesmo em vez de o assumir, e confirme lendo um registo real de ponta a ponta.

A outra metade do mesmo problema são os conselhos de quase todos os guias de observabilidade: fazer muita amostragem em volume e limpar o conteúdo antes de chegar ao backend. Ambos fazem sentido para monitorizar e são fatais para auditar. Uma amostra de 10 % não vale nada quando a decisão a defender está nos outros 90 %.

## O que registar em cada execução

Um registo por execução. É o cabeçalho que se lê primeiro.

| O que registar | Porque interessa |
|---|---|
| Um identificador de execução criado ao lançar | Tudo o resto se prende a ele, e um criado tarde perde-se |
| Quem ou o que a iniciou, e como | Uma pessoa, um agendamento, um webhook: define de quem é a responsabilidade |
| Hora de início e de fim, duas marcas de tempo | Uma duração não se cruza com uma cronologia externa |
| Que modelo foi faturado e qual correu de facto | Podem diferir, e anotar só um deixa o resto errado |
| Os preços em vigor no momento | Para o custo continuar a fazer sentido depois de mudarem as tarifas |
| Tokens de entrada, de saída, em cache, e o custo | A sua fatura e o seu aviso antecipado |
| O estado e porque parou | A afirmação que lhe vão pedir para defender |
| A configuração e a versão de política em vigor | Se era exigida aprovação, naquele instante |
| Que versão do software estava a correr | Se esta execução é anterior à correção |
| Se era exigida aprovação, e a sua referência | Vazio tem de significar "não exigida", não "desconhecido" |

Dois pontos merecem insistência. **Duas marcas de tempo, não uma duração**, porque só marcas se cruzam com registos de terceiros. E **os preços em vigor**, porque preços e nomes de modelos mudam debaixo dos seus pés, e um custo que não consegue reproduzir é um custo que não consegue defender.

Uma coisa a não guardar: o prompt de sistema completo em cada execução. A dez mil execuções por dia, um prompt de seis kilobytes são cerca de 20 GB por ano de pura duplicação. Guarde cada versão uma vez e faça referência.

## O que registar em cada passo

Um registo por turno de modelo, chamada a ferramenta, decisão ou aprovação. São cerca de vinte e cinco vezes mais do que os de execução e levam quase todo o conteúdo.

| O que registar | Porque interessa |
|---|---|
| A ordem real, atribuída na escrita | As marcas de tempo empatam e trocam de ordem. Um contador não |
| Se houve passos em paralelo | Ler um lote paralelo como uma cadeia causal é pior do que uma falha |
| Que tipo de passo foi | Turno de modelo, chamada a ferramenta, decisão, aprovação |
| Nome da ferramenta e identificador da chamada | Liga o pedido ao seu resultado apesar das repetições |
| Os argumentos e o resultado | O conteúdo real, no relógio que aplicar ao conteúdo |
| Uma impressão digital de ambos | Permite provar o que foi enviado muito depois de apagado |
| O tamanho do conteúdo | Diz a um leitor futuro que houve truncagem e de quanto |
| Que ramo foi seguido | Torna a execução reproduzível no papel |
| Porque é que um passo não correu | Um ramo descartado e um nunca alcançado são factos diferentes |
| Código de erro, separado da mensagem | Os códigos consultam-se; as mensagens copiam a entrada que falhou |
| Se houve ocultação de dados | Caso contrário, um registo de aspeto limpo não prova nada |

A linha da impressão digital é a estrela discreta da tabela. Guardar um resumo do que entrou e saiu custa uns bytes por passo, e permite manter prova durante anos apagando o conteúdo ao fim de meses. Quando alguém apresenta um documento e diz que o seu agente o viu, a impressão digital resolve.

Uma ressalva, para ninguém errar: uma impressão digital de algo adivinhável, como um código postal ou uma data de nascimento, reverte-se tentando todas as hipóteses. Essas precisam de uma chave guardada à parte.

## O registo de aprovação merece linha própria

Se uma pessoa aprova, registe isso como um registo de pleno direito e não como uma marca na execução.

Anote quem aprovou, quando, por que canal, quanto tempo tinha antes de expirar e, sobretudo, **o que a pessoa viu de facto**. Congele esse texto no momento em que a execução pausou e guarde-o com o registo. Sem isso, "uma pessoa aprovou" não quer dizer nada, porque ninguém consegue saber o que estava a aprovar.

Três armadilhas pequenas no mesmo sítio. Um campo de aprovação vazio tem de significar "a política em vigor não exigia aprovação", o que obriga a poder recuperar a versão dessa política. Identidades por omissão como "sistema" ou "api" nunca devem poder designar uma pessoa real. E se o seu registo mostra um papel de aprovador, garanta que algo verificou esse papel, ou diga claramente no registo que não verificou.

## Dois erros que arruínam um registo em silêncio

**Escrevê-lo sem garantias.** Se a escrita de auditoria é disparada sem esperar e as falhas são anotadas como não críticas, o seu registo emagrece justamente quando o sistema está sob pressão, ou seja, durante os incidentes que lhe vão pedir para explicar. A cobertura fica correlacionada com a saúde do sistema, a pior propriedade possível. Escreva o registo na mesma transação daquilo que regista.

**Guardar uma duração sem a cronologia.** Parece menor até lhe pedirem para cruzar o seu registo com as marcas de tempo dos emails de um cliente e não conseguir.

## Perguntas frequentes

### O meu fornecedor de modelos não regista já isto tudo?

Regista o lado dele da chamada, durante o período dele, no formato dele, e não o pode consultar como prova. O registo que consegue defender é o que guarda.

### Registar tudo não fica caro?

O esqueleto (identificadores, tempos, estados, contagens, impressões digitais, ramos) é minúsculo, na ordem de algumas dezenas de gigabytes por ano com dez mil execuções diárias. O conteúdo é a parte cara, e é precisamente por isso que anda num relógio mais curto. Essa separação é o tema de [durante quanto tempo guardar](/pt/blog/ai-agent-audit-log-retention).

### E os dados pessoais nos registos?

Parta do princípio de que existem, sobretudo nas mensagens de erro, que copiam sistematicamente a entrada que falhou. Mantenha os identificadores pseudonimizados, o conteúdo num relógio curto, e reduza o registo de longa duração a impressões digitais e códigos.

### Como sei se o meu registo chega?

Pegue numa execução do mês passado e reconstrua-a de ponta a ponta usando só o que está guardado. Se tiver de correr algo outra vez ou perguntar a um colega, ainda não chega.

## O passo seguinte

Pegue numa execução real e tente explicá-la só a partir do registo. Tudo o que tiver de adivinhar é o próximo campo a acrescentar. Depois decida quanto tempo cada parte tem de sobreviver: [durante quanto tempo guardar os registos de um agente de IA](/pt/blog/ai-agent-audit-log-retention).
`;

export default content;
