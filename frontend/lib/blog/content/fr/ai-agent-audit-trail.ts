// French translation of ai-agent-audit-trail (public register, 2026-07-24).
// Schema half of the audit pair. Structure identical to the English source.
const content = `Un agent IA qui marche en démo a prouvé une chose : il peut marcher une fois. La production pose une question plus dure. Quand il se trompe, pouvez-vous dire ce qui s'est passé et pourquoi ?

Si la réponse est non, vous n'avez pas un système que vous exploitez. Vous en avez un que vous espérez. Ce qui comble l'écart, c'est un enregistrement de chaque exécution que quelqu'un d'extérieur à votre équipe pourrait lire des mois plus tard.

## L'essentiel en bref

- Votre tableau de bord de supervision n'est pas une piste d'audit. Autre lecteur, autre horloge, autres règles.
- Le traçage IA standard n'enregistre par défaut ni les prompts ni les réponses. Il faut l'activer.
- N'échantillonnez jamais une piste d'audit. L'exécution que vous devrez expliquer sera dans ce que vous avez jeté.
- Journalisez chaque appel d'outil et son résultat, la branche prise, le coût, et qui a validé.
- Pour les validations, enregistrez ce que la personne a réellement vu, pas seulement qu'elle a cliqué oui.

## Un tableau de bord n'est pas une piste d'audit

Ils se ressemblent et ce n'est pas le même objet. Un tableau de bord est lu par son auteur, quelques minutes après, l'incident encore en tête. Une piste est lue par un tiers indifférent ou hostile, des mois plus tard, qui ne peut pas vous poser de question.

| | Tableau de bord | Piste d'audit |
|---|---|---|
| Qui le lit | Vous, quelques minutes après | Un tiers, des mois plus tard |
| Échantillonnage | Normal, souvent 10 à 20 % | Jamais |
| Contenu des prompts et des réponses | En général désactivé | Activé, le temps de la conservation |
| Si une écriture échoue | On le note et on continue | L'opération doit échouer |
| Ordre | Horodatages | Un numéro de séquence que vous attribuez |
| Peut-il changer ensuite | Oui, par conception | Non, ajout seul |
| Mode de défaillance | Vous déboguez plus lentement | Vous ne pouvez pas répondre à la question |

![Une exécution de workflow LiveContext en vue observabilité : le graphe exécuté avec une coche verte sur chaque nœud, à côté d'un inspecteur listant l'epoch, ses horodatages de début et de fin, et le statut, la durée et le coût de chaque nœud.](/blog/ai-agent-audit-trail-run.png)

*Une exécution en vue observabilité : chaque étape, son statut, sa durée, son coût. Vraiment utile, et malgré tout un tableau de bord plutôt que l'enregistrement durable décrit dans la suite.*

## « On a du traçage » ne veut pas dire « on a une piste »

C'est le constat qui prend le plus d'équipes au dépourvu.

Les conventions standard du secteur pour tracer les appels IA traitent les prompts, les réponses, les arguments d'outils et les résultats d'outils comme optionnels, et la position de la spécification est que les outils ne doivent pas les capturer par défaut. Une installation de traçage neuve vous donne donc le nom du modèle, des comptes de tokens, la latence et un motif de fin : rien de la matière qui reconstitue une décision.

Activer la capture du contenu est aussi plus retors qu'un simple interrupteur, dans au moins une implémentation répandue où un second réglage, à peine documenté, doit être activé aussi. Vérifiez ce que votre installation stocke vraiment, plutôt que de le supposer, et vérifiez-le en lisant un enregistrement réel de bout en bout.

L'autre moitié du même problème, ce sont les conseils qu'on trouve dans la plupart des guides d'observabilité : échantillonnez fortement en volume, et nettoyez le contenu avant qu'il n'atteigne le backend. Les deux sont sains pour de la supervision et fatals pour une piste d'audit. Un échantillon à 10 % ne vaut rien quand la décision à défendre est dans les 90 % restants.

## Quoi journaliser pour chaque exécution

Un enregistrement par exécution. C'est l'en-tête qu'on lit en premier.

| Ce qu'il faut enregistrer | Pourquoi cela compte |
|---|---|
| Un identifiant d'exécution créé au lancement | Tout le reste s'y rattache, et un identifiant créé trop tard se perd |
| Qui ou quoi l'a déclenchée, et comment | Une personne, une planification, un webhook : cela décide de qui est responsable |
| Heure de début et heure de fin, deux horodatages | Une durée ne s'aligne pas sur une chronologie extérieure |
| Quel modèle a été facturé et lequel a réellement tourné | Ils peuvent différer, et n'en noter qu'un rend le reste faux |
| Les prix en vigueur au moment de l'exécution | Pour que le coût reste compréhensible après un changement de tarif |
| Tokens en entrée, en sortie, en cache, et le coût | Votre facture, et votre alerte précoce |
| Le statut, et pourquoi elle s'est arrêtée | L'affirmation que l'on vous demandera de défendre |
| La configuration et la version de politique en vigueur | Une validation était-elle exigée, à cet instant précis |
| Quelle version du logiciel tournait | Cette exécution est-elle antérieure au correctif |
| Si une validation était requise, et sa référence | Vide doit vouloir dire « non requise », pas « inconnue » |

Deux points méritent qu'on insiste. **Deux horodatages, pas une durée**, parce que seuls des horodatages se recoupent avec les enregistrements de quelqu'un d'autre. Et **les prix en vigueur**, parce que les prix et les noms de modèles changent sous vos pieds, et qu'un coût non reproductible est un coût indéfendable.

Une chose à ne pas stocker : le prompt système complet à chaque exécution. À dix mille exécutions par jour, un prompt de six kilo-octets représente environ 20 Go par an de pure duplication. Stockez chaque version une fois et référencez-la.

## Quoi journaliser pour chaque étape

Un enregistrement par tour de modèle, appel d'outil, décision ou validation. Ils sont environ vingt-cinq fois plus nombreux que les enregistrements d'exécution et portent presque tout le contenu.

| Ce qu'il faut enregistrer | Pourquoi cela compte |
|---|---|
| L'ordre réel, attribué à l'écriture | Les horodatages s'égalisent et se réordonnent. Un compteur non |
| Si des étapes ont tourné en parallèle | Lire un lot parallèle comme une chaîne causale est pire qu'un trou |
| De quel type d'étape il s'agit | Tour de modèle, appel d'outil, décision, validation |
| Nom de l'outil et identifiant d'appel | Relie une demande à son résultat malgré les reprises |
| Les arguments et le résultat | Le contenu réel, sur l'horloge que vous appliquez au contenu |
| Une empreinte des deux | Permet de prouver ce qui a été envoyé longtemps après la suppression |
| La taille du contenu | Indique à un lecteur ultérieur qu'il y a eu troncature, et de combien |
| Quelle branche a été prise | Rend l'exécution rejouable sur le papier |
| Pourquoi une étape n'a pas tourné | Une branche écartée et une branche jamais atteinte sont deux faits différents |
| Code d'erreur, séparé du message | Les codes s'interrogent ; les messages recopient l'entrée fautive |
| Si une expurgation a eu lieu | Sinon un enregistrement d'apparence propre ne prouve rien |

La ligne de l'empreinte est la vedette discrète de ce tableau. Garder une empreinte de ce qui est entré et sorti coûte quelques octets par étape, et permet de conserver des preuves pendant des années tout en supprimant le contenu au bout de quelques mois. Quand quelqu'un produit un document et affirme que votre agent l'a vu, l'empreinte tranche.

Une réserve, pour que personne ne se trompe : une empreinte de quelque chose de devinable, comme un code postal ou une date de naissance, se retrouve en essayant toutes les possibilités. Salez celles-là avec une clé conservée séparément.

## L'enregistrement de validation mérite sa propre ligne

Si un humain valide, journalisez cela comme un enregistrement à part entière, pas comme un simple drapeau sur l'exécution.

Notez qui a validé, quand, par quel canal, de combien de temps il disposait avant expiration, et surtout **ce que le validateur a réellement vu**. Figez ce texte au moment où l'exécution s'est mise en pause et conservez-le avec l'enregistrement. Sans cela, « un humain a validé » ne veut rien dire, puisque personne ne peut savoir ce qu'il validait.

Trois petits pièges au même endroit. Un champ de validation vide doit signifier « aucune validation n'était requise par la politique en vigueur », ce qui suppose de pouvoir retrouver la version de cette politique. Des identités par défaut comme « système » ou « api » ne doivent jamais pouvoir désigner une vraie personne. Et si votre enregistrement affiche un rôle de validateur, assurez-vous que quelque chose a bien vérifié ce rôle, ou écrivez noir sur blanc dans l'enregistrement que ce n'est pas le cas.

## Deux erreurs qui ruinent une piste en silence

**L'écrire au mieux, sans garantie.** Si l'écriture d'audit part sans attendre et que les échecs sont notés comme non critiques, votre piste s'amincit dès que le système est sous tension : c'est-à-dire pendant exactement les incidents qu'on vous demandera d'expliquer. La couverture devient corrélée à la santé du système, la pire propriété possible. Écrivez l'enregistrement dans la même transaction que ce qu'il enregistre.

**Stocker une durée sans la chronologie.** Cela paraît mineur jusqu'au jour où l'on vous demande d'aligner votre enregistrement sur les horodatages des e-mails d'un client, et que vous ne pouvez pas.

## Les questions qu'on nous pose

### Mon fournisseur de modèle ne journalise-t-il pas déjà tout cela ?

Il journalise son côté de l'appel, pour sa durée de conservation, dans son format, et vous ne pouvez pas l'interroger comme une preuve. L'enregistrement que vous pouvez défendre est celui que vous gardez.

### Tout journaliser ne coûte-t-il pas cher ?

Le squelette (identifiants, horaires, statuts, compteurs, empreintes, branches) est minuscule, de l'ordre de quelques dizaines de gigaoctets par an à dix mille exécutions par jour. Le contenu est la partie coûteuse, et c'est précisément pour cela qu'il vit sur une horloge plus courte. Cette séparation est le sujet de [combien de temps le garder](/fr/blog/ai-agent-audit-log-retention).

### Et les données personnelles dans les journaux ?

Partez du principe qu'il y en a, surtout dans les messages d'erreur, qui recopient systématiquement l'entrée qui a échoué. Gardez les identifiants pseudonymes, mettez le contenu sur une horloge courte, et réduisez l'enregistrement longue durée à des empreintes et des codes.

### Comment savoir si ma piste est suffisante ?

Prenez une exécution du mois dernier et reconstituez-la de bout en bout uniquement à partir des enregistrements. Si vous devez relancer quoi que ce soit ou demander à un collègue, elle n'est pas encore suffisante.

## La prochaine étape

Prenez une vraie exécution et essayez de l'expliquer à partir du seul enregistrement. Tout ce que vous devrez deviner est le prochain champ à ajouter. Décidez ensuite combien de temps chaque partie doit survivre : [combien de temps garder une piste d'audit d'agent IA](/fr/blog/ai-agent-audit-log-retention).
`;

export default content;
