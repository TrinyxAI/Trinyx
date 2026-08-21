// German translation of cap-ai-agent-cost-budgets (public register, 2026-07-24).
const content = `Fast alle bösen Überraschungen auf der KI-Rechnung haben dieselbe Ursache: ein Agent ohne Obergrenze. Er drehte Schleifen, versuchte es erneut, schleppte eine wachsende Unterhaltung mit sich, und niemand merkte es, bis die Rechnung kam.

Das Gegenmittel ist weder ein besseres Modell noch ein besserer Prompt. Es ist ein Limit, das den nächsten Aufruf verweigert, und die meisten Dinge, die Leute Budget nennen, tun genau das nicht.

## Kurz gefasst

- Ein Alarm sagt Ihnen, was Sie schon ausgegeben haben. Er ist kein Limit.
- Das Ausgabelimit Ihres Anbieters ist meist eine Benachrichtigung, kein harter Stopp.
- Kein Budget kann den Aufruf stoppen, den es gerade macht. Der reale Worst Case ist Ihr Budget plus ein Aufruf.
- Die meisten Agenten-Frameworks liefern gar kein Kostenlimit, oder eines, das Aufrufe statt Geld zählt.
- Der entscheidende Test: Hat Ihr Limit je etwas verweigert?

## Ein Alarm ist kein Limit

Ein Monitor läuft, wenn das Geld weg ist. Ein Limit läuft vor dem nächsten Aufruf und sagt nein. Beides ist nützlich, aber nur eines ist eine Kontrolle.

| | Ein Monitor | Ein echtes Limit |
|---|---|---|
| Wann es greift | Nach Abschluss des Aufrufs | Vor Beginn des nächsten |
| Was es kann | Sie informieren | Verweigern |
| Worst Case | Unbegrenzt | Ein weiterer Aufruf |
| Wofür es taugt | Limit dimensionieren, Abweichungen erkennen | Den Lauf stoppen |

Hier ein Test, den Sie heute machen können und der keine Schwelle braucht: Holen Sie die verzeichneten Ablehnungen Ihres aktuellen Limits. Hat es je etwas verweigert? Eine Zahl, die nie einen einzigen Aufruf abgelehnt hat, ist keine Kontrolle, sondern ein Kommentar.

![Die Trinyx-Agentenmetriken: eine Übersichtszeile mit Gesamtausführungen, Tokens, Tool-Aufrufen und Erfolgsquote über einer Tabelle pro Agent mit Ausführungen, Tokens, Tool-Aufrufen, verbrauchten Credits, Modell, Dauer und Erfolgsquote.](/blog/cap-ai-agent-cost-budgets-metrics.png)

*Ausgaben je Agent, im Nachhinein. Genau die richtige Sicht, um ein Limit festzulegen, und genau das Falsche, um damit einen Lauf zu stoppen.*

## Was das Ausgabelimit Ihres Anbieters wirklich tut

Man hält die Zahl im Anbieter-Dashboard für eine Mauer. Meist ist sie eine Türklingel.

| Anbieter-Kontrolle | Was es wirklich ist |
|---|---|
| Projekt- oder Organisationslimit bei OpenAI | Standardmäßig ein weiches Budget: Es benachrichtigt, Anfragen laufen weiter. Ein harter Stopp existiert als separat zu aktivierende Option und lehnt dann Aufrufe ab, bis Sie erhöhen |
| Spend-Limits-API von Anthropic | Nur Enterprise, nur monatlich, und sie deckt die Nutzung menschlicher Lizenzen ab, nicht die API-Ausgaben von Agenten |
| Monatliche Obergrenze je Stufe bei Anthropic | Eine echte Decke, aber organisationsweit und monatlich: Ein außer Kontrolle geratener Lauf macht aus einem Kostenfehler einen Ausfall für alle |

Quellen: der [Spend-Limits-Leitfaden von OpenAI](https://developers.openai.com/api/docs/guides/spend-limits), die [Spend-Limits-API](https://platform.claude.com/docs/en/manage-claude/spend-limits-api) und die [Rate Limits](https://platform.claude.com/docs/en/api/rate-limits) von Anthropic. Anthropics eigene Dokumentation geht weiter und rät davon ab, auf ihre Ausgabenzahl zu schalten: Sie kann null anzeigen, wenn der Wert gerade nicht verfügbar ist, und ist als informativ zu behandeln.

Daraus folgen zwei Dinge. Anbieterlimits sind ein Sicherheitsnetz, nicht Ihre erste Verteidigungslinie. Und eine monatliche, organisationsweite Decke hat die falsche Form, um einen einzelnen fehlerhaften Lauf zu stoppen: Wenn sie greift, reißt sie alles andere mit.

## Sie können den laufenden Aufruf nicht stoppen

Das ist der Teil, den jeder ehrliche Budgetartikel aussprechen muss.

Was ein Aufruf gekostet hat, wissen Sie erst, wenn er fertig ist. Kein laufendes Budget kann also verhindern, dass ein teurer Aufruf die Decke sprengt. Es kann nur den nächsten verhindern. Ihr realer Worst Case ist das Budget plus ein Aufruf.

Das hat eine praktische Folge. Wenn ein einzelner Aufruf plausibel die Hälfte Ihres Budgets kosten kann, kann Ihr Budget nicht funktionieren. Eine Decke verhält sich nur dann wie eine Decke, wenn sie deutlich größer ist als der größte mögliche Einzelaufruf, und eine Faustregel von Faktor drei ist ein vernünftiger Boden. Das sauber zu dimensionieren ist ein eigenes Thema: [wie viel Budget pro Agent](/de/blog/size-an-ai-agent-budget) rechnet es durch.

Es heißt außerdem, dass eine gute Umsetzung vorhersagt, bevor sie ausgibt. Sie betrachtet, was die letzten Schritte gekostet haben, wie schnell das wächst, und wie groß der größte Aufruf wäre, den dieses Modell physisch machen kann, und verweigert, wenn die Prognose die Decke sprengen würde. Vorhersagen ist der ganze Trick, denn Messen kommt immer zu spät.

## Was gängige Werkzeuge tatsächlich begrenzen

Wenn Sie annehmen, Ihr Framework schütze Sie, prüfen Sie es. Die meisten begrenzen etwas anderes als Geld, und die meisten kommen ohne Limit.

| Werkzeug | Was es begrenzt | Standard |
|---|---|---|
| Claude Agent SDK | Dollar pro Lauf und Runden | Beides unbegrenzt |
| Anthropic Messages API | Tokens pro Antwort | Kein Standard, muss gesetzt werden |
| OpenAI-Konto | Dollar pro Monat | Weich, nur Benachrichtigung |
| OpenAI Agents SDK | Anzahl Runden | 10 |
| LangGraph | Anzahl Schritte | Mal mit 25, mal mit 1000 dokumentiert |
| LangChain-Middleware | Anzahl Aufrufe, kein Kosten- oder Tokenbudget | Kein Limit |
| Pydantic AI | Tokens, Anfragen, Tool-Aufrufe | 50 Anfragen, kein Tokenlimit |
| CrewAI | Iterationen | 20 oder 25, je nach Doku-Seite |

Drei Dinge, die man aus dieser Tabelle mitnehmen sollte.

**Fast alles ist standardmäßig unbegrenzt.** Die sichere Annahme lautet: Sie haben keine Decke, bis Sie eine setzen.

**Aufrufe zählen ist kein Budget.** Zehn Aufrufe können einen Cent oder zehn Euro kosten, je nachdem wie viel Text jeder trägt. LangChains Middleware begrenzt Aufrufzahlen und hat gar kein Token- oder Kostenbudget.

**Ein Limit, das Sub-Agenten nicht erreicht, ist Dekoration.** So stellt sich eine Decke am häufigsten als Attrappe heraus: Der Elternprozess bekommt ein Limit, startet Kinder, und die Kinder laufen mit den Standardwerten. Für weit verbreitete Frameworks ist genau das dokumentiert. Wenn Sie nur eine Sache aus diesem Artikel umsetzen, dann diese: Setzen Sie ein Elternlimit, starten Sie ein Kind, und weisen Sie nach, dass es vererbt wird.

## Vier Regeln für ein Budget, das wirkt

1. **Begrenzen Sie Geld oder Tokens, nicht Schritte.** Der Preis eines Schritts schwankt. Der eines Euro nicht.
2. **Geben Sie jedem Schritt eine Decke und dem ganzen Lauf ebenfalls eine.** Ein Lauf, der sich in fünfzig parallele Zweige öffnet, kann jedes Schrittbudget einhalten und trotzdem das Fünfzigfache kosten.
3. **Reservieren Sie vor dem Start, unterbrechen Sie nicht mitten im Flug.** Zweige mittendrin abzuschneiden hinterlässt ein zufälliges Halbergebnis. Den Start zu verweigern ist explizit und wiederholbar.
4. **Wenn die Decke greift, bewahren Sie die geleistete Arbeit.** Ein Stopp, der alles Bisherige verwirft, macht aus einem Kostenproblem einen Totalverlust, und genau deshalb schalten Betreiber Decken ab.

Der letzte Punkt verdient eine eigene Zeile. Ein Budgetstopp sollte zurückgeben, was der Agent erzeugt hat, dazu die Aufstellung der Ausgaben und den Grund des Stopps, und er sollte benennen, welche Decke gegriffen hat. Ein Stopp, der nur "Budget überschritten" sagt, gibt Ihnen nichts an die Hand.

## Wie schlimm wird es wirklich?

Es gibt keine veröffentlichte Grundrate dafür, wie oft Agenten in Produktion außer Kontrolle geraten, also misstrauen Sie jeder selbstsicher genannten Häufigkeit. Dokumentiert ist die Größenordnung, und sie ist nüchterner als die Legende.

Erfasste Vorfälle liegen zwischen einigen hundert und wenigen tausend Dollar: rund 2.150 Dollar ungewollte Ausgaben in einem Fall, 235 Dollar in vier Tagen bei einem einzelnen Nutzer, eine Überschreitung von 70 % über ein gesetztes Budget. Die meistgeteilte Geschichte der Branche dagegen, ein anonymes "wir haben 47.000 Dollar für KI-Agenten ausgegeben", nennt keine Firma, zeigt keine Rechnung, und ihre eigenen Wochenzahlen summieren sich auf 25.658 Dollar, nicht 47.000.

Das echte Risiko ist keine spektakuläre Rechnung. Es ist ein leises, wiederkehrendes Leck im niedrigen vierstelligen Bereich, das niemand zuordnet, Monat für Monat.

## Häufige Fragen

### Begrenzt ein Token-Maximum meine Kosten?

Nur die Größe jeder Antwort. Gegen die Zahl der Schleifen, aus der die entlaufenen Kosten stammen, tut es nichts.

### Soll ich das Ausgabelimit meines Anbieters nutzen?

Ja, als Sicherheitsnetz, und aktivieren Sie die harte Variante, falls angeboten. Halten Sie es nur nicht für Ihre Kontrolle: Es ist meist monatlich, organisationsweit und standardmäßig weich.

### Was ist ein sinnvolles Startbudget?

Mindestens das Dreifache des größten möglichen Einzelaufrufs, sonst kann es platzen, bevor es überhaupt verweigern kann. Beginnen Sie dort und justieren Sie mit echten Läufen.

### Meine Decke hat nie gegriffen. Gut so?

Das heißt, sie ist ungetestet, nicht dass sie funktioniert. Setzen Sie ein absichtlich winziges Budget auf einen Testagenten und prüfen Sie, dass Sie eine saubere, typisierte Ablehnung mit Nennung des Limits bekommen.

### Ersetzen Schleifenerkennungen Budgets?

Nein, sie beantworten eine andere Frage. Eine Schleifenerkennung begrenzt, wie oft sich etwas wiederholt. Ein Budget begrenzt, was diese Wiederholungen kosten dürfen. Sie wollen beides.

## Der nächste Schritt

Prüfen Sie diese Woche drei Dinge: Betrifft Ihre Decke Geld statt Aufrufzahlen, erreicht sie Sub-Agenten, und hat sie je etwas verweigert. Wählen Sie dann die Zahl selbst mit [wie viel Budget pro KI-Agent](/de/blog/size-an-ai-agent-budget).
`;

export default content;
