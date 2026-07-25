// German translation of size-an-ai-agent-budget (public register, 2026-07-24).
const content = `Sie können einem KI-Agenten ein Budget geben. Schwierig ist zu wissen, welche Zahl ins Feld gehört. Zu hoch, und es stoppt nie etwas. Zu niedrig, und es tötet Arbeit, die gut lief.

So kommen Sie zu einer Zahl, die Sie verteidigen können, ohne Statistikstudium.

## Kurz gefasst

- Gehen Sie davon aus, was ein Schritt wirklich kostet, nicht davon, was sich sicher anfühlt.
- Legen Sie Puffer nach Werkzeugnutzung drauf: etwa 2x bei einem Einzelaufruf, 3x bis 4x bei werkzeuglastigen Schritten.
- Zu begrenzen, wie oft ein Agent Schleifen dreht, ist ein schlechter Weg, Geld zu begrenzen.
- Bei billigen Schritten begrenzen Sie die Eingabe. Bei teuren das Geld.
- Das Budget eines Laufs ist nicht die Summe der Schrittbudgets, weil Schritte sich wiederholen.

## Zuerst: was kostet ein Schritt?

Die Kosten unterscheiden sich zwischen Arbeitsarten weit stärker, als man erwartet. Das sind Beispiele aus einem konstruierten Modell, keine Produktionsmessungen, aber die Spannweite ist der Punkt.

| Art des Schritts | Was er tut | Typische Kosten pro Lauf |
|---|---|---|
| Klassifizieren | Liest eine Nachricht, gibt ein Label zurück | etwa 0,0003 $ |
| Entwerfen mit Nachschlagen | Holt ein Dokument, schreibt eine Antwort | etwa 0,013 $ |
| Recherche mit mehreren Werkzeugen | Rund sechs Werkzeugaufrufe, dann eine Zusammenfassung | etwa 0,27 $ |
| Langes Dokument zusammenfassen | Ein großer Lesevorgang, eine Antwort | etwa 0,04 $ |
| Browser-Schritt | Ein Dutzend Seitenaktionen, jede mit Momentaufnahme | etwa 1,67 $ |

Zwischen einem Klassifizierschritt und einem Browser-Schritt liegt mehr als das Tausendfache. Eine Budgetzahl für beide ergibt keinen Sinn, und deshalb gehören Budgets an Schritte und nicht an Agenten.

## Ihr Puffer ist nicht 2x

Die meisten nehmen die typischen Kosten und verdoppeln sie. Für einen Schritt mit einem Aufruf stimmt das ungefähr. Für alles mit Werkzeugen ist es deutlich falsch.

Der Grund: Jedes Werkzeugergebnis wird in alle späteren Aufrufe mitgeschleppt, also wächst der Preis nicht im Takt der Werkzeugaufrufe, sondern schneller. Die Werkzeugaufrufe eines werkzeuglastigen Schritts zu verdoppeln kann seine Kosten ungefähr vervierfachen.

| Art des Schritts | Wenn er doppelt so viele Schritte braucht wie üblich | Einzuplanender Puffer |
|---|---|---|
| Ein Aufruf, ohne Werkzeuge | Ungefähr die doppelten Kosten | 2x |
| Entwurf mit ein oder zwei Abrufen | Ungefähr das Dreieinhalbfache | 3x bis 4x |
| Werkzeuglastige Recherche oder Browsing | Ungefähr das Vierfache | 3x bis 4x |

Die praktische Schlussfolgerung ist immer dieselbe: "Wir heben die maximalen Iterationen etwas an" ist keine kleine Änderung. Es ist die Entscheidung, die Decke ungefähr zu vervierfachen.

![Die LiveContext-Agentenmetriken: eine Übersichtszeile mit Gesamtausführungen, Tokens, Tool-Aufrufen und Erfolgsquote über einer Tabelle pro Agent mit Ausführungen, Tokens, Tool-Aufrufen, verbrauchten Credits, Modell, Dauer und Erfolgsquote.](/blog/cap-ai-agent-cost-budgets-metrics.png)

*Ausgaben, Tokens und Werkzeugaufrufe je Agent aus echten Läufen. Das ist die Eingangsgröße fürs Dimensionieren: Die Zahl, die Sie setzen, sollte aus Ihrer eigenen Verteilung stammen, nicht aus einem Bauchgefühl.*

## Warum ein Iterationslimit Geld schlecht begrenzt

Viele Werkzeuge erlauben nur, die Zahl der Runden zu begrenzen. Das fühlt sich nach Limit an. Rechnet man nach, ist es kaum eines.

| Schritt | Erwartete Kosten | Kosten beim Limit von 100 Runden |
|---|---|---|
| Recherche mit mehreren Werkzeugen | etwa 0,27 $ | etwa 47 $ |
| Browser-Schritt | etwa 1,67 $ | etwa 101 $ |

Ein Limit, das das Sechzigfache der erwarteten Rechnung zulässt, schützt vor nichts. Ist ein Rundenzähler Ihre einzige Kontrolle, setzen Sie ihn nah an das, was echte Arbeit braucht (ein paar Aufrufe für einfache Abfragen, zehn bis fünfzehn für einen Vergleich), statt auf eine runde Zahl wie 100.

## Billige Schritte: Eingabe begrenzen. Teure: Geld begrenzen.

Es gibt einen Boden, unterhalb dessen ein Geldlimit physisch nicht funktionieren kann.

Ein Budget kann nur den *nächsten* Aufruf verweigern, es braucht also Platz für mehrere Aufrufe vor der Decke. Faustregel: Das Budget sollte mindestens das Dreifache des größten möglichen Aufrufs dieses Schritts betragen. Darunter kann der erste Aufruf die Decke sprengen, und das Budget kommt nie zum Zug.

Bei billigen Schritten liegt dieser Boden über dem, was der Schritt kostet, ein Geldlimit ist dort also Theater. Was dort wirkt, ist die Eingabe zu begrenzen: Deckeln Sie, wie viel Text der Schritt bekommt und wie viel er zurückschreiben darf. Damit fällt der schlimmste Aufruf um eine Größenordnung, und der Boden fällt mit.

| Art des Schritts | Die Kontrolle, die wirkt | Warum |
|---|---|---|
| Klassifizieren, kurze Abfragen | Eingabegröße begrenzen | Der Schritt ist ohnehin begrenzt, ein Geldlimit greift nicht |
| Arbeit mit langen Dokumenten | Eingabegröße begrenzen | Ein großer Aufruf: Die Eingabe *ist* der Preis |
| Recherche, Browsing, alles Wiederholende | Geld begrenzen | Der Preis kommt aus Wiederholung, die nur Geld begrenzt |

## Das Laufbudget ist nicht die Summe der Schrittbudgets

Hier scheitert sorgfältiges Dimensionieren meistens.

Schritte wiederholen sich. Ein Schritt in einer Schleife über fünfzig Einträge läuft fünfzigmal. Ein Zweig, der sich öffnet, läuft je Zweig einmal. Die Laufdecke muss also entlang des teuersten Pfades durch den Workflow gerechnet werden, Wiederholungen eingeschlossen, und nicht als Summe je gezeichnetem Schritt auf dem Canvas.

Und wenn ein Lauf sich aufteilt, verweigern Sie ihn vor dem Start, statt ihn mittendrin abzubrechen. Eine laufende Aufteilung abzuschneiden hinterlässt eine willkürliche Teilmenge fertiger Zweige, und welche überleben, hängt an der Startreihenfolge. Vorab verweigern hinterlässt etwas Wiederholbares.

## Wie Sie die Zahl wählen

1. **Sammeln Sie einige echte Läufe.** Je Schritt: Eingabe-Tokens, Ausgabe-Tokens, Zahl der Werkzeugaufrufe, Modell und wie er endete.
2. **Dimensionieren Sie nicht auf den Mittelwert.** Kosten sind schief verteilt: Die meisten Läufe sind billig, wenige teuer, der Mittelwert liegt also weit unter der Mitte des Risikos. Darauf zu dimensionieren tötet rund ein Drittel legitimer Arbeit.
3. **Seien Sie ehrlich zu Ihrer Stichprobe.** Es braucht einige hundert Läufe, bevor man ohne Erröten von einem Worst Case spricht. Darunter dimensionieren Sie auf den strukturellen Worst Case (den größten Aufruf, den das Modell physisch machen kann), statt eine Verteilung vorzutäuschen.
4. **Achten Sie auf die Häufung.** Ein Limit, das 5 % der Schritte tötet, klingt erträglich, bis Sie zehn Schritte haben: Das sind 40 % der Läufe, die irgendwo an ein Limit stoßen. Schrittlimits müssen viel großzügiger sein als Ihre Toleranz auf Laufebene.
5. **Testen Sie es.** Überfüttern Sie absichtlich einen Schritt und prüfen Sie, dass Sie eine saubere Ablehnung mit Nennung des Limits bekommen. Ein ungetestetes Limit ist ein Bauchgefühl mit einer Zahl darauf.

## Häufige Fragen

### Welches Startbudget ist für einen Agenten sinnvoll?

Nehmen Sie die erwarteten Kosten seines teuersten Schritts, multiplizieren Sie mit drei oder vier, wenn er Werkzeuge nutzt, und setzen Sie das je Schritt. Setzen Sie dann ein Laufbudget entlang des längsten Pfades, alles Wiederholende eingerechnet.

### Warum nicht großzügig budgetieren und vergessen?

Weil ein großzügiges Budget erst nach dem Schaden greift. Der Wert einer Decke ist der Lauf, den sie verweigert, und eine Decke beim Sechzigfachen der erwarteten Kosten verweigert nichts Nennenswertes.

### Mein Agent läuft ständig ins Budget. Anheben oder reparieren?

Sehen Sie nach, was sich geändert hat, bevor Sie etwas anheben. Ans Limit zu stoßen heißt meist, dass die Eingabe gewachsen ist oder der Agent zu kreisen begann, und beides gehört repariert, nicht finanziert.

### Budget je Schritt oder reicht eines je Agent?

Je Schritt, wenn die Schritte unterschiedlicher Natur sind. Zwischen Klassifizieren und Browsen liegt Faktor tausend, und eine Zahl kann nicht für beides richtig sein.

### Wie oft sollte ich diese Zahlen prüfen?

Immer wenn Sie Modell, Promptgröße oder die Befugnisse des Schritts ändern. Alle drei verschieben die Kosten, und ein Budget nach der Form des letzten Quartals leckt entweder oder würgt ab.

## Der nächste Schritt

Dimensionieren nützt nur, wenn die Decke einen Lauf wirklich stoppen kann. Prüfen Sie zuerst diese Seite: [wie Sie verhindern, dass ein Agent zu viel ausgibt](/de/blog/cap-ai-agent-cost-budgets) erklärt, woraus eine echte Decke besteht und wie Sie beweisen, dass Ihre funktioniert.
`;

export default content;
