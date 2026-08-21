// German translation of workflow-beats-do-everything-agent (public register,
// 2026-07-24). Keep the cents consistent with the English source.
const content = `Ein KI-Agent, der alles erledigt, kostet fast immer mehr als dieselbe Aufgabe, aufgeteilt in ein paar enge Schritte. Wie viel mehr, hängt an einer einzigen Sache: wie oft der Agent seine Schleife dreht, bis er fertig ist. Bei einer kurzen Aufgabe kaum ein Unterschied. Bei einer langen, mäandernden kann der Agent zwanzig- oder dreißigmal so viel kosten.

Das ist die ehrliche Fassung. Zuerst aber die Zahl, die wir zurücknehmen mussten.

## Kurz gefasst

- Der Kostenunterschied ist real, hängt aber fast vollständig an der Zahl der Schritte des Agenten.
- Bei einem typischen Support-Ticket kostet ein Agent rund 19 Cent, ein aufgeteilter Workflow rund 2.
- Mit Caching sinkt der Agent auf rund 9 Cent, was den Abstand halbiert.
- Kurze oder offene Aufgabe: Agent bauen. Wiederholte Aufgabe mit bekannter Form: Workflow bauen.
- Zuverlässigkeit und Bauaufwand wiegen meist schwerer als die Token-Rechnung.

## Die Behauptung, die wir gelöscht haben

Ein früherer Entwurf dieses Artikels sagte, ein aufgeteilter Workflow laufe "rund zehnmal günstiger" als ein Alleskönner-Agent. Wir haben das gestrichen. Dahinter stand keine Rechnung und keine Quelle, nur eine Zahl, die richtig klang.

Es gibt auch keine saubere Studie als Ersatz. Niemand hat dieselbe echte Aufgabe in beiden Bauformen mit nebeneinander gemessenen Kosten veröffentlicht. Selbst Anthropics eigener Leitfaden [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents) widmet dem Thema zwei Sätze und keine einzige Zahl: Agenten "tauschen Latenz und Kosten gegen bessere Leistung", und ihre Autonomie "bedeutet höhere Kosten". Wahr, aber keine Zahl, mit der man planen kann.

Alles Folgende ist deshalb aus nachprüfbaren Annahmen gerechnet und nicht aus fremden Schlagzeilen übernommen.

## Warum der Agent mehr kostet

Eine Idee erklärt das Ganze. Ein KI-Modell hat zwischen Aufrufen kein Gedächtnis. Bei jedem weiteren Schritt muss dem Agenten die gesamte Unterhaltung erneut übergeben werden: die ursprünglichen Anweisungen, alle Werkzeuge, die er nutzen könnte, und alles bisher Geschehene.

Die erste Runde ist also billig. Die zweite liest die erste erneut. Die dritte liest die beiden ersten erneut. Bei der achten bezahlt der Agent dafür, einen wachsenden Stapel eigener Vorarbeit immer wieder zu lesen. Die Kosten addieren sich nicht linear, sie rollen als Schneeball.

Ein aufgeteilter Workflow vermeidet den Schneeball. Jeder Schritt bekommt nur, was er braucht, erledigt es und übergibt ein kleines, sauberes Ergebnis an den nächsten. Schritt vier liest die Schritte eins bis drei nie erneut. Es gibt keinen wachsenden Stapel.

Das ist der ganze Mechanismus. Der Rest ist, Geld daranzuschreiben.

## Ein echtes Beispiel: Support-Triage

Nehmen wir eine übliche Aufgabe. Ein Support-Ticket kommt an, und Sie wollen es einordnen, das Kundenkonto nachschlagen, Ihre Hilfeartikel durchsuchen, eine Antwort entwerfen und diese vor dem Versand prüfen.

| Ansatz | Kosten pro Ticket |
|---|---|
| Ein Alleskönner-Agent | etwa 0,19 $ |
| Aufgeteilter Workflow | etwa 0,023 $ |

Als ein Agent gebaut kostet dieses Ticket rund 19 Cent. Als Workflow gebaut (vier kleine KI-Schritte plus zwei gewöhnliche Abfragen ganz ohne KI) kostet dasselbe Ticket gut 2 Cent. Rund achtmal weniger.

Woher kommt der Abstand? Der Agent dreht etwa acht Runden und liest in jeder ein dickeres Protokoll als zuvor. Der Workflow erledigt dieselbe echte Arbeit in vier fokussierten Schritten, von denen keiner den Ballast der anderen trägt. Gleiche Antwort am Ende, sehr unterschiedliche Rechnung. (Die Preise hier folgen den [Listenpreisen der Modelle](https://platform.claude.com/docs/en/about-claude/pricing); Ihre werden abweichen.)

Eine faire Anmerkung, bevor Sie den Faktor acht einplanen: Beide Ansätze müssen die eigentliche Antwort schreiben, und Schreiben kostet in beiden Fällen gleich viel. Dieser Entwurf ist ein guter Teil der 2 Cent des Workflows, und deshalb ist der Abstand etwa achtfach und nicht etwa achtzigfach.

![Ein Trinyx-Workflow-Lauf in der Observability-Ansicht: der ausgeführte Graph mit einem grünen Haken an jeder Node, daneben ein Lauf-Inspektor mit Epoch, Start- und Endzeitstempeln sowie Status, Dauer und Kosten jeder Node.](/blog/ai-agent-audit-trail-run.png)

*Ein abgeschlossener Lauf, Schritt für Schritt, mit Dauer und Kosten je Schritt. Genau diese Sicht macht die Rechnung erklärbar statt zu einer einzigen Summe.*

## Es hängt vor allem an der Zahl der Schritte

Der Faktor acht ist kein Gesetz. Er ergibt sich, wenn der Agent acht Runden dreht. Ändern Sie die Rundenzahl, ändert sich das ganze Bild.

| Schritte des Agenten | Ungefährer Mehrpreis des Agenten |
|---|---|
| 2 | praktisch gleich (1,3x) |
| 8 | etwa 8x mehr |
| 20 | etwa 37x mehr |

Lesen Sie diese Tabelle als die eigentliche Überschrift. Ein Kostenfaktor ohne Schrittzahl bedeutet nichts. Wenn Ihnen jemand "Agenten kosten 10x" nennt, sollte Ihre erste Frage lauten: bei einer Aufgabe mit wie vielen Schritten?

Auch hier gehört eine faire Einschränkung dazu. Die letzte Zeile zählt nur, wenn die Aufgabe wirklich zwanzig Schritte braucht. Ein Agent, der sich in zwanzig Runden verheddert, wofür ein sauberer Workflow vier braucht, ist nicht teuer, sondern verloren, und das ist zuerst ein Qualitätsproblem.

## Wann ein einzelner Agent richtig ist

Aufteilen gewinnt nicht immer, und etwas anderes zu behaupten wäre nur ein weiteres Verkaufsargument.

| Situation | Bauen Sie das | Warum |
|---|---|---|
| Kurze Aufgabe, zwei bis drei Schritte | Ein Agent | Der Abstand ist winzig, ein Workflow kostet Einrichtungszeit |
| Offene Arbeit, nicht skriptbar | Ein Agent | Sie kennen die Schritte erst mittendrin |
| Jeder Schritt braucht dasselbe große Dokument | Ein Agent | Ein Workflow schickt es bei jedem Schritt erneut |
| Wiederholte Aufgabe mit bekannter Form | Workflow | Volumen zahlt die Struktur schnell zurück |
| Alles, was seinen Weg nie improvisieren darf | Workflow | Die Zweige stehen fest, sie werden nicht zur Laufzeit gewählt |

Im offenen Fall kauft Autonomie echte Ergebnisse: Anthropic stellte fest, dass ein Team paralleler Agenten [einen einzelnen Agenten bei schweren Rechercheaufgaben um rund 90 % übertraf](https://www.anthropic.com/engineering/multi-agent-research-system), bei deutlich höherem Tokenverbrauch. Wenn die Antwort mehr zählt als die Rechnung, zahlen Sie das bewusst.

## Mit Caching schrumpft der Abstand

Hier das Zugeständnis, das die meisten "Workflows sind 10x billiger"-Pitches auslassen. Für den Schneeball des Wiederlesens gibt es ein Standardmittel, das Caching: Der Anbieter lässt das Modell bereits gesehenen Text stark vergünstigt erneut lesen.

Cachen Sie den Agenten sauber, sinken seine Kosten im Beispiel von rund 19 auf rund 9 Cent pro Ticket. Der Abstand zum Workflow fällt von etwa acht auf unter vier. Noch ein Abstand, aber ein viel kleinerer, und ein ehrlicher Vergleich muss den Agenten so bepreisen und nicht in seiner schlechtesten Variante ohne Cache.

Zwei Dinge leistet Caching nicht. Bei sehr kurzen Schritten hilft es wenig, weil es eine Mindestgröße gibt, unterhalb derer der Rabatt nicht greift. Und es verkürzt die Unterhaltung nicht, sondern nur den Preis fürs Wiederlesen, sodass ein außer Kontrolle geratener Agent sein Kontextfenster trotzdem füllen und den Faden verlieren kann.

## Was tatsächlich entscheidet

Ein Schritt zurück: Der Kostenunterschied ist real, sollte aber selten den Ausschlag geben.

Zwei andere Zahlen überwiegen meist. Erstens die Zuverlässigkeit: Wenn ein Ansatz öfter gelingt und jemand jeden Fehlschlag von Hand aufräumen muss, ist selbst ein kleiner Vorsprung in der Erfolgsquote mehr wert als ein paar Cent pro Ticket. Zweitens der Bauaufwand: Ein sauberer mehrstufiger Workflow braucht echte Arbeit im Bau und Unterhalt, während ein einzelner Agent mit ein paar Werkzeugen viel schneller steht. Bei tausenden Tickets am Tag zahlt sich der Workflow rasch aus. Bei ein paar Dutzend nie.

Die Reihenfolge der Fragen lautet also: Hat die Aufgabe eine bekannte Form, läuft sie in Menge, und welcher Ansatz scheitert seltener? Der Kostenfaktor zählt erst danach und bestätigt dann meist nur, was die ersten beiden schon gesagt haben.

## Häufige Fragen

### Ist ein Workflow immer billiger als ein Agent?

Nein. Bei einer Aufgabe mit zwei Schritten ist der Unterschied fast null, und wenn jeder Schritt dasselbe große Dokument braucht, kann der Workflow teurer sein, weil er es jedes Mal erneut schickt.

### Warum wird ein Agent im Verlauf teurer?

Weil er seine gesamte Unterhaltung in jeden neuen Schritt mitnimmt. Schritt acht bezahlt das Wiederlesen der Schritte eins bis sieben, also sind die späten Schritte die teuren.

### Macht Caching Agenten so billig wie Workflows?

Es halbiert den Abstand in unserem Beispiel, es schließt ihn nicht. Caching senkt den Preis fürs Wiederlesen, aber der Agent liest weiterhin weit mehr Text als jeder einzelne Workflow-Schritt.

### Wie rechne ich das für meine eigene Aufgabe?

Messen Sie drei Dinge, bevor Sie sich eine Zahl nennen: die echte Größe Ihrer Prompts und Daten, wie viele Schritte der Agent bei echter Arbeit tatsächlich braucht (Ihre Protokolle wissen das), und wie oft jeder Ansatz gelingt. Der Kostenabstand folgt daraus.

### Kann ich beides mischen?

Ja, und die meisten guten Systeme tun das. Legen Sie die Struktur als Workflow fest und lassen Sie einen kleinen Agenten den einen Schritt übernehmen, der wirklich Urteilsvermögen braucht.

## Für Neugierige

Die eine Zeile Mathematik hinter dem Schneeball: Das Gesamtlesen eines Agenten wächst ungefähr mit der Zahl der Schritte mal sich selbst, das eines Workflows in gerader Linie. Deshalb laufen beide umso weiter auseinander, je länger die Aufgabe dauert.

## Der nächste Schritt

Holen Sie sich die Schrittzahl einer echten Aufgabe aus Ihren Protokollen und lesen Sie die Tabelle oben mit dieser Zahl noch einmal. Welche Form Sie auch wählen, setzen Sie ihr zuerst eine Decke: [wie Sie begrenzen, was ein Agent ausgeben darf](/de/blog/cap-ai-agent-cost-budgets).
`;

export default content;
