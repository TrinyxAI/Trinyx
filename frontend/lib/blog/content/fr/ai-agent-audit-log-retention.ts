// French translation of ai-agent-audit-log-retention (public register,
// 2026-07-24). Retention half of the audit pair. Keep the "not legal advice"
// line and the out-of-scope spine.
const content = `« On garde les journaux combien de temps ? » reçoit en général un chiffre dont quelqu'un se souvient d'un autre poste. Quatre-vingt-dix jours. Un an. Sept ans, parce que ça fait prudent.

Il y a une meilleure façon de décider, et elle commence en remarquant que vous ne conservez pas une chose. Vous en conservez deux, et elles n'ont pas du tout le même coût.

## L'essentiel en bref

- Coupez l'enregistrement en deux : un petit squelette, et le contenu volumineux.
- Gardez le squelette des années. Il coûte peu, et il ne se rajoute pas après coup.
- Gardez le contenu des mois. C'est presque tout le stockage et presque tout le risque.
- La plupart des agents IA ne sont pas du tout concernés par les obligations de journalisation du règlement européen sur l'IA.
- Tout garder pour toujours n'est pas l'option prudente. C'est un autre problème.

## Deux horloges, pas une

Presque tout le débat sur la conservation se dissout dès qu'on cesse de traiter l'enregistrement comme un bloc unique.

| Couche | Ce qu'elle contient | Combien de temps | Pourquoi |
|---|---|---|---|
| Squelette | Identifiants, horodatages, statut, modèle, coûts, branche prise, empreintes des contenus, qui a validé | Des années | Minuscule, et il répond à lui seul à la plupart des questions |
| Contenu | Prompts, réponses, arguments d'outils, résultats d'outils, messages d'erreur | Des mois | Presque tout le stockage, et presque toute l'exposition en données personnelles |

La charnière entre les deux, c'est l'empreinte. Gardez une empreinte de chaque contenu dans le squelette et vous pourrez encore prouver, des années plus tard, exactement ce qui a été envoyé et renvoyé, sans en conserver un seul mot.

C'est ce qui rend une longue conservation défendable plutôt que dangereuse.

## L'arithmétique décide pour vous

Prenons un système chargé : dix mille exécutions d'agents par jour. Voici à peu près où vont les octets sur un an. À traiter comme un modèle plutôt qu'une mesure, et ajoutez un peu pour la réalité.

| Quoi | Par an | Qu'en faire |
|---|---|---|
| Squelette, toutes exécutions et toutes étapes | environ 31 Go | Gardez-le des années. C'est l'assurance bon marché |
| Résultats d'outils dupliqués | environ 84 Go | Stockez une fois, référencez |
| Prompts système dupliqués | environ 21 Go | Stockez une fois par version, référencez par empreinte |

Le squelette coûte quelques euros par an de stockage. Presque tout débat sur la conservation porte en réalité sur la couche de contenu, précisément celle que vous avez de bonnes raisons de garder courte.

Deux gains faciles se cachent dans ce tableau. Le même prompt système stocké à chaque exécution, parfois plusieurs fois par exécution, est de la pure duplication. Les résultats d'outils recopiés à plusieurs endroits aussi. Corrigez ces deux-là et la question du stockage disparaît en grande partie d'elle-même.

## Ce que la loi exige vraiment

Ceci n'est pas un conseil juridique, et aucun des régimes ci-dessous ne doit être réduit à un chiffre unique qui s'appliquerait à vous. Mais la forme mérite d'être connue, parce que la plupart des articles se trompent des deux mêmes façons.

**Le plancher de six mois du règlement européen sur l'IA ne concerne que les systèmes à haut risque.** Pour ceux-là, le fournisseur et le déployeur portent chacun leur propre minimum de six mois, chacun limité aux journaux sous son propre contrôle. Il est dû deux fois, par deux parties différentes, et non partagé entre elles.

**Six mois est le plancher pour les journaux. Dix ans est le plancher pour la documentation.** Deux régimes distincts, sans cesse confondus. Conserver votre documentation de conception dix ans ne dit rien de la durée de conservation des enregistrements d'exécution.

**Et la partie qui concerne la plupart des lecteurs :** haut risque veut dire composant de sécurité d'un produit réglementé, ou l'un des domaines précis listés par le règlement, comme la biométrie, les infrastructures critiques, les décisions d'emploi, l'accès aux services essentiels ou le maintien de l'ordre. Un assistant de code, un agent de recherche interne, un agent de rédaction de documents, un agent de tri du support : aucun n'y figure.

Il existe aussi un droit distinct qu'il faut connaître, car c'est lui qui force réellement l'explication d'une décision : une personne affectée de façon significative par une décision prise sur la base de la sortie d'un système à haut risque peut demander une explication du rôle de ce système. C'est une obligation différente de la journalisation, et là encore elle ne mord que pour les systèmes à haut risque.

Un dernier point si vous citiez des dates : le calendrier a bougé. Les obligations haut risque ont été reportées au 2 décembre 2027 pour les systèmes autonomes, et à août 2028 pour l'IA intégrée dans des produits réglementés. Tout article citant encore août 2026 pour le haut risque est périmé.

Si vous êtes hors périmètre, construisez donc l'enregistrement pour les questions qu'on vous posera vraiment : un litige client, une revue d'incident, une contestation de facture, une enquête de sécurité. Et laissez les six mois être un plancher que vous dépassez par hasard plutôt qu'un chantier.

## La demande de suppression qui arrive demain

Vient la collision. Vous voulez un enregistrement qui dure des années. Quelqu'un a le droit de vous demander d'effacer ses données.

Quatre choses rendent cela vivable.

**Une référence pseudonyme n'est pas de l'anonymat.** Si un jeton peut être relié à une personne grâce à des informations que vous détenez ailleurs, cela reste une donnée personnelle. Stockez la correspondance séparément, et ne vous racontez pas que la piste est anonyme.

**Tout garder pour toujours n'est pas la réponse conforme.** La phrase même qui fixe un minimum renvoie aussi au droit de la protection des données. La sur-conservation est un problème en soi, pas un choix par défaut sans risque.

**Supprimez la couche opérationnelle, gardez le registre.** Séparez ce qu'une demande de suppression peut emporter (le contenu, les lignes opérationnelles) de ce qui doit survivre (registres de facturation, traces de sécurité), et assurez-vous que la couche survivante ne contient ni contenu ni identifiant direct.

**Méfiez-vous des données qui survivent à la suppression.** L'échec classique : les gros contenus vivent dans un stockage de fichiers et la ligne en base ne garde qu'un pointeur. Supprimez la ligne et le fichier reste, sans référence, invisible à tout audit ultérieur de ce que vous détenez. Faites du fichier la cible de la suppression et réconciliez les restes régulièrement.

Un motif à construire si vous le pouvez : quand un contenu est effacé, laissez une pierre tombale qui conserve l'empreinte et la taille. Un lecteur ultérieur saura alors que quelque chose existait, quelle taille cela faisait, et que cela a été retiré sur demande plutôt que perdu.

## L'erreur qu'on ne peut pas rattraper

Toutes les autres erreurs de conservation se corrigent. Pas celle-ci : **on ne rallonge pas une conservation rétroactivement.**

Le jour où vous découvrez que la fenêtre nécessaire était plus longue que votre purge, la donnée est déjà partie. La correction fait mal dans l'autre sens aussi : une équipe passant un journal de cycle de vie de 30 jours à un an s'est retrouvée avec un arriéré douze fois plus gros à la première purge suivante.

Réglez donc le squelette sur la plus longue fenêtre que vous puissiez raisonnablement imaginer, dès le premier jour. À environ 31 Go par an, c'est l'assurance la moins chère du système. Ajustez ensuite la fenêtre du contenu, qui est la partie à la fois coûteuse et réversible.

Deux erreurs plus petites de la même famille. Vérifiez que votre conservation documentée correspond à votre conservation configurée : un commentaire disant « 30 jours » au-dessus d'un réglage dont la valeur par défaut est un an, c'est ainsi que les deux divergent en silence. Et gardez les requêtes courantes hors des lignes de détail, avec des synthèses par jour pour les questions fréquentes, sinon votre enregistrement finit techniquement complet et pratiquement inutilisable.

## Les questions qu'on nous pose

### Quelle valeur par défaut raisonnable si je ne suis pas régulé ?

Squelette sur quelques années, contenu sur trois à six mois. Cela couvre les litiges, les revues d'incident et les contestations de facture sans détenir un entrepôt de données personnelles.

### Dois-je conserver les prompts et les réponses ?

Aussi longtemps que vous pourriez avoir à expliquer une décision précise, oui. Ensuite, l'empreinte porte la preuve et le texte n'est plus qu'une exposition.

### La règle des six mois s'applique-t-elle à mon chatbot ?

Presque certainement pas. Elle s'applique aux systèmes à haut risque tels que le règlement les définit, et les agents internes ou de productivité ordinaires n'y figurent pas. Vérifiez la liste plutôt que de supposer, dans un sens ou dans l'autre.

### Où part réellement le stockage ?

Dans les contenus. Les résultats d'outils et les prompts dominent, surtout quand ils sont dupliqués à plusieurs endroits. Le squelette structuré est négligeable à côté.

### Puis-je tout garder et décider plus tard ?

C'est l'option qui semble prudente et ne l'est pas. Un contenu conservé longtemps est un passif permanent, et c'est la première chose qu'une demande de suppression trouvera.

## La prochaine étape

Écrivez deux chiffres, un pour le squelette et un pour le contenu, et faites en sorte que celui du squelette soit généreux. Vérifiez ensuite que votre enregistrement contient bien ce que ces fenêtres sont censées protéger : [quoi journaliser pour chaque exécution d'agent IA](/fr/blog/ai-agent-audit-trail).
`;

export default content;
