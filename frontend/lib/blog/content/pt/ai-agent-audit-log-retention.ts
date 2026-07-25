// Portuguese translation of ai-agent-audit-log-retention (public register,
// 2026-07-24). Keep the "not legal advice" line and the out-of-scope spine.
const content = `"Quanto tempo guardamos os registos?" é normalmente respondido com um número de que alguém se lembra de outro emprego. Noventa dias. Um ano. Sete anos, porque soa a prudente.

Há uma forma melhor de decidir, e começa por reparar que não está a guardar uma coisa. Está a guardar duas, e custam coisas muito diferentes.

## Em resumo

- Divida o registo em dois: um esqueleto pequeno e o conteúdo volumoso.
- Guarde o esqueleto durante anos. É barato e não se acrescenta depois.
- Guarde o conteúdo durante meses. É quase todo o armazenamento e quase todo o risco.
- A maioria dos agentes de IA não está sequer abrangida pelas obrigações de registo do regulamento europeu de IA.
- Guardar tudo para sempre não é a opção segura. É outro problema.

## Dois relógios, não um

Quase toda a discussão sobre conservação se dissolve assim que deixa de tratar o registo como uma coisa só.

| Camada | O que contém | Durante quanto tempo | Porquê |
|---|---|---|---|
| Esqueleto | Identificadores, marcas de tempo, estado, modelo, custos, ramo seguido, impressões digitais dos conteúdos, quem aprovou | Anos | Minúsculo, e responde sozinho à maioria das perguntas |
| Conteúdo | Prompts, respostas, argumentos e resultados de ferramentas, mensagens de erro | Meses | Quase todo o armazenamento e quase toda a exposição de dados pessoais |

A dobradiça entre as duas é a impressão digital. Guarde um resumo de cada conteúdo no esqueleto e conseguirá provar, anos depois, exatamente o que foi enviado e devolvido, sem guardar uma única palavra.

É isso que torna uma conservação longa defensável em vez de perigosa.

## A aritmética decide por si

Tomemos um sistema com carga: dez mil execuções de agentes por dia. É mais ou menos assim que os bytes se distribuem por ano. Trate isto como um modelo e não como uma medição, e acrescente algo para a realidade.

| O quê | Por ano | O que fazer |
|---|---|---|
| Esqueleto, todas as execuções e passos | cerca de 31 GB | Guardar anos. É o seguro barato |
| Resultados de ferramentas duplicados | cerca de 84 GB | Guardar uma vez e referenciar |
| Prompts de sistema duplicados | cerca de 21 GB | Guardar uma vez por versão e referenciar por resumo |

O esqueleto custa uns euros por ano de armazenamento. Quase todo o debate sobre conservação é, na verdade, sobre a camada de conteúdo, precisamente aquela que tem boas razões para manter curta.

Escondem-se aí duas vitórias fáceis. O mesmo prompt de sistema guardado em cada execução, às vezes mais do que uma vez por execução, é pura duplicação. Resultados de ferramentas copiados para vários sítios também. Corrija esses dois e a questão do armazenamento resolve-se quase sozinha.

## O que a lei exige mesmo

Isto não é aconselhamento jurídico, e nenhum dos regimes abaixo deve ser reduzido a um número único aplicável a si. Mas vale a pena conhecer a forma, porque quase todos os artigos erram das mesmas duas maneiras.

**O piso de seis meses do regulamento europeu de IA só se aplica a sistemas de alto risco.** Para esses, o fornecedor e o responsável pela implantação têm cada um o seu mínimo próprio de seis meses, limitado aos registos sob o seu próprio controlo. É devido duas vezes, por duas partes diferentes, e não é partilhado.

**Seis meses é o piso para os registos. Dez anos é o piso para a documentação.** Dois regimes distintos, constantemente confundidos. Guardar a documentação de conceção uma década não diz nada sobre quanto tempo guardar registos de execução.

**E a parte que interessa à maioria:** alto risco significa componente de segurança de um produto regulado, ou uma das áreas concretas que o regulamento enumera, como biometria, infraestruturas críticas, decisões de emprego, acesso a serviços essenciais ou aplicação da lei. Um assistente de programação, um agente interno de investigação, um agente que redige documentos, um agente que faz triagem de suporte: nenhum está nessa lista.

Existe ainda um direito à parte que convém conhecer, porque é esse que obriga mesmo a explicar uma decisão: quem for significativamente afetado por uma decisão tomada com base na saída de um sistema de alto risco pode pedir uma explicação sobre o papel desse sistema. É uma obrigação diferente do registo e, também aqui, só se aplica a sistemas de alto risco.

Mais um ponto, se andava a citar datas: o calendário mudou. As obrigações de alto risco foram adiadas para 2 de dezembro de 2027 nos sistemas autónomos e para agosto de 2028 na IA integrada em produtos regulados. Qualquer artigo que continue a citar agosto de 2026 para alto risco está desatualizado.

Por isso, se está fora do âmbito, construa o registo para as perguntas que lhe vão mesmo fazer: um litígio com um cliente, uma revisão de incidente, uma discussão sobre uma fatura, uma investigação de segurança. E deixe os seis meses serem um piso que ultrapassa por acaso e não um projeto.

## O pedido de eliminação que chega amanhã

Chega a colisão. Quer um registo que dure anos. Alguém tem o direito de lhe pedir que apague os seus dados.

Quatro coisas tornam isso suportável.

**Uma referência pseudonimizada não é anonimato.** Se um identificador puder ser ligado de novo a uma pessoa com informação que tem noutro lado, continua a ser dado pessoal. Guarde a correspondência à parte e não se convença de que o registo é anónimo.

**Guardar tudo para sempre não é a resposta conforme.** A mesma frase que fixa um mínimo remete também para a proteção de dados. A conservação excessiva é um problema em si, não um valor por omissão seguro.

**Apague a camada operacional, guarde o livro-razão.** Separe o que um pedido de eliminação pode levar (conteúdo e linhas operacionais) do que tem de sobreviver (registos de faturação e de segurança), e garanta que a camada sobrevivente não leva conteúdo nem identificadores diretos.

**Atenção aos dados que sobrevivem à eliminação.** A falha clássica: os conteúdos grandes vivem num armazenamento de ficheiros e a linha da base de dados guarda só um ponteiro. Apaga a linha e o ficheiro fica, sem referências, invisível a qualquer auditoria posterior do que detém. Faça do ficheiro o alvo da eliminação e reconcilie os restos com regularidade.

Um padrão que vale a pena construir, se conseguir: quando um conteúdo é apagado, deixe uma lápide com a impressão digital e o tamanho. Um leitor posterior saberá que existiu algo, que tamanho tinha, e que foi removido a pedido e não perdido.

## O erro que não se desfaz

Todos os outros erros de conservação se corrigem. Este não: **a conservação não se prolonga retroativamente.**

No dia em que descobre que a janela necessária era maior do que a sua limpeza, os dados já foram. A correção dói também no sentido inverso: uma equipa que subiu um registo de ciclo de vida de 30 dias para um ano ficou com um atraso doze vezes maior na primeira limpeza seguinte.

Por isso defina o esqueleto para a janela mais longa que consiga imaginar razoavelmente, logo no primeiro dia. A cerca de 31 GB por ano é o seguro mais barato do sistema. Depois afine a janela do conteúdo, que é a parte cara e reversível.

Dois erros menores da mesma família. Confirme que a sua conservação documentada corresponde à configurada: um comentário a dizer "30 dias" por cima de uma definição cujo valor por omissão é um ano é como as duas divergem em silêncio. E mantenha as consultas do dia a dia fora das linhas de detalhe, com resumos diários para as perguntas frequentes, ou o seu registo fica tecnicamente completo e praticamente inútil.

## Perguntas frequentes

### Qual é um valor por omissão razoável se não sou regulado?

Esqueleto durante alguns anos, conteúdo durante três a seis meses. Cobre litígios, revisões de incidentes e discussões de faturas sem manter um armazém de dados pessoais.

### Tenho de guardar prompts e respostas?

Enquanto puder precisar de explicar uma decisão concreta, sim. Depois disso, a impressão digital leva a prova e o texto é só exposição.

### A regra dos seis meses aplica-se ao meu chatbot?

Quase de certeza que não. Aplica-se a sistemas de alto risco tal como o regulamento os define, e os agentes internos ou de produtividade correntes não estão nessa lista. Consulte a lista em vez de assumir, num sentido ou no outro.

### Para onde vai mesmo o armazenamento?

Para os conteúdos. Resultados de ferramentas e prompts dominam, sobretudo quando estão duplicados em vários sítios. O esqueleto estruturado é desprezável ao lado.

### Posso guardar tudo e decidir mais tarde?

É a opção que parece segura e não é. Conteúdo guardado muito tempo é um passivo permanente, e é a primeira coisa que um pedido de eliminação vai encontrar.

## O passo seguinte

Escreva dois números, um para o esqueleto e outro para o conteúdo, e faça com que o do esqueleto seja generoso. Depois confirme que o seu registo contém mesmo o que essas janelas devem proteger: [o que registar em cada execução de um agente de IA](/pt/blog/ai-agent-audit-trail).
`;

export default content;
