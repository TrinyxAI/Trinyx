// German translation of chat-to-workflow-no-code (public register, 2026-07-24).
const content = `Sie müssen keinen Code schreiben, um eine KI-Automatisierung zu bauen. Sie beschreiben in einem Satz, was passieren soll, und bekommen einen Workflow, den Sie ansehen, ausführen und ändern können.

Das ist die ganze Idee von No-Code-KI-Automatisierung: Sagen Sie die Aufgabe laut, behalten Sie das System, das Sie bekommen.

## Kurz gefasst

- Beschreiben Sie das Ergebnis, nicht die Schritte. Die Verkabelung übernimmt das Werkzeug.
- Sie bekommen ein Diagramm, keine Blackbox. Jeder Schritt steht auf dem Bildschirm.
- Verfeinern geht auf zwei Wegen: weiter chatten oder einen Schritt öffnen und bearbeiten.
- Behalten Sie eine menschliche Freigabe vor allem, was unumkehrbar beim Kunden landet.
- Ein paar Zeilen Code bleiben die richtige Antwort für exakte, mechanische Arbeit.

## Sagen Sie, wie "fertig" aussieht

Viele bringen eine Gewohnheit aus älteren Automatisierungswerkzeugen mit: erst in Schritten denken, einen Auslöser wählen, Feld A auf Feld B legen. Hier ist es umgekehrt.

Gehen Sie vom Ergebnis aus. Ein Satz reicht:

"Finde jeden Morgen die neuen Anmeldungen in meiner Tabelle und schicke jeder eine Slack-Willkommensnachricht."

Das beschreibt ein Ziel und die Form der Arbeit. Auslöser, Schleife, Nachschlagen und Zurückschreiben sind Verkabelung, und Verkabelung ist genau das, wofür das Werkzeug da ist.

![Ein Trinyx-Chat: links eine Anfrage in Alltagssprache, „jeden Morgen neue Anmeldungen in meiner Tabelle finden und jeder eine Slack-Willkommensnachricht senden“, und rechts der auf dem Canvas generierte Workflow: ein Morgen-Trigger, ein Schritt, der neue Anmeldungen findet, jede einzeln durchläuft, die Slack-Nachricht sendet und sie als begrüßt markiert.](/blog/chat-to-workflow-no-code-generated.png)

*Ein Satz hinein, ein lesbarer Workflow heraus. Die Anfrage links, die erzeugten Schritte rechts.*

## Sie bekommen ein Diagramm, keine Blackbox

Das ist der Teil, der viel mehr zählt, als es klingt.

Viele KI-Werkzeuge verstecken die Arbeit. Sie tippen eine Anfrage, irgendetwas passiert, und Sie drücken die Daumen. Geht es schief, gibt es nichts zu prüfen und nichts zu reparieren, also bleibt nur: umformulieren und nochmal versuchen.

| | Ein Prompt in einer Blackbox | Ein erzeugter Workflow |
|---|---|---|
| Sehen Sie die Schritte? | Nein | Ja, jeden |
| Können Sie einen Schritt ändern? | Nein, nur den Prompt | Ja, öffnen und bearbeiten |
| Wissen Sie, warum es so lief? | Nicht wirklich | Der genommene Weg ist protokolliert |
| Läuft es zweimal gleich? | Keine Garantie | Die Struktur liegt fest |
| Können Sie es übergeben? | Nur den Prompt | Das ganze Diagramm |

Wenn ein Schritt existiert, steht er auf dem Canvas. Nichts bleibt implizit.

## Ändern Sie es im Chat oder von Hand

Die erste Fassung ist selten die letzte, und beim Verfeinern verdient No-Code seinen Platz. Sie haben zwei Wege und können sie frei mischen.

| Sie wollen | Tun Sie das | Warum |
|---|---|---|
| Einen ganzen Zweig ergänzen | Weiter chatten: "markiere außerdem alles mit Erstattung als dringend" | Strukturänderungen gehen in Worten schneller |
| Eine Formulierung oder Kategorie korrigieren | Schritt öffnen und bearbeiten | Präzise, ohne Neuinterpretation |
| Schritte umsortieren | Beides | Das Diagramm ist maßgeblich |
| Einen Schwellenwert ändern | Schritt öffnen | Sie wollen die exakte Zahl, keine Umschreibung |

Beide Wege schreiben in dasselbe Diagramm, keiner sperrt den anderen aus.

## Wann ein paar Zeilen Code besser sind

No-Code deckt den größten Teil der Arbeit ab. Zu behaupten, es decke alles ab, ist der Grund für den schlechten Ruf solcher Werkzeuge.

Greifen Sie zum Code-Schritt, wenn die Logik mechanisch und exakt ist:

- Daten in genau die Struktur bringen, die der nächste Schritt erwartet.
- Datumsrechnung, eine Berechnung, eine Schwelle ohne jede Unschärfe.
- Ein Format lesen, das sonst nichts erkennt.

Alltagssprache für Urteilsfragen. Ein paar Zeilen Code für Exaktheit. Diese Aufteilung hält in der Praxis.

## Ein konkretes Beispiel: Support-Posteingang sortieren

Gleiche Idee, etwas größere Aufgabe. Eine Support-Mail kommt an und soll sortiert, beantwortet und geprüft werden.

| Schritt | Was passiert | Wer entscheidet |
|---|---|---|
| Auslöser | Eine neue Mail landet im Support-Postfach | Das Postfach |
| Klassifizieren | Ein kleiner KI-Schritt liest sie und gibt ein Label zurück: Fehler, Abrechnung oder Allgemein | Das Modell, nur auf dieser Mail |
| Verzweigen | Das Diagramm teilt sich anhand des Labels dreifach | Die Struktur, nicht das Modell |
| Entwerfen | Jeder Zweig schreibt eine Antwort im passenden Ton | Das Modell |
| Prüfen | Der Entwurf wartet in einer Warteschlange auf einen Menschen | Immer ein Mensch |
| Protokollieren | Was hereinkam, das Label, der Zweig, der Entwurf, wer freigegeben hat | Automatisch erfasst |

Achten Sie darauf, welche Entscheidungen dem Modell gehören und welche dem Diagramm. Das Modell liest und urteilt. Die Struktur entscheidet, was als Nächstes passiert. Diese Trennung hält das Ganze vorhersehbar, und sie wird vertieft in [Workflow oder ein einziger Agent](/de/blog/workflow-beats-do-everything-agent).

## Häufige Fragen

### Muss ich wissen, was ein Auslöser oder eine Node ist?

Nein. Später hilft es, wenn Sie Schritte direkt bearbeiten, aber für eine erste funktionierende Fassung brauchen Sie nichts davon.

### Was, wenn der erzeugte Workflow falsch ist?

Sagen Sie, was falsch ist, und er wird neu gebaut, oder öffnen Sie den betreffenden Schritt und korrigieren ihn selbst. Weil Sie jeden Schritt sehen, ist "falsch" meist ein konkreter Schritt und kein Rätsel.

### Ist das nicht nur ein Prompt mit Zusatzschritten?

Nein. Ein Prompt ist ein Aufruf und eine Ausgabe. Ein Workflow ist eine feste Struktur mit getrennten Schritten, echten Verzweigungen und einer Aufzeichnung des Weges jeder Ausführung, und genau das macht ihn einen Monat später noch debuggbar.

### Kann er echte Systeme anfassen, etwa E-Mail oder Slack?

Ja, das ist der Sinn. Setzen Sie eine menschliche Freigabe vor alles, was sich nicht rückgängig machen lässt, etwa an Kunden senden oder Geld ausgeben.

### Was kostet der Betrieb?

Meist weniger, als die ganze Aufgabe einem einzigen autonomen Agenten zu übergeben, weil jeder Schritt nur sieht, was er braucht. Wie viel weniger, hängt an der Zahl der Schritte: [der Kostenvergleich](/de/blog/workflow-beats-do-everything-agent) rechnet es mit offenen Zahlen vor.

## Der nächste Schritt

Suchen Sie sich eine wöchentliche Routineaufgabe, schreiben Sie sie als einen Satz und sehen Sie, was zurückkommt. Ändern Sie dann eine Sache daran. Das ist der ganze Kreislauf, und er dauert etwa zehn Minuten.
`;

export default content;
