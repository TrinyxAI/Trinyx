// Portuguese translation of size-an-ai-agent-budget (public register, 2026-07-24).
const content = `Pode pôr um orçamento num agente de IA. O difícil é saber que número escrever na caixa. Demasiado alto e nunca para nada. Demasiado baixo e mata trabalho que estava a correr bem.

É assim que se chega a um número defensável, sem um curso de estatística.

## Em resumo

- Parta do que um passo custa mesmo, não do que lhe parece prudente.
- Acrescente margem consoante o uso de ferramentas: cerca de 2x num passo de uma só chamada, 3x a 4x num passo com muitas ferramentas.
- Limitar quantas voltas um agente pode dar é uma péssima forma de limitar dinheiro.
- Nos passos baratos, limite a entrada. Nos caros, limite o dinheiro.
- O orçamento de uma execução não é a soma dos orçamentos dos passos, porque os passos repetem-se.

## Primeiro, saber quanto custa um passo

Os custos variam entre tipos de trabalho muito mais do que se espera. São exemplos de um modelo construído, não medições de produção, mas o importante é a distância entre eles.

| Tipo de passo | O que faz | Custo típico por execução |
|---|---|---|
| Classificar | Lê uma mensagem, devolve uma etiqueta | cerca de 0,0003 $ |
| Redigir com consulta | Vai buscar um documento, escreve uma resposta | cerca de 0,013 $ |
| Investigação com várias ferramentas | Umas seis chamadas a ferramentas e um resumo | cerca de 0,27 $ |
| Resumir um documento longo | Uma leitura grande, uma resposta | cerca de 0,04 $ |
| Passo de navegador | Uma dúzia de ações de página, cada uma com a sua captura | cerca de 1,67 $ |

Entre um passo de classificação e um de navegador há mais de mil vezes de diferença. Um único orçamento para ambos não quer dizer nada, e é por isso que os orçamentos se põem por passo e não por agente.

## A sua margem não é 2x

Quase toda a gente pega no custo típico e duplica-o. Está mais ou menos certo para um passo que faz uma chamada e para. Está muito errado para tudo o que use ferramentas.

A razão é que cada resultado de ferramenta é arrastado para todas as chamadas seguintes, por isso o custo não cresce ao ritmo do número de chamadas. Cresce mais depressa. Duplicar as chamadas a ferramentas num passo intensivo pode quadruplicar aproximadamente o seu custo.

| Tipo de passo | Se der o dobro dos passos habituais | Margem a prever |
|---|---|---|
| Uma chamada, sem ferramentas | Cerca do dobro do custo | 2x |
| Redação com uma ou duas consultas | Cerca de três vezes e meia | 3x a 4x |
| Investigação ou navegação intensivas | Cerca de quatro vezes | 3x a 4x |

A conclusão prática é a mesma em todos os casos: "vamos subir um pouco o máximo de iterações" não é uma mudança pequena. É a decisão de quadruplicar aproximadamente o teto.

![A vista de métricas de agentes do Trinyx: uma linha de resumo com execuções totais, tokens, chamadas a ferramentas e taxa de sucesso, sobre uma tabela por agente com execuções, tokens, chamadas a ferramentas, créditos gastos, modelo, duração e taxa de sucesso.](/blog/cap-ai-agent-cost-budgets-metrics.png)

*Despesa, tokens e chamadas a ferramentas por agente, com execuções reais. É esta a entrada do dimensionamento: o número que definir deve sair da sua própria distribuição, não de uma intuição.*

## Porque é que um teto de iterações limita mal o dinheiro

Muitas ferramentas só deixam limitar o número de voltas. Parece um limite. Faça as contas e quase não é.

| Passo | Custo esperado | Custo se atingir um teto de 100 voltas |
|---|---|---|
| Investigação com várias ferramentas | cerca de 0,27 $ | cerca de 47 $ |
| Passo de navegador | cerca de 1,67 $ | cerca de 101 $ |

Um teto que permite sessenta vezes a fatura esperada não o protege de nada. Se o seu único controlo é um contador de voltas, ponha-o perto do que o trabalho real precisa (umas poucas chamadas para consultas simples, dez a quinze para uma comparação) e não num número redondo como 100.

## Passos baratos: limite a entrada. Passos caros: limite o dinheiro.

Existe um piso abaixo do qual um limite em dinheiro não pode funcionar fisicamente.

Um orçamento só pode recusar a chamada *seguinte*, por isso precisa de espaço para várias chamadas antes do teto. Regra aproximada: o orçamento deve valer pelo menos três vezes a maior chamada possível do passo. Abaixo disso, a primeira chamada pode rebentar o teto e o orçamento nunca chega a agir.

Nos passos baratos esse piso fica acima do que o passo custa, por isso um limite em dinheiro é teatro. O que funciona ali é limitar o que entra: restrinja quanto texto é entregue ao passo e quanto ele pode escrever de volta. Com isso, a pior chamada cai uma ordem de grandeza e o piso desce com ela.

| Tipo de passo | O controlo que funciona | Porquê |
|---|---|---|
| Classificação, consultas curtas | Limitar o tamanho de entrada | O passo já está limitado; um limite em dinheiro não morde |
| Trabalho com documentos longos | Limitar o tamanho de entrada | Uma só chamada grande: a entrada *é* o custo |
| Investigação, navegação, tudo o que repete | Limitar o dinheiro | O custo vem da repetição, que só o dinheiro limita |

## O orçamento de execução não é a soma dos passos

É aqui que um dimensionamento cuidadoso costuma partir-se.

Os passos repetem-se. Um passo dentro de um ciclo sobre cinquenta itens corre cinquenta vezes. Um ramo que se abre corre uma vez por ramo. Por isso o teto da execução tem de ser calculado ao longo do caminho mais caro do workflow, contando repetições, e não somando um orçamento por passo desenhado na tela.

E quando uma execução se abre, recuse-a antes de começar em vez de a interromper a meio. Cortar uma abertura em marcha deixa um subconjunto arbitrário de ramos terminados, e quais sobrevivem depende da ordem de arranque. Recusar à partida deixa algo que se pode repetir.

## Como escolher o número

1. **Junte algumas execuções reais.** De cada passo: tokens de entrada, tokens de saída, quantas chamadas a ferramentas, que modelo e como terminou.
2. **Não dimensione pela média.** Os custos são enviesados: a maioria das execuções é barata e algumas são caras, por isso a média fica muito abaixo do centro do risco. Dimensionar por ela mata cerca de um terço do trabalho legítimo.
3. **Seja honesto quanto à amostra.** São precisas algumas centenas de execuções antes de falar de um pior caso sem corar. Abaixo disso, dimensione pelo pior caso estrutural (a maior chamada que o modelo pode fisicamente fazer) em vez de fingir que tem uma distribuição.
4. **Atenção à acumulação.** Um limite que mata 5 % dos passos parece tolerável, até ter dez passos: isso são 40 % de execuções a bater num limite algures. Os limites por passo têm de ser muito mais folgados do que a sua tolerância ao nível da execução.
5. **Teste-o.** Sobrecarregue de propósito um passo e confirme que recebe uma recusa limpa que nomeia o limite. Um limite por testar é uma intuição com um número em cima.

## Perguntas frequentes

### Qual é um orçamento inicial razoável para um agente?

Pegue no custo esperado do seu passo mais caro, multiplique por três ou quatro se usar ferramentas, e aplique isso por passo. Depois defina um orçamento de execução ao longo do caminho mais longo, contando tudo o que repete.

### Porque não pôr um orçamento generoso e esquecer?

Porque um orçamento generoso só dispara depois do estrago. O valor de um teto é a execução que recusa, e um teto posto a sessenta vezes o custo esperado não vai recusar nada que valha a pena.

### O meu agente bate sempre no orçamento. Subo ou corrijo?

Veja o que mudou antes de subir seja o que for. Bater no limite significa quase sempre que a entrada cresceu ou que o agente começou a andar às voltas, e ambos pedem correção e não financiamento.

### Orçamento por passo ou basta um por agente?

Por passo, se os passos forem de naturezas diferentes. Entre classificar e navegar há mil vezes de diferença de custo, e um só número não pode estar certo para os dois.

### Com que frequência rever estes números?

Sempre que mudar de modelo, o tamanho dos prompts ou o que o passo tem permissão para fazer. As três coisas mexem no custo, e um orçamento afinado pela forma do trimestre passado ou deixa fugir ou estrangula.

## O passo seguinte

Dimensionar só serve se o teto conseguir mesmo parar uma execução. Verifique primeiro esse lado: [como evitar que um agente gaste demais](/pt/blog/cap-ai-agent-cost-budgets) explica de que é feito um teto a sério e como provar que o seu funciona.
`;

export default content;
