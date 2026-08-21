// Portuguese translation of from-dataset-to-live-workflow (public register,
// 2026-07-24). Structure identical to the English source.
const content = `Um conjunto de dados não serve de nada até que algo o leia com regularidade, decida o que mudou e aja. É assim que se passa de um ficheiro que verifica à mão para um workflow que se verifica a si próprio.

O exemplo que percorre o artigo é uma vigilância de preços: seguir alguns produtos, reparar quando um se mexe e avisar alguém antes que custe dinheiro. A forma serve para tudo o que tenha um ritmo.

## Em resumo

- Escolha uma fonte que mude num ritmo que consiga prever.
- Limpe uma única vez, logo à entrada, para que todos os passos seguintes possam confiar nela.
- Calcule a decisão primeiro e ramifique sobre a decisão, não sobre valores em bruto.
- Ponha uma aprovação humana à frente de tudo o que não se desfaz.
- Escreva o resultado de volta, para que a execução seguinte saiba o que fez a anterior.

## A construção, em seis passos

| Passo | O que faz | Porque está ali |
|---|---|---|
| 1. Agendamento | Dispara de hora a hora | O ritmo. Ninguém tem de se lembrar de começar |
| 2. Leitura | Consulta a fonte em direto | É aqui que entram dados frescos |
| 3. Limpeza | Reduz tudo aos mesmos poucos campos | Tudo a jusante deixa de adivinhar |
| 4. Consulta | Verifica se já viu este item | Evita duplicados e dá o valor anterior |
| 5. Decisão | Mexeu-se mais de 5 %? | A pergunta a sério |
| 6. Aprovar e agir | Uma pessoa confirma e só então saem o alerta e a escrita | A parte irreversível, controlada |

![O construtor de workflows do Trinyx a mostrar na tela o grafo de vigilância de preços com oito nós: um gatilho horário, uma chamada HTTP, um nó de código, uma consulta em tabela e uma decisão que separa um SKU novo de um conhecido, depois uma decisão de variação de preço, uma porta de aprovação e a escrita protegida.](/blog/from-dataset-to-live-workflow-builder.png)

*A construção toda numa só tela: do gatilho horário à esquerda à escrita com aprovação à direita.*

## Passo 1: escolha uma fonte com ritmo

Automatize dados que mudem num ritmo que consiga nomear. Não "semanalmente", mas "um CSV por fornecedor, por email, todas as segundas antes das 9". Essa precisão decide o seu gatilho.

Se a fonte quase nunca muda, não precisa de um workflow. Precisa de uma consulta, e poupa o esforço.

## Passos 2 e 3: ler e limpar uma só vez

As fontes em bruto são desarrumadas. Os nomes das colunas variam, as datas chegam em três formatos, um fornecedor escreve "preço unitário" e outro "preço/un".

Faça a limpeza num único sítio, mesmo onde os dados entram. Decida primeiro a forma que quer (para a vigilância de preços: produto, preço, moeda, visto-em) e faça cada fonte produzir essa forma e mais nada. Todos os passos seguintes ficam mais simples, porque podem confiar na entrada.

Um aviso que apanha toda a gente: uma leitura falhada chega muitas vezes disfarçada de sucesso. Muitos serviços devolvem uma mensagem de erro dentro de uma resposta perfeitamente normal. Confirme que o que voltou são mesmo os dados antes de os passar adiante, ou a falha desce em silêncio por todo o workflow.

## Passos 4 e 5: decidir e depois ramificar

O objetivo do workflow é uma decisão, por isso torne a decisão explícita.

A armadilha é ramificar sobre o valor em bruto. Não interessa que o preço seja 12,40. Interessa se subiu mais do que a sua tolerância desde a última vez. Calcule isso primeiro e ramifique sobre a resposta.

Também tem um lado muito prático. Filtros que parecem numéricos são muitas vezes comparados como texto por dentro, e o texto não ordena como os números: "100" vem antes de "9". Um filtro de "preço maior que 9" pode falhar em silêncio o 100 que lhe interessava. Vá buscar o valor anterior, faça a conta num passo de decisão explícito e ramifique sobre isso.

## Passo 6: controle o irreversível

O último passo deve fazer algo real: enviar o alerta, atualizar a linha, abrir o pedido, preparar a encomenda.

Quando essa ação é cara ou sem retorno, ponha uma aprovação humana à frente. A execução pausa, espera por uma pessoa e depois continua exatamente onde parou. Ações baratas e reversíveis podem correr sem vigilância. Tudo o que chega a um cliente ou gasta dinheiro passa por uma porta.

Duas coisas sobre a pausa. Aprovar duas vezes não estraga nada: vale a primeira resposta. E a execução agendada seguinte não atropela uma decisão que alguém ainda está a pensar: cada execução guarda os seus próprios resultados.

## A única proteção que torna segura uma execução repetida

Um gatilho horário repete a mesma leitura todas as horas. Sem proteção, insere a mesma linha todas as horas e a sua tabela enche-se de duplicados.

O padrão que resolve isto, em qualquer ferramenta: **procurar primeiro, decidir depois, escrever no fim**. Procure o item. Se a contagem for zero, é novo, então escreva. Caso contrário já existe, então atualize. Nunca insira sem condição quando o mesmo item pode ser lido de novo.

Essa procura serve duas coisas. É a sua proteção contra duplicados e é também de onde vem o valor da semana passada, o que torna a pergunta "mexeu-se?" respondível.

## Quatro armadilhas que custam uma tarde

| Armadilha | O que vê | O que se passa na verdade |
|---|---|---|
| Resultado vazio silencioso | Um passo não devolve nada, sem erro | Os dados estão um nível mais abaixo do que esperava |
| Leitura falhada com ar normal | Tudo a jusante fica errado | O erro veio dentro de uma resposta normal |
| Número comparado como texto | Um limiar falha casos em silêncio | "100" ordena antes de "9" |
| Duplicados de hora a hora | A tabela cresce sem parar | Falta a proteção de procurar antes de escrever |

Nenhum destes casos lança um erro. É exatamente por isso que custam uma tarde.

## Prove cada ramo antes de dizer que está no ar

Não publique só pelo caminho feliz. Provoque cada caso de propósito e veja o que o workflow fez mesmo.

| Teste | O que provoca | O que deve acontecer |
|---|---|---|
| Item novo | Um item sem histórico | Exatamente uma linha escrita |
| Sem alteração | Item conhecido, preço estável | Nada enviado, nada escrito |
| Alteração real | Item conhecido, preço 10 % acima | A execução pausa para aprovação |
| Recusa | Recuse a aprovação | Sem alerta e sem escrita |
| Correr duas vezes | Dispare o agendamento outra vez | O número de linhas não muda |

Se o caso de "alteração real" terminar sem pausar, o seu limiar está a ser avaliado num sítio que não pretendia. É a falha que compensa apanhar antes de estar no ar e não depois.

## Perguntas frequentes

### Com que frequência deve correr?

Ao ritmo da fonte. De hora a hora para preços, diariamente para um relatório, semanalmente para um ficheiro de fornecedor. Correr mais vezes do que os dados mudam custa chamadas e não acrescenta nada.

### Onde guardo o histórico?

Numa tabela que o próprio workflow lê e escreve. É isso que transforma execuções soltas em algo com memória: sabe o que já tratou e tem o valor de ontem para comparar.

### O que acontece se uma execução falhar a meio?

A execução para no passo que falhou, e o registo mostra qual foi e o que tinha recebido. Corrige esse passo e volta a correr, em vez de raciocinar sobre o conjunto.

### Preciso mesmo de uma pessoa no processo?

Para tudo o que é irreversível, sim, pelo menos até ganhar confiança. Enviar automaticamente com base numa leitura errada é como a automatização ganha má fama. Comece com a porta e retire-a mais tarde se os factos o justificarem.

## O passo seguinte

Escolha uma fonte que já verifica à mão todas as semanas. Anote a decisão que ela alimenta, o limiar que usa e o que faz quando é ultrapassado. Esse é o workflow, e já o desenhou. Depois veja [o que registar](/pt/blog/ai-agent-audit-trail) para poder responder pelo que ele fez.
`;

export default content;
