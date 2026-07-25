// French translation of size-an-ai-agent-budget (public register, 2026-07-24).
// Sizing half of the budget pair. Structure identical to the English source.
const content = `Vous pouvez poser un budget sur un agent IA. Le difficile est de savoir quel chiffre écrire dans la case. Trop haut, il n'arrête jamais rien. Trop bas, il tue du travail qui se passait bien.

Voici comment arriver à un chiffre défendable, sans diplôme de statistiques.

## L'essentiel en bref

- Partez de ce que coûte réellement une étape, pas de ce qui vous semble prudent.
- Ajoutez une marge selon l'usage d'outils : environ 2x pour une étape en un seul appel, 3x à 4x pour une étape riche en outils.
- Limiter le nombre de boucles est une très mauvaise façon de limiter l'argent.
- Sur les étapes bon marché, plafonnez l'entrée. Sur les étapes coûteuses, plafonnez l'argent.
- Le budget d'une exécution n'est pas la somme des budgets d'étapes, parce que les étapes se répètent.

## D'abord, savoir ce que coûte une étape

Les coûts varient bien plus d'un type de travail à l'autre qu'on ne l'imagine. Ce sont des exemples issus d'un modèle construit, pas des mesures de production, mais c'est l'écart qui compte.

| Type d'étape | Ce qu'elle fait | Coût typique par exécution |
|---|---|---|
| Classer | Lit un message, renvoie une étiquette | environ 0,0003 $ |
| Rédiger avec recherche | Récupère un document, écrit une réponse | environ 0,013 $ |
| Recherche multi-outils | Six appels d'outils environ, puis une synthèse | environ 0,27 $ |
| Résumer un long document | Une grosse lecture, une réponse | environ 0,04 $ |
| Étape navigateur | Une douzaine d'actions de page, chacune ajoutant une capture | environ 1,67 $ |

Entre une étape de classement et une étape navigateur, le rapport dépasse mille. Un budget unique valable pour les deux n'a aucun sens : c'est pourquoi les budgets se posent par étape plutôt que par agent.

## Votre marge n'est pas 2x

La plupart des gens prennent le coût typique et le doublent. C'est à peu près juste pour une étape qui fait un appel et s'arrête. C'est très faux pour tout ce qui utilise des outils.

La raison : chaque résultat d'outil est réinjecté dans tous les appels suivants, donc le coût ne suit pas le nombre d'appels d'outils. Il monte plus vite. Doubler les appels d'outils d'une étape qui en fait beaucoup peut à peu près quadrupler son coût.

| Type d'étape | Si elle prend deux fois plus d'étapes que d'habitude | Marge à prévoir |
|---|---|---|
| Un appel, sans outil | Environ le double du coût | 2x |
| Rédaction avec une ou deux recherches | Environ trois fois et demie | 3x à 4x |
| Recherche ou navigation riches en outils | Environ quatre fois | 3x à 4x |

La conclusion pratique est la même dans tous les cas : « on monte un peu le nombre max d'itérations » n'est pas un petit changement. C'est la décision de quadrupler à peu près le plafond.

![La vue des métriques d'agents LiveContext : une ligne de synthèse (exécutions totales, tokens, appels d'outils, taux de succès) au-dessus d'un tableau par agent montrant, pour chacun, les exécutions, tokens, appels d'outils, crédits dépensés, modèle, durée et taux de succès.](/blog/cap-ai-agent-cost-budgets-metrics.png)

*Dépense, tokens et appels d'outils par agent, sur de vraies exécutions. C'est l'entrée du dimensionnement : le chiffre que vous posez doit venir de votre propre distribution, pas d'une intuition.*

## Pourquoi un plafond d'itérations plafonne mal l'argent

Beaucoup d'outils ne permettent de plafonner que le nombre de boucles. Cela ressemble à une limite. Faites le calcul et ça n'en est presque pas une.

| Étape | Coût attendu | Coût si elle atteint un plafond de 100 boucles |
|---|---|---|
| Recherche multi-outils | environ 0,27 $ | environ 47 $ |
| Étape navigateur | environ 1,67 $ | environ 101 $ |

Un plafond qui autorise soixante fois la facture attendue ne vous protège de rien. Si votre seul contrôle est un compteur de boucles, réglez-le près de ce que le travail réel demande (quelques appels pour une recherche simple, dix à quinze pour une comparaison) plutôt que sur un chiffre rond comme 100.

## Étapes bon marché : plafonnez l'entrée. Étapes coûteuses : plafonnez l'argent.

Il existe un plancher en dessous duquel un plafond en argent ne peut physiquement pas fonctionner.

Un budget ne peut refuser que l'appel *suivant* : il lui faut donc de la place pour au moins quelques appels avant le plafond. Règle approximative : le budget doit valoir au moins trois fois le plus gros appel possible de l'étape. En dessous, le premier appel peut faire sauter le plafond, qui n'a jamais son tour.

Pour les étapes bon marché, ce plancher est au-dessus de ce que l'étape coûte : un plafond en argent y est du théâtre. Ce qui marche là, c'est de limiter ce qui entre : plafonnez la quantité de texte confiée à l'étape et ce qu'elle peut écrire en retour. Le pire appel chute alors d'un ordre de grandeur, et le plancher descend avec lui.

| Type d'étape | Le contrôle qui marche | Pourquoi |
|---|---|---|
| Classement, recherches courtes | Plafonner la taille d'entrée | L'étape est déjà bornée, un plafond en argent ne mord pas |
| Travail sur long document | Plafonner la taille d'entrée | Un seul gros appel : l'entrée *est* le coût |
| Recherche, navigation, tout ce qui boucle | Plafonner l'argent | Le coût vient de la répétition, que seul l'argent borne |

## Le budget d'exécution n'est pas la somme des budgets d'étapes

C'est là que le dimensionnement soigné s'effondre en général.

Les étapes se répètent. Une étape dans une boucle sur cinquante éléments tourne cinquante fois. Une branche qui se déploie tourne une fois par branche. Le plafond d'exécution doit donc se calculer le long du chemin le plus coûteux dans le workflow, en comptant les répétitions, et non en additionnant un budget par étape dessinée sur le canvas.

Et quand une exécution se déploie, refusez-la avant qu'elle démarre plutôt que de l'interrompre à mi-course. Couper un déploiement en vol laisse un sous-ensemble arbitraire de branches terminées, et lesquelles survivent dépend de l'ordre de démarrage. Refuser d'emblée donne quelque chose de réessayable.

## Comment choisir le chiffre

1. **Récoltez quelques vraies exécutions.** Pour chaque étape : tokens en entrée, tokens en sortie, nombre d'appels d'outils, modèle, et comment elle s'est terminée.
2. **Ne dimensionnez pas sur la moyenne.** Les coûts sont déséquilibrés : la plupart des exécutions sont bon marché et quelques-unes coûteuses, donc la moyenne se situe bien en dessous du milieu du risque. Dimensionner dessus tue environ un tiers du travail légitime.
3. **Soyez honnête sur votre échantillon.** Il faut quelques centaines d'exécutions avant de parler d'un pire cas sans rougir. En dessous, dimensionnez sur le pire cas structurel (le plus gros appel que le modèle peut physiquement faire) au lieu de faire semblant d'avoir une distribution.
4. **Surveillez le cumul.** Un plafond qui tue 5 % des étapes semble tolérable, jusqu'à ce que vous en ayez dix : cela fait 40 % des exécutions qui heurtent un plafond quelque part. Les plafonds par étape doivent être bien plus larges que votre tolérance au niveau de l'exécution.
5. **Testez-le.** Sur-alimentez volontairement une étape et vérifiez que vous obtenez un refus propre qui nomme la limite. Un plafond non testé est une intuition avec un chiffre dessus.

## Les questions qu'on nous pose

### Quel budget de départ raisonnable pour un agent ?

Prenez le coût attendu de son étape la plus coûteuse, multipliez par trois ou quatre si elle utilise des outils, et posez cela par étape. Puis fixez un budget d'exécution le long du chemin le plus long, en comptant tout ce qui boucle.

### Pourquoi ne pas mettre un budget large et l'oublier ?

Parce qu'un budget large ne se déclenche qu'après les dégâts. La valeur d'un plafond, c'est l'exécution qu'il refuse, et un plafond réglé à soixante fois le coût attendu ne refusera rien qui mérite de l'être.

### Mon agent atteint sans cesse son budget. Le relever ou le corriger ?

Regardez ce qui a changé avant de relever quoi que ce soit. Atteindre un plafond signifie en général que l'entrée a grossi ou que l'agent s'est mis à boucler, et les deux méritent une correction plutôt qu'un financement.

### Un budget par étape, ou un seul par agent suffit-il ?

Par étape, si les étapes sont de natures différentes. Entre une étape de classement et une étape navigateur, le rapport de coût dépasse mille, et un seul chiffre ne peut pas convenir aux deux.

### À quelle fréquence revoir ces chiffres ?

Chaque fois que vous changez de modèle, la taille des prompts, ou ce que l'étape a le droit de faire. Ces trois éléments déplacent le coût, et un budget calé sur la forme du trimestre dernier va soit fuir, soit étrangler.

## La prochaine étape

Le dimensionnement ne sert que si le plafond peut réellement arrêter une exécution. Vérifiez ce côté d'abord : [comment empêcher un agent de trop dépenser](/fr/blog/cap-ai-agent-cost-budgets) explique de quoi est fait un vrai plafond et comment prouver que le vôtre fonctionne.
`;

export default content;
