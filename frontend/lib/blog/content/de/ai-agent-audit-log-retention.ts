// German translation of ai-agent-audit-log-retention (public register,
// 2026-07-24). Keep the "not legal advice" line and the out-of-scope spine.
const content = `"Wie lange heben wir die Protokolle auf?" wird meist mit einer Zahl beantwortet, die jemand aus einem anderen Job im Kopf hat. Neunzig Tage. Ein Jahr. Sieben Jahre, weil das sicher klingt.

Es gibt einen besseren Weg zu entscheiden, und er beginnt mit der Beobachtung, dass Sie nicht eine Sache aufbewahren, sondern zwei, und die kosten sehr Unterschiedliches.

## Kurz gefasst

- Teilen Sie die Aufzeichnung in zwei: ein kleines Skelett und den umfangreichen Inhalt.
- Bewahren Sie das Skelett jahrelang auf. Es ist billig und lässt sich nicht nachträglich ergänzen.
- Bewahren Sie den Inhalt monatelang auf. Er ist fast der gesamte Speicher und fast das gesamte Risiko.
- Die meisten KI-Agenten fallen gar nicht unter die Protokollpflichten der EU-KI-Verordnung.
- Alles für immer zu behalten ist nicht die sichere Option, sondern ein anderes Problem.

## Zwei Uhren, nicht eine

Fast die ganze Aufbewahrungsdebatte löst sich auf, sobald Sie die Aufzeichnung nicht mehr als eine einzige Sache behandeln.

| Schicht | Was darin steht | Wie lange | Warum |
|---|---|---|---|
| Skelett | IDs, Zeitstempel, Status, Modell, Kosten, genommener Zweig, Fingerabdrücke der Inhalte, wer freigab | Jahre | Winzig, und beantwortet die meisten Fragen allein |
| Inhalt | Prompts, Antworten, Werkzeugargumente und -ergebnisse, Fehlermeldungen | Monate | Fast der gesamte Speicher und fast die gesamte Datenschutz-Exposition |

Das Scharnier dazwischen ist der Fingerabdruck. Bewahren Sie zu jedem Inhalt einen Hash im Skelett, und Sie können noch Jahre später beweisen, was gesendet und zurückgegeben wurde, ohne ein einziges Wort davon zu behalten.

Genau das macht eine lange Aufbewahrung verteidigbar statt gefährlich.

## Die Rechnung entscheidet für Sie

Nehmen wir ein ausgelastetes System: zehntausend Agentenläufe am Tag. So verteilen sich die Bytes im Jahr grob. Behandeln Sie das als Modell und nicht als Messung, und schlagen Sie etwas Realität auf.

| Was | Pro Jahr | Was damit tun |
|---|---|---|
| Skelett, alle Läufe und Schritte | etwa 31 GB | Jahrelang behalten. Die billige Versicherung |
| Doppelte Werkzeugergebnisse | etwa 84 GB | Einmal speichern, referenzieren |
| Doppelte Systemprompts | etwa 21 GB | Einmal je Fassung speichern, per Hash referenzieren |

Das Skelett kostet ein paar Euro Speicher im Jahr. Fast jede Aufbewahrungsdebatte dreht sich in Wahrheit um die Inhaltsschicht, also genau die, die Sie aus guten Gründen kurz halten wollen.

Zwei leichte Gewinne stecken in dieser Tabelle. Derselbe Systemprompt, bei jedem Lauf gespeichert, manchmal mehrfach je Lauf, ist reine Dopplung. Mehrfach abgelegte Werkzeugergebnisse ebenso. Beheben Sie diese zwei, und die Speicherfrage erledigt sich weitgehend von selbst.

## Was das Recht wirklich verlangt

Dies ist keine Rechtsberatung, und keine der folgenden Regelungen darf auf eine einzige, für Sie gültige Zahl eingedampft werden. Die Grundform lohnt aber, denn die meisten Artikel irren auf dieselben zwei Arten.

**Die Sechs-Monats-Untergrenze der EU-KI-Verordnung gilt nur für Hochrisiko-Systeme.** Dort tragen Anbieter und Betreiber je ein eigenes Minimum von sechs Monaten, jeweils beschränkt auf die Protokolle in ihrer eigenen Kontrolle. Sie ist zweimal geschuldet, von zwei verschiedenen Parteien, und nicht geteilt.

**Sechs Monate ist die Untergrenze für Protokolle. Zehn Jahre ist die Untergrenze für Dokumentation.** Zwei verschiedene Regime, die ständig verwechselt werden. Ihre Entwurfsdokumentation ein Jahrzehnt aufzubewahren sagt nichts darüber, wie lange Sie Laufaufzeichnungen behalten.

**Und der Teil, der die meisten betrifft:** Hochrisiko bedeutet Sicherheitsbauteil eines regulierten Produkts oder einer der konkret aufgezählten Bereiche wie Biometrie, kritische Infrastruktur, Beschäftigungsentscheidungen, Zugang zu wesentlichen Diensten oder Strafverfolgung. Ein Programmierassistent, ein interner Rechercheagent, ein Agent, der Dokumente entwirft, ein Agent, der Support sortiert: keiner davon steht auf dieser Liste.

Es gibt außerdem ein eigenes Recht, das man kennen sollte, denn es erzwingt tatsächlich die Erklärung einer Entscheidung: Wer von einer Entscheidung auf Basis der Ausgabe eines Hochrisiko-Systems erheblich betroffen ist, kann eine Erklärung zur Rolle dieses Systems verlangen. Das ist eine andere Pflicht als das Protokollieren und greift ebenfalls nur bei Hochrisiko-Systemen.

Noch ein Punkt, falls Sie Daten zitiert haben: Der Zeitplan hat sich verschoben. Die Hochrisiko-Pflichten wurden auf den 2. Dezember 2027 für eigenständige Systeme und auf August 2028 für KI in regulierten Produkten verschoben. Jeder Artikel, der für Hochrisiko noch August 2026 nennt, ist veraltet.

Wenn Sie also außerhalb des Anwendungsbereichs liegen, bauen Sie die Aufzeichnung für die Fragen, die wirklich kommen: ein Kundenstreit, eine Vorfallanalyse, ein Streit über eine Rechnung, eine Sicherheitsuntersuchung. Und lassen Sie sechs Monate eine Grenze sein, die Sie nebenbei überschreiten, statt ein Projekt.

## Die Löschanfrage, die morgen kommt

Jetzt der Konflikt. Sie wollen eine Aufzeichnung, die Jahre hält. Jemand hat das Recht, die Löschung seiner Daten zu verlangen.

Vier Dinge machen das erträglich.

**Ein pseudonymer Verweis ist keine Anonymität.** Lässt sich ein Token mit anderweitig bei Ihnen vorhandenen Informationen wieder einer Person zuordnen, bleibt es ein personenbezogenes Datum. Bewahren Sie die Zuordnung getrennt auf und reden Sie sich nicht ein, das Protokoll sei anonym.

**Alles für immer zu behalten ist nicht die konforme Antwort.** Derselbe Satz, der ein Minimum setzt, verweist auch auf das Datenschutzrecht. Übermäßige Aufbewahrung ist ein eigenes Problem, kein sicherer Standard.

**Löschen Sie die operative Schicht, behalten Sie das Hauptbuch.** Trennen Sie, was eine Löschanfrage mitnehmen darf (Inhalte, operative Zeilen), von dem, was bleiben muss (Abrechnungs- und Sicherheitsaufzeichnungen), und sorgen Sie dafür, dass die bleibende Schicht keine Inhalte und keine direkten Kennungen führt.

**Achten Sie auf Daten, die das Löschen überleben.** Der klassische Fehler: Große Inhalte liegen im Dateispeicher, die Datenbankzeile hält nur einen Verweis. Löschen Sie die Zeile, bleibt die Datei zurück, ohne Verweis und unsichtbar für jede spätere Prüfung dessen, was Sie besitzen. Machen Sie die Datei zum Ziel des Löschens und gleichen Sie Reste regelmäßig ab.

Ein Muster, das sich lohnt, wenn Sie es können: Wird ein Inhalt gelöscht, hinterlassen Sie einen Grabstein mit Fingerabdruck und Größe. Ein späterer Leser erkennt dann, dass es etwas gab, wie groß es war und dass es auf Antrag entfernt und nicht verloren wurde.

## Der Fehler, den man nicht rückgängig macht

Jeder andere Aufbewahrungsfehler ist reparabel. Dieser nicht: **Aufbewahrung lässt sich nicht rückwirkend verlängern.**

An dem Tag, an dem Sie merken, dass das nötige Fenster länger war als Ihr Löschlauf, sind die Daten weg. Die Korrektur schmerzt auch andersherum: Ein Team, das ein Lebenszyklusprotokoll von 30 Tagen auf ein Jahr anhob, hatte beim ersten Löschlauf danach den zwölffachen Rückstau.

Stellen Sie das Skelett also vom ersten Tag an auf das längste Fenster ein, das Sie plausibel brauchen könnten. Bei rund 31 GB im Jahr ist es die billigste Versicherung im System. Feintunen Sie dann das Inhaltsfenster, den teuren und umkehrbaren Teil.

Zwei kleinere Fehler derselben Familie. Prüfen Sie, ob Ihre dokumentierte Aufbewahrung der konfigurierten entspricht: Ein Kommentar "30 Tage" über einer Einstellung, die standardmäßig ein Jahr hält, ist der Weg, auf dem beide still auseinanderlaufen. Und halten Sie Alltagsabfragen von den Detailzeilen fern, mit Tagesübersichten für die häufigen Fragen, sonst wird Ihre Aufzeichnung technisch vollständig und praktisch unbrauchbar.

## Häufige Fragen

### Was ist ein sinnvoller Standard, wenn ich nicht reguliert bin?

Skelett ein paar Jahre, Inhalt drei bis sechs Monate. Das deckt Streitfälle, Vorfallanalysen und Rechnungsdiskussionen ab, ohne ein Lager personenbezogener Daten zu führen.

### Muss ich Prompts und Antworten aufbewahren?

So lange, wie Sie eine konkrete Entscheidung erklären müssen könnten, ja. Danach trägt der Fingerabdruck den Beweis, und der Text ist nur noch Exposition.

### Gilt die Sechs-Monats-Regel für meinen Chatbot?

Mit ziemlicher Sicherheit nicht. Sie gilt für Hochrisiko-Systeme im Sinne der Verordnung, und gewöhnliche interne oder Produktivitätsagenten stehen nicht auf dieser Liste. Prüfen Sie die Liste, statt in eine Richtung zu vermuten.

### Wohin geht der Speicher wirklich?

In die Inhalte. Werkzeugergebnisse und Prompts dominieren, besonders wenn sie mehrfach abgelegt sind. Das strukturierte Skelett fällt daneben kaum ins Gewicht.

### Kann ich nicht alles behalten und später entscheiden?

Das ist die Option, die sicher wirkt und es nicht ist. Lange gehaltene Inhalte sind eine dauerhafte Haftung und das Erste, was eine Löschanfrage findet.

## Der nächste Schritt

Schreiben Sie zwei Zahlen auf, eine fürs Skelett und eine für den Inhalt, und machen Sie die fürs Skelett großzügig. Prüfen Sie dann, ob Ihre Aufzeichnung wirklich enthält, was diese Fenster schützen sollen: [was Sie je KI-Agentenlauf protokollieren](/de/blog/ai-agent-audit-trail).
`;

export default content;
