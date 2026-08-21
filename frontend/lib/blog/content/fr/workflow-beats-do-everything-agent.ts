// French translation of workflow-beats-do-everything-agent (public register,
// 2026-07-24). Keep the cents consistent with the English source:
// 19c agent / 2,3c workflow = ~8x; 9c cached agent = ~4x.
const content = `Un agent IA « bon à tout faire » coûte presque toujours plus cher que le même travail découpé en quelques étapes étroites. De combien, cela tient à une seule chose : le nombre de boucles que l'agent enchaîne avant d'avoir fini. Sur un travail rapide, la différence est minime. Sur un travail long et sinueux, l'agent peut coûter vingt ou trente fois plus.

Voilà la version honnête. Et d'abord, le chiffre que nous avons dû retirer.

## L'essentiel en bref

- L'écart de coût est réel, mais il dépend presque entièrement du nombre d'étapes de l'agent.
- Sur un ticket de support type, un agent revient à environ 19 centimes et un workflow découpé à environ 2.
- Activez le cache et l'agent tombe à environ 9 centimes, ce qui réduit l'écart de moitié.
- Travail court ou travail ouvert : construisez l'agent. Travail répété à forme connue : construisez le workflow.
- La fiabilité et l'effort de construction pèsent en général plus lourd que la facture de tokens.

## Le chiffre que nous avons retiré

Une version antérieure de cet article affirmait qu'un workflow découpé revient « environ dix fois moins cher » qu'un agent bon à tout faire. Nous l'avons supprimé. Il n'y avait aucun calcul derrière, aucune source, juste un chiffre qui sonnait juste.

Et il n'existe pas d'étude propre pour le remplacer. Personne n'a publié le même travail réel, construit des deux façons, avec les coûts mesurés côte à côte. Même le guide d'Anthropic, [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents), consacre deux phrases au sujet et zéro chiffre : les agents « échangent de la latence et du coût contre une meilleure performance », et leur autonomie « implique des coûts plus élevés ». C'est vrai, mais ce n'est pas un chiffre sur lequel bâtir un budget.

Tout ce qui suit est donc calculé à partir d'hypothèses vérifiables, pas emprunté au titre de quelqu'un d'autre.

## Pourquoi l'agent coûte plus cher

Une seule idée explique tout. Un modèle IA n'a pas de mémoire d'un appel à l'autre. Chaque fois que l'agent fait un pas de plus, il faut lui redonner toute la conversation : les instructions de départ, tous les outils qu'il pourrait utiliser, et tout ce qui s'est passé jusque-là.

La première boucle est donc bon marché. La deuxième relit la première. La troisième relit les deux premières. À la huitième, l'agent paie pour relire une pile croissante de son propre travail, encore et encore. Le coût ne s'additionne pas en ligne droite : il fait boule de neige.

Un workflow découpé évite la boule de neige. Chaque étape reçoit uniquement ce dont elle a besoin, le fait, et transmet un petit résultat propre à la suivante. L'étape quatre ne relit jamais les étapes un à trois. Il n'y a pas de pile qui grossit.

C'est tout le mécanisme. Le reste consiste à y mettre des euros.

## Un exemple réel : le tri du support

Prenons un travail courant. Un ticket de support arrive, et vous voulez le classer, consulter le compte du client, chercher dans vos articles d'aide, rédiger une réponse et la vérifier avant l'envoi.

| Approche | Coût par ticket |
|---|---|
| Un agent bon à tout faire | environ 0,19 $ |
| Workflow découpé | environ 0,023 $ |

Construit en un seul agent, ce ticket revient à environ 19 centimes. Construit en workflow (quatre petites étapes IA plus deux consultations ordinaires sans IA du tout), le même ticket revient à un peu plus de 2 centimes. Environ huit fois moins.

D'où vient l'écart ? L'agent boucle environ huit fois pour venir à bout du travail, et chaque boucle relit une transcription plus grosse que la précédente. Le workflow fait le même travail réel en quatre étapes ciblées, dont aucune ne porte le bagage des autres. Même réponse au bout, facture très différente. (Les prix utilisés ici sont les [tarifs publics des modèles](https://platform.claude.com/docs/en/about-claude/pricing) ; les vôtres seront différents.)

Une précision loyale avant d'encaisser ce facteur huit : les deux approches doivent quand même écrire la réponse finale, et écrire coûte la même chose des deux côtés. Ce brouillon final représente une bonne part des 2 centimes du workflow, et c'est pourquoi l'écart est d'environ huit fois, pas d'environ quatre-vingts.

![Une exécution de workflow Trinyx en vue observabilité : le graphe exécuté avec une coche verte sur chaque nœud, à côté d'un inspecteur listant l'epoch, ses horodatages de début et de fin, et le statut, la durée et le coût de chaque nœud.](/blog/ai-agent-audit-trail-run.png)

*Une exécution terminée, étape par étape, avec la durée et le coût en face de chacune. C'est cette vue par étape qui rend la facture explicable au lieu d'une somme unique.*

## Cela dépend surtout du nombre d'étapes

Le facteur huit n'est pas une loi. C'est ce que vous obtenez quand l'agent fait huit boucles. Changez le nombre de boucles et tout le tableau change.

| Étapes prises par l'agent | Surcoût approximatif de l'agent |
|---|---|
| 2 | à peu près identique (1,3x) |
| 8 | environ 8x de plus |
| 20 | environ 37x de plus |

C'est ce tableau qu'il faut lire comme le vrai titre. Un multiple de coût sans nombre d'étapes ne veut rien dire. Si l'on vous annonce « les agents coûtent 10x », votre première question doit être : sur un travail qui prend combien d'étapes ?

Il y a aussi une nuance de loyauté ici. La dernière ligne ne compte que si le travail nécessite vraiment vingt étapes. Un agent qui patauge sur vingt boucles là où un workflow propre en fait quatre n'est pas cher, il est perdu, et c'est un problème de qualité avant d'être un problème de coût.

## Quand un agent unique est le bon choix

Découper n'est pas toujours gagnant, et prétendre le contraire serait un argument de vente comme un autre.

| Situation | Construisez ceci | Pourquoi |
|---|---|---|
| Travail court, deux ou trois étapes | Un agent | L'écart est minime et un workflow coûte du temps de mise en place |
| Travail ouvert, impossible à scénariser | Un agent | Vous ne connaissez les étapes qu'une fois dedans |
| Chaque étape a besoin du même gros document | Un agent | Un workflow finit par le renvoyer à chaque étape |
| Travail répété à forme connue | Workflow | Le volume rembourse vite la structure |
| Tout ce qui ne doit jamais improviser son chemin | Workflow | Les branches sont fixées, pas choisies à l'exécution |

Sur le cas ouvert, l'autonomie achète de vrais résultats : Anthropic a constaté qu'une équipe d'agents en parallèle [dépassait un agent unique d'environ 90 % sur des questions de recherche difficiles](https://www.anthropic.com/engineering/multi-agent-research-system), en consommant bien plus de tokens pour cela. Quand la réponse compte plus que la facture, payez-la volontairement.

## Mettez l'agent en cache, et l'écart se réduit

Voici la concession que la plupart des argumentaires « les workflows sont 10x moins chers » passent sous silence. Cette boule de neige de relecture a un remède standard, le cache : le fournisseur laisse le modèle relire un texte déjà vu à prix fortement réduit.

Mettez correctement l'agent en cache et son coût sur notre exemple tombe d'environ 19 centimes à environ 9 centimes par ticket. L'écart avec le workflow passe d'environ huit fois à moins de quatre. Il reste un écart, mais bien plus modeste, et une comparaison honnête doit chiffrer l'agent ainsi plutôt que dans sa pire version sans cache.

Deux choses que le cache ne fait pas. Il aide peu sur les étapes très courtes, car il existe une taille minimale en dessous de laquelle la remise ne s'applique pas. Et il ne raccourcit pas la conversation, seulement le prix de sa relecture : un agent emballé peut donc quand même saturer sa fenêtre de contexte et perdre le fil.

## Ce qui décide vraiment

Prenez du recul : l'écart de coût, aussi réel soit-il, n'est presque jamais ce qui doit trancher.

Deux autres chiffres l'écrasent en général. D'abord la fiabilité : si une approche réussit plus souvent, et que quelqu'un doit rattraper chaque échec à la main, même un léger avantage de taux de réussite vaut bien plus que quelques centimes par ticket. Ensuite l'effort de construction : un workflow soigné en plusieurs étapes demande du vrai travail à construire et à maintenir, alors qu'un agent unique branché sur quelques outils se met en place bien plus vite. À des milliers de tickets par jour, le workflow rembourse cet effort rapidement. À quelques dizaines, jamais.

L'ordre des questions est donc : le travail a-t-il une forme connue, allez-vous le faire tourner en volume, et quelle approche échoue le moins ? Le multiple de coût ne compte qu'après, et il ne fait en général que confirmer ce que les deux premières ont déjà dit.

## Les questions qu'on nous pose

### Un workflow est-il toujours moins cher qu'un agent ?

Non. Sur un travail en deux étapes, la différence est proche de zéro, et si chaque étape a besoin du même gros document, le workflow peut coûter plus cher parce qu'il le renvoie à chaque fois.

### Pourquoi un agent devient-il plus cher au fil de l'exécution ?

Parce qu'il emmène toute sa conversation dans chaque nouvelle étape. L'étape huit paie pour relire les étapes une à sept : ce sont les dernières étapes qui coûtent cher.

### Le cache rend-il les agents aussi bon marché que les workflows ?

Il réduit l'écart de moitié dans notre exemple, il ne le referme pas. Le cache baisse le prix de la relecture, mais l'agent relit toujours bien plus de texte que n'importe quelle étape de workflow.

### Comment faire ce calcul sur mon propre cas ?

Mesurez trois choses avant de vous citer un chiffre : la taille réelle de vos prompts et de vos données, le nombre d'étapes que l'agent prend vraiment sur du travail réel (vos journaux le savent), et le taux de réussite de chaque approche. L'écart de coût en découle.

### Peut-on mélanger les deux ?

Oui, et la plupart des bons systèmes le font. Fixez la structure sous forme de workflow et laissez un petit agent traiter l'unique étape qui demande réellement du jugement.

## Pour les curieux

La seule ligne de calcul derrière la boule de neige : la lecture totale d'un agent croît à peu près comme le nombre d'étapes multiplié par lui-même, alors que celle d'un workflow croît en ligne droite. C'est pourquoi les deux s'éloignent d'autant plus que le travail est long.

## La prochaine étape

Sortez de vos journaux le nombre d'étapes d'un travail réel, puis relisez le tableau ci-dessus avec ce chiffre. Quelle que soit la forme retenue, posez-lui d'abord un plafond : [comment plafonner ce qu'un agent peut dépenser](/fr/blog/cap-ai-agent-cost-budgets).
`;

export default content;
