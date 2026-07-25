// German translation of from-dataset-to-live-workflow (public register,
// 2026-07-24). Structure identical to the English source.
const content = `Ein Datensatz nützt nichts, bis etwas ihn regelmäßig liest, entscheidet, was sich geändert hat, und handelt. So kommen Sie von einer Datei, die Sie von Hand prüfen, zu einem Workflow, der sich selbst prüft.

Das durchgehende Beispiel ist eine Preisüberwachung: ein paar Produkte verfolgen, merken, wenn eines sich bewegt, und jemanden warnen, bevor es Geld kostet. Die Form passt auf alles mit einem Rhythmus.

## Kurz gefasst

- Wählen Sie eine Quelle, die sich in einem vorhersagbaren Rhythmus ändert.
- Bereinigen Sie einmal, direkt am Eingang, damit alle späteren Schritte ihr trauen können.
- Berechnen Sie erst die Entscheidung und verzweigen Sie auf die Entscheidung, nicht auf Rohwerte.
- Setzen Sie eine menschliche Freigabe vor alles Unumkehrbare.
- Schreiben Sie das Ergebnis zurück, damit der nächste Lauf weiß, was der letzte getan hat.

## Der Aufbau in sechs Schritten

| Schritt | Was er tut | Warum er da ist |
|---|---|---|
| 1. Zeitplan | Löst stündlich aus | Der Takt. Niemand muss ans Starten denken |
| 2. Abruf | Liest die Quelle live | Hier kommen frische Daten herein |
| 3. Bereinigen | Bringt alles auf dieselben wenigen Felder | Alles Nachgelagerte muss nicht mehr raten |
| 4. Nachschlagen | Prüft, ob Sie diesen Eintrag schon kennen | Verhindert Duplikate und liefert den Vorwert |
| 5. Entscheiden | Hat er sich um mehr als 5 % bewegt? | Die eigentliche Frage |
| 6. Freigeben, dann handeln | Ein Mensch bestätigt, dann gehen Warnung und Schreibvorgang raus | Der unumkehrbare Teil, abgesichert |

![Der LiveContext-Workflow-Builder mit dem achtknotigen Preisüberwachungs-Graphen auf dem Canvas: ein stündlicher Trigger, ein HTTP-Abruf, eine Code-Node, ein Tabellen-Lookup und eine Entscheidung, die einen neuen SKU von einem bekannten trennt, dann eine Preisbewegungs-Entscheidung, ein Genehmigungs-Gate und der abgesicherte Schreibvorgang.](/blog/from-dataset-to-live-workflow-builder.png)

*Der ganze Aufbau auf einem Canvas: vom stündlichen Auslöser links bis zum freigegebenen Schreibvorgang rechts.*

## Schritt 1: eine Quelle mit Takt wählen

Automatisieren Sie Daten, die sich in einem benennbaren Takt ändern. Nicht "wöchentlich", sondern "eine CSV je Lieferant, per Mail, jeden Montag vor 9 Uhr". Diese Genauigkeit entscheidet über Ihren Auslöser.

Ändert sich die Quelle fast nie, brauchen Sie keinen Workflow, sondern eine Abfrage, und sparen sich den Aufwand.

## Schritt 2 und 3: abrufen, dann einmal bereinigen

Rohquellen sind unordentlich. Spaltennamen wandern, Datumsangaben kommen in drei Formaten, ein Lieferant schreibt "Stückpreis", der andere "Preis/St.".

Bereinigen Sie an genau einer Stelle, dort, wo die Daten hereinkommen. Legen Sie zuerst die gewünschte Form fest (für die Preisüberwachung: Produkt, Preis, Währung, gesehen-am) und lassen Sie jede Quelle genau diese Form erzeugen und sonst nichts. Alles Weitere wird einfacher, weil es seiner Eingabe trauen kann.

Eine Warnung, die jeden einmal erwischt: Ein fehlgeschlagener Abruf kommt oft als Erfolg getarnt an. Viele Dienste liefern eine Fehlermeldung innerhalb einer völlig normalen Antwort. Prüfen Sie, dass das Zurückgekommene wirklich die Daten sind, bevor Sie es weitergeben, sonst wandert der Fehler still durch den ganzen Workflow.

## Schritt 4 und 5: entscheiden, dann verzweigen

Der Zweck des Workflows ist eine Entscheidung, also machen Sie die Entscheidung explizit.

Die Falle ist, auf den Rohwert zu verzweigen. Es interessiert nicht, dass der Preis 12,40 beträgt. Es interessiert, ob er seit dem letzten Mal um mehr als Ihre Toleranz gestiegen ist. Berechnen Sie das zuerst und verzweigen Sie auf die Antwort.

Das hat auch eine sehr praktische Seite. Filter, die numerisch aussehen, werden intern oft als Text verglichen, und Text sortiert anders als Zahlen: "100" kommt vor "9". Ein Filter "Preis größer als 9" kann also stillschweigend die 100 verfehlen, um die es ging. Holen Sie den Vorwert, rechnen Sie in einem expliziten Entscheidungsschritt und verzweigen Sie darauf.

## Schritt 6: das Unumkehrbare absichern

Der letzte Schritt soll etwas Echtes tun: die Warnung senden, die Zeile aktualisieren, das Ticket anlegen, die Bestellung vorbereiten.

Ist diese Aktion teuer oder ohne Rückweg, setzen Sie eine menschliche Freigabe davor. Der Lauf pausiert, wartet auf eine Person und macht dann genau dort weiter, wo er stehen blieb. Billige, umkehrbare Aktionen dürfen unbeaufsichtigt laufen. Alles, was einen Kunden erreicht oder Geld ausgibt, bekommt ein Tor.

Zwei Dinge zur Pause. Zweimal freigeben schadet nicht: Die erste Antwort zählt. Und der nächste geplante Lauf überfährt keine Entscheidung, über die noch jemand nachdenkt: Jeder Lauf behält seine eigenen Ergebnisse.

## Die eine Absicherung, die wiederholte Läufe sicher macht

Ein stündlicher Auslöser wiederholt stündlich dieselbe Lesung. Ohne Absicherung fügt er stündlich dieselbe Zeile ein und Ihre Tabelle füllt sich mit Duplikaten.

Das Muster, das das behebt, auf jedem Werkzeug: **erst suchen, dann entscheiden, dann schreiben**. Suchen Sie den Eintrag. Ist die Trefferzahl null, ist er neu, also schreiben. Sonst existiert er schon, also aktualisieren. Fügen Sie nie bedingungslos ein, wenn derselbe Eintrag erneut geholt werden kann.

Diese Suche hat doppelten Nutzen. Sie ist Ihr Duplikatschutz und zugleich die Quelle des Vorwerts, was die Frage "hat er sich bewegt?" überhaupt beantwortbar macht.

## Vier Fallen, die einen Nachmittag kosten

| Falle | Was Sie sehen | Was wirklich passiert |
|---|---|---|
| Stilles leeres Ergebnis | Ein Schritt liefert nichts, ohne Fehler | Die Daten liegen eine Ebene tiefer als erwartet |
| Fehlabruf mit normalem Aussehen | Alles Nachgelagerte ist falsch | Der Fehler kam in einer normalen Antwort |
| Zahl als Text verglichen | Eine Schwelle verfehlt still Fälle | "100" sortiert vor "9" |
| Stündliche Duplikate | Die Tabelle wächst endlos | Keine Erst-suchen-Absicherung vor dem Schreiben |

Keiner dieser Fälle wirft einen Fehler. Genau deshalb kosten sie einen Nachmittag.

## Prüfen Sie jeden Zweig, bevor Sie live gehen

Gehen Sie nicht nur über den glücklichen Pfad live. Lösen Sie jeden Fall absichtlich aus und prüfen Sie, was der Workflow tatsächlich getan hat.

| Test | Was Sie auslösen | Was passieren soll |
|---|---|---|
| Neuer Eintrag | Ein Eintrag ohne Historie | Genau eine Zeile geschrieben |
| Keine Änderung | Bekannter Eintrag, Preis stabil | Nichts gesendet, nichts geschrieben |
| Echte Änderung | Bekannter Eintrag, Preis 10 % höher | Der Lauf pausiert zur Freigabe |
| Ablehnung | Freigabe verweigern | Keine Warnung, kein Schreibvorgang |
| Zweimal laufen | Zeitplan erneut auslösen | Die Zeilenzahl bleibt gleich |

Endet der Fall "echte Änderung" ohne Pause, wird Ihre Schwelle an einer Stelle ausgewertet, die Sie nicht vorgesehen hatten. Diesen Fehler fängt man besser vor dem Livegang als danach.

## Häufige Fragen

### Wie oft soll er laufen?

Im Takt der Quelle. Stündlich für Preise, täglich für einen Bericht, wöchentlich für eine Lieferantendatei. Häufiger laufen als sich die Daten ändern kostet Aufrufe und bringt nichts.

### Wo bewahre ich die Historie auf?

In einer Tabelle, die der Workflow selbst liest und schreibt. Das macht aus einzelnen Läufen etwas mit Gedächtnis: Er weiß, was er schon bearbeitet hat, und hat den gestrigen Wert zum Vergleich.

### Was passiert, wenn ein Lauf mittendrin scheitert?

Der Lauf stoppt am fehlerhaften Schritt, und die Aufzeichnung zeigt, welcher es war und was er bekommen hatte. Sie reparieren diesen Schritt und starten neu, statt über das Ganze zu grübeln.

### Brauche ich einen Menschen in der Schleife?

Für alles Unumkehrbare ja, zumindest bis Sie Vertrauen haben. Automatisch senden auf Basis einer Fehlinterpretation ist der Grund für den schlechten Ruf von Automatisierung. Beginnen Sie mit dem Tor und entfernen Sie es später, wenn die Belege es tragen.

## Der nächste Schritt

Suchen Sie eine Quelle, die Sie ohnehin jede Woche von Hand prüfen. Notieren Sie die Entscheidung, die sie speist, die Schwelle, die Sie verwenden, und was Sie tun, wenn sie überschritten wird. Das ist der Workflow, und Sie haben ihn gerade entworfen. Sehen Sie dann, [was zu protokollieren ist](/de/blog/ai-agent-audit-trail), damit Sie für sein Handeln geradestehen können.
`;

export default content;
