// Portuguese translation of cap-ai-agent-cost-budgets (public register, 2026-07-24).
const content = `Quase todas as surpresas na fatura de IA têm a mesma causa: um agente sem teto. Deu voltas, tentou de novo, arrastou uma conversa cada vez maior, e ninguém deu por isso até chegar a fatura.

A solução não é um modelo melhor nem um prompt melhor. É um limite que recusa a chamada seguinte, e a maioria das coisas a que se chama orçamento não faz isso.

## Em resumo

- Um alerta diz-lhe o que já gastou. Não é um limite.
- O limite de despesa do seu fornecedor é normalmente um aviso, não uma paragem seca.
- Nenhum orçamento consegue parar a chamada que já está a fazer. O pior caso real é o seu orçamento mais uma chamada.
- A maioria das frameworks de agentes não traz limite de custo nenhum, ou traz um que conta chamadas em vez de dinheiro.
- O teste que interessa: o seu limite alguma vez recusou alguma coisa?

## Um alerta não é um limite

Um monitor corre depois de o dinheiro sair. Um limite corre antes da chamada seguinte e diz que não. Ambos são úteis, mas só um é um controlo.

| | Um monitor | Um limite a sério |
|---|---|---|
| Quando atua | Depois de a chamada fechar | Antes de a seguinte começar |
| O que pode fazer | Avisá-lo | Recusar |
| Pior caso | Ilimitado | Mais uma chamada |
| Para que serve | Dimensionar o limite, detetar desvios | Parar a execução |

Aqui vai um teste que pode fazer hoje e que não precisa de limiar nenhum: consulte as recusas registadas pelo seu limite atual. Alguma vez recusou algo? Um número que nunca recusou uma única chamada não é um controlo, é um comentário.

![A vista de métricas de agentes do LiveContext: uma linha de resumo com execuções totais, tokens, chamadas a ferramentas e taxa de sucesso, sobre uma tabela por agente com execuções, tokens, chamadas a ferramentas, créditos gastos, modelo, duração e taxa de sucesso.](/blog/cap-ai-agent-cost-budgets-metrics.png)

*A despesa por agente, a posteriori. É exatamente a vista certa para decidir um limite e exatamente a errada para parar uma execução.*

## O que faz mesmo o limite do seu fornecedor

Assume-se que o número no painel do fornecedor é uma parede. Quase sempre é uma campainha.

| Controlo do fornecedor | O que é na verdade |
|---|---|
| Limite de despesa de projeto ou organização na OpenAI | Um orçamento suave por omissão: avisa e os pedidos continuam a passar. Existe uma paragem dura como opção separada que tem de ser ativada, e então recusa chamadas até subir o limite |
| API de Spend Limits da Anthropic | Só planos Enterprise, só mensal, e cobre o uso das licenças humanas e não a despesa de API dos agentes |
| Teto mensal por escalão na Anthropic | Um teto real, mas de toda a organização e mensal: uma execução descontrolada transforma um erro de custo numa falha para todos |

Fontes: o [guia de spend limits da OpenAI](https://developers.openai.com/api/docs/guides/spend-limits), a [API de Spend Limits](https://platform.claude.com/docs/en/manage-claude/spend-limits-api) e os [limites de taxa](https://platform.claude.com/docs/en/api/rate-limits) da Anthropic. A própria documentação da Anthropic vai mais longe e desaconselha usar o seu número de despesa como porta: pode ler zero quando o valor não está disponível, por isso deve ser tratado como informativo.

Daqui saem duas conclusões. Os limites do fornecedor são uma rede de segurança, não a primeira linha de defesa. E um teto mensal de toda a organização tem a forma errada para parar uma execução defeituosa: quando dispara, leva tudo o resto à frente.

## Não consegue parar a chamada que já está a fazer

Esta é a parte que qualquer artigo honesto sobre orçamentos tem de dizer.

Só sabe quanto custou uma chamada depois de ela acabar. Por isso nenhum orçamento em execução consegue impedir que uma chamada cara rebente o teto. Só consegue impedir a seguinte. O pior caso real é o orçamento mais uma chamada.

Isto tem uma consequência prática. Se uma única chamada puder custar metade do seu orçamento, o orçamento não pode funcionar. Um teto só se comporta como teto quando é confortavelmente maior do que a maior chamada possível do agente, e uma regra de três vezes é um piso razoável. Dimensionar isso bem é um tema próprio: [quanto orçamentar por agente](/pt/blog/size-an-ai-agent-budget) faz as contas.

Também significa que uma boa implementação prevê antes de gastar. Olha para o que custaram os últimos passos, para a velocidade a que crescem e para a maior chamada que aquele modelo poderia fisicamente fazer, e recusa quando a projeção rebentaria o teto. Prever é todo o truque, porque medir chega sempre tarde.

## O que as ferramentas populares limitam mesmo

Se assume que a sua framework o protege, confirme. A maioria limita outra coisa que não dinheiro, e quase todas vêm sem limite.

| Ferramenta | O que limita | Por omissão |
|---|---|---|
| Claude Agent SDK | Dólares por execução e turnos | Ambos ilimitados |
| API Messages da Anthropic | Tokens por resposta | Sem valor por omissão, tem de o definir |
| Conta OpenAI | Dólares por mês | Suave, apenas aviso |
| OpenAI Agents SDK | Número de turnos | 10 |
| LangGraph | Número de passos | Documentado como 25 nuns sítios e 1000 noutros |
| Middleware do LangChain | Número de chamadas, sem orçamento de custo ou tokens | Sem limite |
| Pydantic AI | Tokens, pedidos, chamadas a ferramentas | 50 pedidos, sem limite de tokens |
| CrewAI | Iterações | 20 ou 25, conforme a página de documentação |

Três coisas a retirar dessa tabela.

**Quase tudo vem ilimitado.** O pressuposto seguro é que não tem teto nenhum até o pôr.

**Contar chamadas não é um orçamento.** Dez chamadas podem custar um cêntimo ou dez euros conforme o texto que cada uma carrega. O middleware do LangChain limita números de chamadas e não tem qualquer orçamento de tokens ou de custo.

**Um limite que não chega aos subagentes é decorativo.** É a forma mais comum de um teto se revelar falso: o processo pai é configurado com um limite, lança filhos, e os filhos correm com os valores por omissão. Há casos documentados em frameworks muito usadas. Se tirar uma só ação deste artigo, que seja esta: ponha um limite no pai, lance um filho e prove que ele o herda.

## Quatro regras para um orçamento que funciona

1. **Limite dinheiro ou tokens, não passos.** O preço de um passo flutua. O de um euro não.
2. **Dê um teto a cada passo e outro à execução inteira.** Uma execução que se abre em cinquenta ramos paralelos pode respeitar todos os orçamentos de passo e ainda assim custar cinquenta vezes o previsto.
3. **Reserve antes de lançar, não interrompa a meio.** Cortar ramos em marcha deixa meio resultado arbitrário. Recusar arrancar é explícito e pode ser repetido.
4. **Quando o teto disparar, guarde o trabalho feito.** Uma paragem que deita fora tudo o que foi produzido transforma um problema de custo numa perda total, e é exatamente por isso que as equipas desligam os tetos.

Este último merece uma linha só para si. Uma paragem por orçamento deve devolver o que o agente produziu, mais o detalhe do que gastou e da razão da paragem, e deve nomear que teto disparou. Uma paragem que só diz "orçamento excedido" não lhe dá nada com que agir.

## Até onde vai isto na prática?

Não existe qualquer taxa publicada sobre a frequência com que os agentes descarrilam em produção, por isso desconfie de qualquer frequência dita com segurança. O que está documentado é a ordem de grandeza, e é mais modesta do que a lenda.

Os incidentes registados situam-se entre algumas centenas e alguns milhares de dólares: cerca de 2.150 dólares de despesa indesejada num caso, 235 dólares em quatro dias por um só utilizador, um excesso de 70 % sobre um orçamento definido. Entretanto, a história mais republicada do setor, um anónimo "gastámos 47.000 dólares em agentes de IA", não nomeia empresa nenhuma, não mostra fatura nenhuma, e os seus próprios números semanais somam 25.658 dólares, não 47.000.

O risco a sério não é uma fatura espetacular. É uma fuga discreta e recorrente de alguns milhares, que ninguém atribui a nada, mês após mês.

## Perguntas frequentes

### Definir um máximo de tokens limita os meus custos?

Só o tamanho de cada resposta. Não faz nada contra o número de voltas do agente, que é de onde vem a despesa descontrolada.

### Devo usar o limite de despesa do meu fornecedor?

Sim, como rede de segurança, e ative a versão dura se o fornecedor a oferecer. Só não o tome como o seu controlo: costuma ser mensal, de toda a organização e suave por omissão.

### Qual é um orçamento inicial razoável?

Pelo menos três vezes a maior chamada possível do agente, senão pode rebentar antes sequer de ter hipótese de recusar. Comece aí e ajuste com execuções reais.

### O meu teto nunca disparou. É bom sinal?

Quer dizer que não foi testado, não que funcione. Ponha um orçamento propositadamente minúsculo num agente de teste e confirme que recebe uma recusa limpa que nomeia o limite disparado.

### Os detetores de ciclos substituem os orçamentos?

Não, respondem a outra pergunta. Um detetor de ciclos limita quantas vezes algo se repete. Um orçamento limita quanto podem custar essas repetições. Quer os dois.

## O passo seguinte

Verifique três coisas esta semana: se o seu teto é de dinheiro e não de número de chamadas, se chega aos subagentes, e se alguma vez recusou algo. Depois escolha o número com [quanto orçamentar por agente de IA](/pt/blog/size-an-ai-agent-budget).
`;

export default content;
