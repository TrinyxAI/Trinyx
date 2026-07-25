// Portuguese translation of chat-to-workflow-no-code (public register, 2026-07-24).
const content = `Não precisa de escrever código para construir uma automatização com IA. Descreve numa frase o que deve acontecer e recebe um workflow que pode ver, executar e alterar.

É essa a ideia da automatização com IA sem código: diga a tarefa em voz alta e fique com o sistema que recebe.

## Em resumo

- Descreva o resultado, não os passos. Da canalização trata a ferramenta.
- O que recebe é um diagrama, não uma caixa negra. Todos os passos estão no ecrã.
- Pode refinar de duas maneiras: continuar a conversar, ou abrir um passo e editá-lo.
- Mantenha uma aprovação humana antes de tudo o que é irreversível para um cliente.
- Umas linhas de código continuam a ser a resposta certa para trabalho exato e mecânico.

## Diga como é "concluído"

As pessoas chegam com um hábito das ferramentas de automatização antigas: pensar primeiro nos passos, escolher um gatilho, ligar o campo A ao campo B. Aqui é ao contrário.

Parta do resultado. Uma frase chega:

"Todas as manhãs, encontra os novos registos na minha tabela e envia a cada um uma mensagem de boas-vindas no Slack."

Isso descreve um objetivo e a forma do trabalho. O gatilho, o ciclo, a consulta e a escrita de volta são canalização, e é para isso que a ferramenta existe.

![Um chat do LiveContext com um pedido em linguagem corrente à esquerda, "todas as manhãs, encontra os novos registos na minha tabela e envia a cada um uma mensagem de boas-vindas no Slack", e à direita o workflow gerado na tela: um gatilho matinal, um passo que encontra os novos registos, percorre-os um a um, envia a mensagem de Slack e marca-os como recebidos.](/blog/chat-to-workflow-no-code-generated.png)

*Uma frase à entrada, um workflow legível à saída. O pedido à esquerda, os passos gerados à direita.*

## Recebe um diagrama, não uma caixa negra

Esta é a parte que conta muito mais do que parece.

Muitas ferramentas de IA escondem o trabalho. Escreve um pedido, acontece alguma coisa e cruza os dedos. Quando corre mal não há nada para inspecionar nem para corrigir, por isso a única opção é reformular e tentar de novo.

| | Um prompt numa caixa negra | Um workflow gerado |
|---|---|---|
| Vê os passos? | Não | Sim, todos |
| Pode mudar um passo só? | Não, só o prompt | Sim, abra-o e edite |
| Sabe porque fez aquilo? | Nem por isso | O caminho seguido fica registado |
| Comporta-se igual duas vezes? | Sem garantia | A estrutura é fixa |
| Pode passá-lo a um colega? | Só o prompt | O diagrama inteiro |

Se um passo existe, está na tela. Nada fica implícito.

## Altere a conversar ou à mão

A primeira versão raramente é a última, e é no refinamento que o sem código ganha o seu lugar. Tem duas formas de o fazer e pode misturá-las à vontade.

| Quer | Faça isto | Porquê |
|---|---|---|
| Acrescentar um ramo inteiro | Continue a conversar: "marca também como urgente tudo o que fale de reembolso" | Mudanças de estrutura são mais rápidas em palavras |
| Corrigir uma frase ou categoria | Abra o passo e edite | Preciso, sem reinterpretação |
| Reordenar passos | Qualquer uma | O diagrama é que manda |
| Mudar um limiar | Abra o passo | Quer o número exato, não uma paráfrase |

Ambos os caminhos escrevem no mesmo diagrama, por isso nenhum lhe fecha o outro.

## Quando ainda compensa uma linha de código

O sem código cobre a maior parte do trabalho. Fingir que cobre tudo é como estas ferramentas ganham má fama.

Use um passo de código quando a lógica é mecânica e exata:

- Reorganizar dados na estrutura precisa que o passo seguinte espera.
- Cálculo de datas, uma conta, um limiar sem qualquer ambiguidade.
- Interpretar um formato que mais nada reconhece.

Linguagem corrente para o julgamento. Umas linhas de código para a exatidão. Essa divisão aguenta-se na prática.

## Um exemplo concreto: triagem da caixa de suporte

Mesma ideia, tarefa um pouco maior. Chega um email de suporte e quer que seja triado, respondido e revisto.

| Passo | O que acontece | Quem decide |
|---|---|---|
| Gatilho | Chega um email novo à caixa de suporte | A caixa |
| Classificar | Um pequeno passo de IA lê-o e devolve uma etiqueta: erro, faturação ou geral | O modelo, só sobre esse email |
| Ramificar | O diagrama divide-se em três consoante a etiqueta | A estrutura, não o modelo |
| Redigir | Cada ramo escreve uma resposta no tom certo | O modelo |
| Rever | O rascunho espera por uma pessoa numa fila | Uma pessoa, sempre |
| Registar | O que entrou, a etiqueta, o ramo, o rascunho, quem aprovou | Registado automaticamente |

Repare em que decisões pertencem ao modelo e quais pertencem ao diagrama. O modelo lê e julga. A estrutura decide o que acontece a seguir. Essa separação é o que mantém tudo previsível, e é aprofundada em [workflow ou um único agente](/pt/blog/workflow-beats-do-everything-agent).

## Perguntas frequentes

### Preciso de saber o que é um gatilho ou um nó?

Não. Ajuda mais tarde, quando começar a editar passos diretamente, mas não precisa de nada disso para ter uma primeira versão funcional.

### E se o workflow gerado estiver errado?

Diga o que está errado e ele é reconstruído, ou abra o passo problemático e corrija-o. Como vê todos os passos, "errado" costuma ser um passo concreto e não um mistério.

### Isto não é só um prompt com passos a mais?

Não. Um prompt é uma chamada e uma saída. Um workflow é uma estrutura fixa com passos separados, ramos reais e o registo do caminho de cada execução, e é isso que permite depurá-lo um mês depois.

### Pode mexer em sistemas reais, como email ou Slack?

Sim, é essa a ideia. Ponha uma aprovação humana antes de tudo o que não se desfaz, como enviar a um cliente ou gastar dinheiro.

### Quanto custa executá-lo?

Menos do que entregar toda a tarefa a um único agente autónomo, na maioria dos casos, porque cada passo só vê o que precisa. Quanto menos depende do número de passos: [a comparação de custos](/pt/blog/workflow-beats-do-everything-agent) faz as contas com os números à vista.

## O passo seguinte

Escolha uma rotina que faça todas as semanas, escreva-a numa só frase e veja o que recebe. Depois mude uma coisa. É esse o ciclo inteiro, e demora uns dez minutos.
`;

export default content;
