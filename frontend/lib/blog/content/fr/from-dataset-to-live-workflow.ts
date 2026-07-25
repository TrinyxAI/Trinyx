// French translation of from-dataset-to-live-workflow (public register,
// 2026-07-24). Structure identical to the English source.
const content = `Un jeu de données ne sert à rien tant que quelque chose ne le lit pas à intervalle régulier, ne décide pas de ce qui a changé et n'agit pas. Voici comment passer d'un fichier que vous ouvrez à la main à un workflow qui se contrôle tout seul.

L'exemple tout au long est une veille de prix : suivre quelques produits, repérer quand l'un bouge, et prévenir quelqu'un avant que cela coûte de l'argent. La forme vaut pour tout ce qui a un rythme.

## L'essentiel en bref

- Choisissez une source qui change selon un rythme prévisible.
- Nettoyez une seule fois, à l'entrée, pour que toutes les étapes suivantes puissent lui faire confiance.
- Calculez la décision d'abord, puis branchez sur la décision, pas sur les valeurs brutes.
- Mettez une validation humaine devant tout ce qui est irréversible.
- Réécrivez le résultat, pour que la prochaine exécution sache ce qu'a fait la précédente.

## La construction, en six étapes

| Étape | Ce qu'elle fait | Pourquoi elle est là |
|---|---|---|
| 1. Planification | Se déclenche toutes les heures | Le rythme. Personne n'a besoin d'y penser |
| 2. Récupération | Lit la source en direct | C'est là que la donnée fraîche entre |
| 3. Nettoyage | Remet tout dans les mêmes quelques champs | Tout l'aval cesse de deviner |
| 4. Recherche | Vérifie si vous avez déjà vu cet élément | Évite les doublons, et donne le chiffre précédent |
| 5. Décision | A-t-il bougé de plus de 5 % ? | La vraie question |
| 6. Validation, puis action | Une personne confirme, puis l'alerte et l'écriture partent | La partie irréversible, sous contrôle |

![Le générateur de workflow LiveContext affichant le graphe de veille de prix à huit nœuds sur le canvas : un déclencheur horaire, un appel HTTP, un nœud de code, une recherche en table et une décision qui sépare un SKU inédit d'un SKU connu, puis une décision de mouvement de prix, une porte d'approbation et l'écriture gardée.](/blog/from-dataset-to-live-workflow-builder.png)

*Toute la construction sur un seul canvas : du déclencheur horaire à gauche à l'écriture sous validation à droite.*

## Étape 1 : choisissez une source qui a un rythme

Automatisez une donnée qui change selon un rythme que vous pouvez nommer. Pas « chaque semaine », mais « un CSV par fournisseur, par e-mail, chaque lundi avant 9 h ». Cette précision décide de votre déclencheur.

Si la source ne change presque jamais, vous n'avez pas besoin d'un workflow. Vous avez besoin d'une consultation, et vous vous épargnerez l'effort.

## Étapes 2 et 3 : récupérer, puis nettoyer une seule fois

Les sources brutes sont désordonnées. Les noms de colonnes dérivent, les dates arrivent en trois formats, un fournisseur écrit « prix unitaire » et l'autre « prix/pièce ».

Faites le nettoyage à un seul endroit, exactement là où la donnée entre. Décidez d'abord la forme voulue (pour la veille de prix : produit, prix, devise, vu-le), puis faites en sorte que chaque source produise cette forme et rien d'autre. Toutes les étapes suivantes deviennent plus simples, parce qu'elles peuvent faire confiance à leur entrée.

Un avertissement qui rattrape tout le monde : une récupération ratée arrive souvent déguisée en succès. Beaucoup de services renvoient un message d'erreur à l'intérieur d'une réponse parfaitement normale. Vérifiez que ce qui est revenu est bien la donnée avant de la transmettre, sinon la panne descend en silence dans tout le workflow.

## Étapes 4 et 5 : décider, puis brancher

Le but du workflow est une décision : rendez donc la décision explicite.

Le piège est de brancher sur la valeur brute. Vous ne vous souciez pas de savoir que le prix est à 12,40. Vous vous souciez de savoir s'il a monté de plus que votre tolérance depuis la dernière fois. Calculez cela d'abord, puis branchez sur la réponse.

Il y a aussi un aspect très concret. Des filtres qui ont l'air numériques sont souvent comparés comme du texte en coulisses, et le texte ne se trie pas comme les nombres : « 100 » passe avant « 9 ». Un filtre « prix supérieur à 9 » peut donc rater silencieusement le 100 qui vous intéressait. Récupérez la valeur précédente, faites le calcul dans une étape de décision explicite, et branchez là-dessus.

## Étape 6 : encadrez l'irréversible

La dernière étape doit faire quelque chose de réel : envoyer l'alerte, mettre à jour la ligne, ouvrir le ticket, préparer la commande.

Quand cette action est coûteuse ou sans retour possible, mettez une validation humaine devant. L'exécution se met en pause, attend une personne, puis reprend exactement là où elle s'était arrêtée. Les actions peu coûteuses et réversibles peuvent tourner sans surveillance. Tout ce qui atteint un client ou dépense de l'argent passe par une porte.

Deux choses à savoir sur la pause. Valider deux fois ne fait rien de grave : la première réponse l'emporte. Et l'exécution planifiée suivante ne piétine pas une décision en cours de réflexion : chaque exécution garde ses propres résultats.

## La garde qui rend une exécution répétée sûre

Un déclencheur horaire relance la même lecture toutes les heures. Sans garde, il insère la même ligne toutes les heures et votre table se remplit de doublons.

Le motif qui corrige cela, sur n'importe quel outil : **chercher d'abord, décider ensuite, écrire enfin**. Cherchez l'élément. Si le compte est à zéro, il est nouveau, donc écrivez-le. Sinon, il existe déjà, donc mettez-le à jour. N'insérez jamais sans condition quand le même élément peut être récupéré à nouveau.

Cette recherche fait double emploi. C'est votre garde anti-doublons, et c'est aussi d'où vient le chiffre de la semaine dernière, ce qui rend la question « a-t-il bougé ? » possible à trancher.

## Quatre pièges qui coûtent un après-midi

| Piège | Ce que vous voyez | Ce qui se passe vraiment |
|---|---|---|
| Résultat vide silencieux | Une étape ne renvoie rien, sans erreur | La donnée est imbriquée un niveau plus profond que prévu |
| Récupération ratée d'apparence normale | Tout l'aval est faux | L'erreur est revenue à l'intérieur d'une réponse normale |
| Nombre comparé comme du texte | Un seuil rate discrètement des cas | « 100 » se trie avant « 9 » |
| Doublons toutes les heures | La table grossit sans fin | Pas de garde « chercher d'abord » avant l'écriture |

Aucun de ces cas ne lève d'erreur. C'est exactement pour cela qu'ils coûtent un après-midi.

## Prouvez chaque branche avant de dire que c'est en production

Ne livrez pas sur le seul chemin heureux. Provoquez chaque cas et vérifiez ce que le workflow a réellement fait.

| Test | Ce que vous provoquez | Ce qui doit se passer |
|---|---|---|
| Nouvel élément | Un élément sans historique | Exactement une ligne écrite |
| Aucun changement | Un élément connu, prix stable | Rien d'envoyé, rien d'écrit |
| Vrai changement | Un élément connu, prix en hausse de 10 % | L'exécution se met en pause pour validation |
| Refus | Refusez la validation | Pas d'alerte, pas d'écriture |
| Deux exécutions | Redéclenchez la planification | Le nombre de lignes ne bouge pas |

Si le cas « vrai changement » se termine sans pause, votre seuil est évalué à un endroit que vous n'aviez pas prévu. C'est le genre de panne qu'il vaut mieux attraper avant la mise en production qu'après.

## Les questions qu'on nous pose

### À quelle fréquence doit-il tourner ?

À la fréquence de la source. Toutes les heures pour des prix, chaque jour pour un rapport, chaque semaine pour un fichier fournisseur. Tourner plus souvent que la donnée ne change coûte des appels et n'apprend rien.

### Où garder l'historique ?

Dans une table que le workflow lit et écrit lui-même. C'est ce qui transforme une série d'exécutions séparées en quelque chose qui a une mémoire : il sait ce qu'il a déjà traité, et il a le chiffre d'hier pour comparer.

### Que se passe-t-il si une exécution échoue en cours de route ?

L'exécution s'arrête à l'étape en cause, et l'enregistrement montre laquelle et ce qu'elle avait reçu. Vous corrigez cette étape et relancez, au lieu de raisonner sur l'ensemble.

### Faut-il vraiment un humain dans la boucle ?

Pour tout ce qui est irréversible, oui, au moins jusqu'à ce que vous ayez confiance. Envoyer automatiquement sur une lecture erronée, c'est ainsi que l'automatisation se fait une mauvaise réputation. Commencez avec la porte, retirez-la plus tard si les faits le justifient.

## La prochaine étape

Choisissez une source que vous vérifiez déjà à la main chaque semaine. Notez la décision qu'elle alimente, le seuil que vous utilisez, et ce que vous faites quand il est franchi. C'est le workflow, et vous venez de le concevoir. Voyez ensuite [quoi journaliser](/fr/blog/ai-agent-audit-trail) pour pouvoir répondre de ce qu'il a fait.
`;

export default content;
