# Generell
## Verhandlungen:
	- Zielkonflikte
	- Win-Win-Situationen mit privaten/geheimen Zielen
	- Einfache Optimierung: Mediator durch Ausprobieren neue Varianten vorschlagen
	- Andere Optimierungsansätze: zB Verschlechterungen akzeptieren um lokale Minima zu überwinden

## Schwarm:
	- Insgesamt "höhere" Zielerreichung
	- normal mit gemeinsamem Ziel
	- (dynamische) Regeln

## Reinforcement Learning:
	- Aktionen lernen 



	- Bilaterale Vehandlung durch eher verdichtete Gruppen? 
	- Was ist der Vertrag? Über was verhandeln wir? Zb 24 mal pro Tag, schedulen? Also die Agenten haben zufällig einen Tagesplan und der kollidiert mit dem anderen
	- Ziele? 
	- Modell wollen wir verkaufen, wo kann man in der Realität anwenden?


## Grundidee/ziele:
Vorschlag: INT für Preis und Strommenge

## Aktuell ein Tag mit 24 Slots
Agent	Ziel
Supplier	Preis maximieren, Überschuss loswerden
Customer	Preis minimieren, Bedarf decken
Beide	Grid-Fallback vermeiden (teurer)

## Ideen:
	- Speicherkosten?
	- Gridkosten (können variieren?)
	- Evtl. wechselnde customer und supplier
	- Mehrere Supplier/Customer-Gruppen gleichzeitig
	- Batterie als Zustandsvariable (Überschuss speichern statt verlieren)
	- Mehrere Tage hintereinander
	- Jeden Tag anderes Energieprofil (Bedarf und Produktion in den entsprechenden Slots)
	- Ziel ist Gridabhängigkeit zu minimieren


Annahmen
Use Cases
-> vereinfachen