// Portuguese translation of workflow-beats-do-everything-agent (public register,
// 2026-07-24). Keep the cents consistent with the English source.
const content = `Um agente de IA que faz tudo custa quase sempre mais do que a mesma tarefa dividida em alguns passos estreitos. Quanto mais depende de uma só coisa: quantas voltas o agente dá até terminar. Numa tarefa curta, quase não há diferença. Numa longa e sinuosa, o agente pode custar vinte ou trinta vezes mais.

Esta é a versão honesta. E, primeiro, o número que tivemos de retirar.

## Em resumo

- A diferença de custo é real, mas depende quase por inteiro de quantos passos o agente dá.
- Num pedido de suporte típico, um agente custa cerca de 19 cêntimos e um workflow dividido cerca de 2.
- Com cache, o agente desce para cerca de 9 cêntimos, o que reduz a diferença a metade.
- Tarefa curta ou aberta: construa o agente. Tarefa repetida com forma conhecida: construa o workflow.
- A fiabilidade e o esforço de construção pesam normalmente mais do que a fatura de tokens.

## A afirmação que apagámos

Uma versão anterior deste artigo dizia que um workflow dividido sai "cerca de dez vezes mais barato" do que um agente que faz tudo. Apagámo-la. Não havia conta nenhuma por trás nem fonte, apenas um número que soava bem.

Também não há um estudo limpo para o substituir. Ninguém publicou a mesma tarefa real, construída das duas formas, com os custos medidos lado a lado. Até o guia da Anthropic, [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents), dedica ao tema duas frases e zero números: os agentes "trocam latência e custo por melhor desempenho" e a sua autonomia "implica custos maiores". Verdade, mas não é um número com que se planeie.

Por isso tudo o que se segue está calculado a partir de pressupostos verificáveis, e não retirado do título de outra pessoa.

## Porque é que o agente custa mais

Uma única ideia explica tudo. Um modelo de IA não tem memória entre chamadas. Sempre que o agente dá mais um passo, é preciso entregar-lhe outra vez a conversa inteira: as instruções iniciais, todas as ferramentas que possa usar e tudo o que aconteceu até ali.

A primeira volta é barata. A segunda relê a primeira. A terceira relê as duas anteriores. À oitava, o agente paga para reler uma pilha crescente do seu próprio trabalho, vezes sem conta. O custo não se soma em linha reta: faz bola de neve.

Um workflow dividido evita a bola de neve. Cada passo recebe só o que precisa, faz o seu trabalho e entrega um resultado pequeno e limpo ao seguinte. O passo quatro nunca relê os passos um a três. Não há pilha a crescer.

É esse todo o mecanismo. O resto é pôr-lhe euros.

## Um exemplo real: triagem de suporte

Peguemos numa tarefa comum. Chega um pedido de suporte e quer classificá-lo, consultar a conta do cliente, procurar nos seus artigos de ajuda, redigir uma resposta e revê-la antes de enviar.

| Abordagem | Custo por pedido |
|---|---|
| Um agente que faz tudo | cerca de 0,19 $ |
| Workflow dividido | cerca de 0,023 $ |

Construído como um único agente, esse pedido custa cerca de 19 cêntimos. Construído como workflow (quatro pequenos passos de IA mais duas consultas normais sem IA nenhuma), o mesmo pedido custa pouco mais de 2 cêntimos. Cerca de oito vezes menos.

De onde vem a diferença? O agente dá cerca de oito voltas para terminar e cada volta relê uma transcrição mais gorda do que a anterior. O workflow faz o mesmo trabalho real em quatro passos focados, nenhum deles a carregar a bagagem dos outros. Mesma resposta no fim, fatura muito diferente. (Os preços usados são as [tarifas públicas dos modelos](https://platform.claude.com/docs/en/about-claude/pricing); os seus serão outros.)

Uma nota justa antes de contar com essas oito vezes: as duas abordagens têm na mesma de escrever a resposta final, e escrever custa o mesmo nos dois casos. Esse rascunho final é boa parte dos 2 cêntimos do workflow, e é por isso que a diferença é de cerca de oito vezes e não de cerca de oitenta.

![Uma execução de workflow do LiveContext na vista de observabilidade: o grafo executado com um visto verde em cada nó, ao lado de um inspetor que lista a época, as marcas de início e fim, e o estado, a duração e o custo de cada nó.](/blog/ai-agent-audit-trail-run.png)

*Uma execução concluída, passo a passo, com a duração e o custo de cada um. É essa vista por passo que torna a fatura explicável em vez de uma soma única.*

## Depende sobretudo do número de passos

O valor de oito vezes não é uma lei. É o que dá quando o agente faz oito voltas. Mude o número de voltas e muda o quadro todo.

| Passos dados pelo agente | Quanto mais custa o agente, aproximadamente |
|---|---|
| 2 | praticamente igual (1,3x) |
| 8 | cerca de 8x mais |
| 20 | cerca de 37x mais |

Leia essa tabela como o verdadeiro título. Um múltiplo de custo sem número de passos não quer dizer nada. Se lhe disserem "os agentes custam 10x", a primeira pergunta deve ser: numa tarefa de quantos passos?

Aqui há também uma nuance de honestidade. A última linha só conta se a tarefa precisar mesmo de vinte passos. Um agente que se enrola em vinte voltas para fazer o que um workflow limpo faz em quatro não é caro, está perdido, e isso é um problema de qualidade antes de ser de custo.

## Quando um único agente é a escolha certa

Dividir nem sempre ganha, e fingir o contrário seria mais um argumento de venda.

| Situação | Construa isto | Porquê |
|---|---|---|
| Tarefa curta, dois ou três passos | Um agente | A diferença é mínima e um workflow custa tempo de montagem |
| Trabalho aberto, impossível de guionizar | Um agente | Só conhece os passos quando já lá está dentro |
| Todos os passos precisam do mesmo documento grande | Um agente | Um workflow acaba por o reenviar em cada passo |
| Tarefa repetida com forma conhecida | Workflow | O volume paga a estrutura depressa |
| Tudo o que nunca deve improvisar o caminho | Workflow | Os ramos estão fixos, não são escolhidos na hora |

No caso aberto, a autonomia compra resultados reais: a Anthropic verificou que uma equipa de agentes em paralelo [superou um agente único em cerca de 90 % em perguntas de investigação difíceis](https://www.anthropic.com/engineering/multi-agent-research-system), gastando muitos mais tokens para isso. Quando a resposta conta mais do que a fatura, pague-a de propósito.

## Com cache, a diferença encolhe

Aqui está a concessão que a maioria dos argumentos "os workflows são 10x mais baratos" salta. Essa bola de neve de releitura tem um remédio padrão, a cache: o fornecedor deixa o modelo reler texto já visto com um desconto forte.

Com cache bem feita, o custo do agente no nosso exemplo desce de cerca de 19 para cerca de 9 cêntimos por pedido. A diferença face ao workflow cai de cerca de oito vezes para menos de quatro. Continua a haver diferença, mas bem menor, e uma comparação honesta tem de valorizar o agente assim e não na sua pior versão sem cache.

Duas coisas que a cache não faz. Ajuda pouco em passos muito curtos, porque há um tamanho mínimo abaixo do qual o desconto não se aplica. E não encurta a conversa, apenas o preço de a reler, por isso um agente descontrolado pode continuar a encher a janela de contexto e a perder o fio.

## O que decide mesmo

Um passo atrás: a diferença de custo, por real que seja, raramente deve ser o que decide.

Costumam pesar mais dois outros números. Primeiro a fiabilidade: se uma abordagem acerta mais vezes e alguém tem de corrigir cada falha à mão, mesmo uma pequena vantagem na taxa de acerto vale muito mais do que uns cêntimos por pedido. Segundo o esforço de construção: um workflow cuidado de vários passos exige trabalho real de construir e manter, enquanto um agente único ligado a algumas ferramentas se monta muito mais depressa. Com milhares de pedidos por dia, o workflow paga esse esforço rapidamente. Com algumas dezenas, nunca.

A ordem das perguntas é, então: a tarefa tem forma conhecida, vai correr em volume, e qual das duas falha menos? O múltiplo de custo só conta depois e, a essa altura, costuma limitar-se a confirmar o que as duas primeiras já disseram.

## Perguntas frequentes

### Um workflow é sempre mais barato do que um agente?

Não. Numa tarefa de dois passos a diferença é quase nula e, se cada passo precisar do mesmo documento grande, o workflow pode custar mais porque o reenvia sempre.

### Porque é que um agente fica mais caro à medida que avança?

Porque arrasta toda a conversa para cada passo novo. O passo oito paga para reler os passos um a sete, por isso os passos finais são os caros.

### A cache torna os agentes tão baratos como os workflows?

Reduz a diferença a metade no nosso exemplo, não a fecha. A cache baixa o preço da releitura, mas o agente continua a reler muito mais texto do que qualquer passo de um workflow.

### Como faço esta conta para o meu caso?

Meça três coisas antes de se citar qualquer número: o tamanho real dos seus prompts e dados, quantos passos o agente dá mesmo em trabalho real (os seus registos sabem) e com que frequência cada abordagem acerta. A diferença de custo sai daí.

### Posso misturar as duas?

Sim, e a maioria dos bons sistemas fá-lo. Fixe a estrutura como workflow e deixe um pequeno agente tratar do único passo que exige mesmo critério.

## Para curiosos

A única linha de matemática por trás da bola de neve: a leitura total de um agente cresce aproximadamente com o número de passos multiplicado por si próprio, enquanto a de um workflow cresce em linha reta. É por isso que se afastam cada vez mais quanto mais longa for a tarefa.

## O passo seguinte

Vá aos seus registos buscar o número de passos de uma tarefa real e volte a ler a tabela acima com esse número. Escolha a forma que escolher, ponha-lhe primeiro um teto: [como limitar o que um agente pode gastar](/pt/blog/cap-ai-agent-cost-budgets).
`;

export default content;
