// German translation of ai-agent-audit-trail (public register, 2026-07-24).
const content = `Ein KI-Agent, der in der Demo funktioniert, hat eines bewiesen: dass er einmal funktionieren kann. Der Produktivbetrieb stellt die härtere Frage. Wenn er etwas falsch macht, können Sie sagen, was passiert ist und warum?

Lautet die Antwort nein, betreiben Sie kein System, sondern hoffen auf eines. Diese Lücke schließt eine Aufzeichnung jedes Laufs, die jemand außerhalb Ihres Teams Monate später lesen kann.

## Kurz gefasst

- Ihr Monitoring-Dashboard ist kein Prüfprotokoll. Anderer Leser, andere Uhr, andere Regeln.
- Standard-Tracing für KI speichert weder Prompts noch Antworten. Das müssen Sie einschalten.
- Nie stichprobenartig protokollieren. Der Lauf, den Sie erklären müssen, liegt im Verworfenen.
- Protokollieren Sie jeden Werkzeugaufruf samt Ergebnis, den genommenen Zweig, die Kosten und wer freigegeben hat.
- Bei Freigaben: Halten Sie fest, was die Person tatsächlich gesehen hat, nicht nur dass sie zugestimmt hat.

## Ein Dashboard ist kein Prüfprotokoll

Sie sehen ähnlich aus und sind nicht dasselbe. Ein Dashboard liest sein Autor, Minuten später, mit frischem Vorfall im Kopf. Ein Protokoll liest ein gleichgültiger oder feindseliger Dritter, Monate später, der nicht nachfragen kann.

| | Monitoring-Dashboard | Prüfprotokoll |
|---|---|---|
| Wer liest es | Sie, Minuten später | Ein Dritter, Monate später |
| Stichproben | Üblich, oft 10 bis 20 % | Nie |
| Inhalt von Prompts und Antworten | Meist aus | An, solange aufbewahrt wird |
| Wenn ein Schreibvorgang fehlschlägt | Notieren und weiter | Der Vorgang sollte fehlschlagen |
| Reihenfolge | Zeitstempel | Eine von Ihnen vergebene Sequenznummer |
| Kann es sich später ändern | Ja, by Design | Nein, nur anfügen |
| Fehlerbild | Sie debuggen langsamer | Sie können die Frage nicht beantworten |

![Ein LiveContext-Workflow-Lauf in der Observability-Ansicht: der ausgeführte Graph mit einem grünen Haken an jeder Node, daneben ein Lauf-Inspektor mit Epoch, Start- und Endzeitstempeln sowie Status, Dauer und Kosten jeder Node.](/blog/ai-agent-audit-trail-run.png)

*Ein Lauf in der Observability-Ansicht: jeder Schritt, sein Status, seine Dauer, seine Kosten. Sehr nützlich, und trotzdem ein Dashboard und nicht die dauerhafte Aufzeichnung, die der Rest dieses Artikels beschreibt.*

## "Wir haben Tracing" heißt nicht "wir haben ein Protokoll"

Das ist der Befund, der die meisten Teams überrascht.

Die branchenüblichen Konventionen zum Tracing von KI-Aufrufen behandeln Prompts, Antworten, Werkzeugargumente und Werkzeugergebnisse als optional, und die Spezifikation stellt sich auf den Standpunkt, dass Werkzeuge sie standardmäßig nicht erfassen sollen. Ein frisch eingerichtetes Tracing liefert Ihnen also Modellname, Tokenzahlen, Latenz und einen Abschlussgrund: nichts von dem Material, das eine Entscheidung rekonstruiert.

Die Inhaltserfassung einzuschalten ist zudem fummeliger als ein einzelner Schalter, jedenfalls in einer verbreiteten Umsetzung, in der zusätzlich eine zweite, kaum dokumentierte Einstellung aktiviert sein muss. Prüfen Sie, was Ihre Installation wirklich speichert, statt es anzunehmen, und prüfen Sie es, indem Sie eine echte Aufzeichnung von vorne bis hinten lesen.

Die andere Hälfte desselben Problems sind Ratschläge aus fast jedem Observability-Leitfaden: bei Volumen stark stichproben und Inhalte vor dem Backend bereinigen. Beides ist für Monitoring vernünftig und für ein Prüfprotokoll fatal. Eine 10-Prozent-Stichprobe nützt nichts, wenn die zu verteidigende Entscheidung in den übrigen 90 % liegt.

## Was Sie je Lauf protokollieren

Eine Aufzeichnung pro Lauf. Das ist der Kopf, den man zuerst liest.

| Was zu erfassen ist | Warum es zählt |
|---|---|
| Eine Lauf-ID, vergeben beim Start | Alles andere hängt daran, und eine zu spät vergebene geht verloren |
| Wer oder was ihn gestartet hat, und wie | Eine Person, ein Zeitplan, ein Webhook: davon hängt die Verantwortung ab |
| Start- und Endzeit als zwei Zeitstempel | Eine Dauer lässt sich nicht mit einer externen Chronologie abgleichen |
| Welches Modell abgerechnet wurde und welches wirklich lief | Sie können abweichen, und nur eines zu notieren macht den Rest falsch |
| Die zum Zeitpunkt gültigen Preise | Damit die Kosten auch nach einer Preisänderung nachvollziehbar bleiben |
| Tokens ein, aus, aus dem Cache, und die Kosten | Ihre Rechnung und Ihr Frühwarnsignal |
| Der Status und warum er endete | Die Aussage, die Sie verteidigen müssen |
| Konfiguration und Richtlinienstand | Ob in diesem Moment eine Freigabe verlangt war |
| Welcher Softwarestand lief | Lag dieser Lauf vor der Korrektur |
| Ob eine Freigabe nötig war, und ihr Verweis | Leer muss "nicht nötig" heißen, nicht "unbekannt" |

Auf zwei Punkten sei bestanden. **Zwei Zeitstempel statt einer Dauer**, weil sich nur Zeitstempel mit fremden Aufzeichnungen abgleichen lassen. Und **die gültigen Preise**, weil Preise und Modellnamen sich unter Ihnen ändern, und Kosten, die Sie nicht reproduzieren können, sind Kosten, die Sie nicht verteidigen können.

Was Sie nicht speichern sollten: den vollständigen Systemprompt bei jedem Lauf. Bei zehntausend Läufen am Tag sind sechs Kilobyte Prompt rund 20 GB reine Dopplung pro Jahr. Speichern Sie jede Fassung einmal und verweisen Sie darauf.

## Was Sie je Schritt protokollieren

Eine Aufzeichnung je Modellrunde, Werkzeugaufruf, Entscheidung oder Freigabe. Sie sind etwa fünfundzwanzigmal so zahlreich wie Laufaufzeichnungen und tragen fast den gesamten Inhalt.

| Was zu erfassen ist | Warum es zählt |
|---|---|
| Die tatsächliche Reihenfolge, beim Schreiben vergeben | Zeitstempel sind gleich oder vertauschen sich. Ein Zähler nicht |
| Ob Schritte parallel liefen | Einen parallelen Stapel als Kausalkette zu lesen ist schlimmer als eine Lücke |
| Um welche Art Schritt es sich handelt | Modellrunde, Werkzeugaufruf, Entscheidung, Freigabe |
| Werkzeugname und Aufruf-ID | Verknüpft Anfrage und Ergebnis trotz Wiederholungen |
| Die Argumente und das Ergebnis | Der echte Inhalt, auf der Uhr, die Sie für Inhalte nutzen |
| Ein Fingerabdruck von beidem | Beweist, was gesendet wurde, lange nach dem Löschen |
| Die Größe des Inhalts | Sagt späteren Lesern, dass gekürzt wurde und um wie viel |
| Welcher Zweig genommen wurde | Macht den Lauf auf dem Papier nachvollziehbar |
| Warum ein Schritt nicht lief | Ein übersprungener und ein nie erreichter Zweig sind verschiedene Tatsachen |
| Fehlercode, getrennt von der Meldung | Codes sind abfragbar, Meldungen wiederholen die auslösende Eingabe |
| Ob eine Schwärzung stattfand | Sonst beweist eine sauber aussehende Aufzeichnung nichts |

Die Zeile mit dem Fingerabdruck ist der stille Star der Tabelle. Einen Hash des Ein- und Ausgangs zu behalten kostet ein paar Byte je Schritt und erlaubt, Beweise jahrelang zu halten, während der Inhalt nach Monaten gelöscht wird. Wenn jemand ein Dokument vorlegt und behauptet, Ihr Agent habe es gesehen, entscheidet der Hash.

Eine Einschränkung, damit es niemand falsch macht: Ein Hash von etwas Erratbarem, etwa einer Postleitzahl oder einem Geburtsdatum, lässt sich durch Ausprobieren umkehren. Solche Werte brauchen einen separat verwahrten Schlüssel.

## Die Freigabe verdient eine eigene Zeile

Wenn ein Mensch freigibt, protokollieren Sie das als eigenständige Aufzeichnung und nicht als Häkchen am Lauf.

Halten Sie fest, wer freigegeben hat, wann, über welchen Kanal, wie lange die Frist war und vor allem **was die freigebende Person tatsächlich gesehen hat**. Frieren Sie diesen Text im Moment der Pause ein und bewahren Sie ihn bei der Aufzeichnung. Ohne das bedeutet "ein Mensch hat freigegeben" nichts, weil niemand weiß, was er freigab.

Drei kleine Fallen an derselben Stelle. Ein leeres Freigabefeld muss "die geltende Richtlinie verlangte keine Freigabe" heißen, was voraussetzt, dass der Richtlinienstand auffindbar ist. Standardidentitäten wie "system" oder "api" dürfen niemals eine reale Person bezeichnen können. Und wenn Ihre Aufzeichnung eine Freigeberrolle zeigt, sorgen Sie dafür, dass diese Rolle wirklich geprüft wurde, oder schreiben Sie in der Aufzeichnung klar, dass sie es nicht wurde.

## Zwei Fehler, die ein Protokoll still ruinieren

**Es unverbindlich schreiben.** Wenn der Prüfschreibvorgang ohne Rückmeldung abgesetzt und ein Fehlschlag als unkritisch vermerkt wird, dünnt Ihr Protokoll genau dann aus, wenn das System unter Last steht, also bei den Vorfällen, die Sie erklären sollen. Die Abdeckung korreliert dann mit der Systemgesundheit, die denkbar schlechteste Eigenschaft. Schreiben Sie die Aufzeichnung in derselben Transaktion wie das, was sie festhält.

**Eine Dauer ohne Chronologie speichern.** Klingt nebensächlich, bis Sie Ihre Aufzeichnung mit den Zeitstempeln der Kundenmails abgleichen sollen und es nicht können.

## Häufige Fragen

### Protokolliert mein Modellanbieter das nicht ohnehin?

Er protokolliert seine Seite des Aufrufs, für seine Aufbewahrungsfrist, in seinem Format, und Sie können es nicht als Beweis abfragen. Verteidigen können Sie nur die Aufzeichnung, die Sie selbst führen.

### Wird es nicht teuer, alles zu protokollieren?

Das Skelett (IDs, Zeiten, Status, Zähler, Fingerabdrücke, Zweige) ist winzig, bei zehntausend Läufen am Tag in der Größenordnung einiger Dutzend Gigabyte im Jahr. Teuer ist der Inhalt, und genau deshalb läuft er auf einer kürzeren Uhr. Diese Trennung ist Thema von [wie lange aufbewahren](/de/blog/ai-agent-audit-log-retention).

### Und personenbezogene Daten in den Protokollen?

Gehen Sie davon aus, dass welche darin sind, vor allem in Fehlermeldungen, die regelmäßig die auslösende Eingabe wiederholen. Halten Sie Kennungen pseudonym, den Inhalt auf kurzer Uhr, und reduzieren Sie die langlebige Aufzeichnung auf Hashes und Codes.

### Woher weiß ich, ob mein Protokoll ausreicht?

Nehmen Sie einen Lauf vom letzten Monat und rekonstruieren Sie ihn allein aus den gespeicherten Aufzeichnungen. Wenn Sie irgendetwas erneut ausführen oder einen Kollegen fragen müssen, reicht es noch nicht.

## Der nächste Schritt

Nehmen Sie einen echten Lauf und versuchen Sie, ihn allein aus der Aufzeichnung zu erklären. Alles, was Sie dabei raten müssen, ist das nächste Feld. Entscheiden Sie dann, wie lange jeder Teil überleben muss: [wie lange ein KI-Agenten-Prüfprotokoll aufbewahren](/de/blog/ai-agent-audit-log-retention).
`;

export default content;
