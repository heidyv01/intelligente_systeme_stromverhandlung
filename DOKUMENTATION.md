# Projektdokumentation – Bilaterale Strom-Verhandlung über einen Mediator

Masterprojekt „Intelligente Systeme". Diese Datei erklärt **Grundidee, Annahmen, Aufbau,
Methoden, Ergebnis-Interpretation und nächste Schritte** – als Nachschlagewerk für uns und
als Grundlage, um das Thema dem Prof zu erläutern.

> Begleitdateien: [README.md](README.md) (Brainstorming/Ideen), Konzept-/Designdoc
> `indexed-tinkering-hellman.md`, Quellcode in [src/](src/).

---

## Inhalt
1. [Grundidee (Stichpunkte)](#1-grundidee-stichpunkte)
2. [Annahmen (Stichpunkte)](#2-annahmen-stichpunkte)
3. [Bestandteile der Verhandlung (Stichpunkte)](#3-bestandteile-der-verhandlung-stichpunkte)
4. [Die Methoden (für die Erläuterung beim Prof)](#4-die-methoden-für-die-erläuterung-beim-prof)
5. [Ablauf einer Verhandlung (Schritt für Schritt)](#5-ablauf-einer-verhandlung-schritt-für-schritt)
6. [Ergebnisse lesen und einordnen](#6-ergebnisse-lesen-und-einordnen)
7. [Parameter (Stellschrauben)](#7-parameter-stellschrauben)
8. [Bekannte Grenzen / offene Punkte](#8-bekannte-grenzen--offene-punkte)
9. [Wie wir weitermachen (Fahrplan)](#9-wie-wir-weitermachen-fahrplan)
10. [Glossar](#10-glossar)
11. [Ausführen](#11-ausführen)

---

## 1. Grundidee (Stichpunkte)

- **Use Case:** Peer-to-Peer-Stromhandel. Ein **Supplier** (PV-Überschuss) und ein **Customer**
  (Tagesbedarf) handeln für **einen Tag mit 24 Slots** je Slot eine **Liefermenge** und einen **Preis** aus.
- **Ziele (privat, gegensätzlich):**
  - Supplier: Preis maximieren, Überschuss loswerden.
  - Customer: Preis minimieren, Bedarf decken.
  - Beide: den **teureren Grid-Fallback vermeiden** (Netzbezug teuer, Einspeisung billig).
- **Verkaufsargument:** Direkter Handel schlägt für **beide** das Netz → Win-Win. Genau dann,
  wenn der Preis im Band **Einspeisevergütung < Preis < Netzbezugspreis** liegt.
- **Bilateral** (genau 2 Parteien) und **Ziele bleiben privat** – der Mediator kennt sie nicht.
- **Aufbauend auf der Basis** (Klein-Verhandlung): Mechanik beibehalten, Domäne komplett auf Strom umgebaut.

## 2. Annahmen (Stichpunkte)

- **Zeit:** 1 Tag = 24 Slots (stündlich). Slots sind **unabhängig** (kein Speicher, keine Kopplung).
- **Kontrakt:** je Slot Menge `x_t` [kWh] **und** Preis `p_t` [ct/kWh] → 48 verhandelte Werte.
- **Öffentliche Marktdaten** (Mediator + beide kennen sie): Einspeisevergütung `f_t`, Netzbezugspreis `r_t`.
- **Private Daten** (nur der jeweilige Agent): Erzeugung `g_t` (Supplier), Bedarf `d_t` (Customer).
- **Preisband fix vorgegeben:** `f_t ≤ p_t ≤ r_t` (durch den Mediator erzwungen, s. u.). `f_t` niedrig (~8 ct),
  `r_t` höher und in den Lastspitzen teurer (Time-of-Use, ~25–37 ct).
- **Keine Netzengpässe:** beliebige Mengen handelbar; Netz ist unbegrenzter Fallback.
- **Übermenge beim Customer** ist wertlos (Strafkosten `s = 0` als Default).
- **Daten synthetisch & generiert** (PV-Glocke, Last-Doppelpeak), Profil über einen Seed reproduzierbar.
- **Kosten der Erzeugung** beim Supplier = 0 (PV); modelliert wird nur der Handel, nicht der Anlagenbetrieb.

## 3. Bestandteile der Verhandlung (Stichpunkte)

| Komponente | Datei | Rolle |
|---|---|---|
| **Kontrakt** | [EnergyContract.java](src/EnergyContract.java) | Eine Tagesvereinbarung: Mengen + Preise (flacher `double[2·24]`). |
| **Szenario** | [ScenarioGenerator.java](src/ScenarioGenerator.java) | Erzeugt `g_t`, `d_t` (privat) und `f_t`, `r_t` (öffentlich). |
| **Agent (abstrakt)** | [Agent.java](src/Agent.java) | Schnittstelle: `utility`, `vote`, `delete`. |
| **Supplier** | [SupplierAgent.java](src/SupplierAgent.java) | Private Profit-Zielfunktion. |
| **Customer** | [CustomerAgent.java](src/CustomerAgent.java) | Private Kosten-Zielfunktion. |
| **Mediator** | [Mediator.java](src/Mediator.java) | **Uninformiert.** Erzeugt Vorschläge (GA-Operatoren) + gibt Annealing-Temperatur vor. |
| **Verhandlung** | [Verhandlung.java](src/Verhandlung.java) | Hauptschleife: löschen → reproduzieren → abstimmen → archivieren. |
| **Auswertung** | [Metrics.java](src/Metrics.java) | Kennzahlen & Baseline-/Optimum-Vergleich (nur Analyse, „God-View"). |

Die **drei Hebel**, an denen man die Verhandlung verändert:
1. **Wie Vorschläge entstehen** → GA-Operatoren des Mediators (Mutation, Crossover, Init).
2. **Wie akzeptiert wird** → Abstimmung der Agenten + Annealing-Temperatur.
3. **Wie selektiert wird** → welche Kontrakte die Agenten löschen (`delete`).

---

## 4. Die Methoden (für die Erläuterung beim Prof)

Das Verfahren kombiniert vier bekannte Bausteine. Beim Prof kann man es so einordnen:

### 4.1 Mediierte Verhandlung („Annealing Mediator", Klein et al.)
Ein **neutraler, uninformierter Mediator** schlägt vollständige Kontrakte vor; die Parteien
**stimmen nur mit Ja/Nein** ab (sie geben ihre Zielfunktion nicht preis). So bleiben die Ziele
privat – der Mediator weiß nicht, *warum* etwas abgelehnt wird. Referenz: M. Klein et al.,
*„Negotiating Complex Contracts"* (2003). Das ist unsere **Basis-Architektur**.

### 4.2 Kontrakt-Repräsentation
Ein Kontrakt ist ein reellwertiger Vektor: 24 Mengen + 24 Preise. Bewusst „flach" gehalten,
damit die GA-Operatoren generisch darüber laufen, während `amount(t)`/`price(t)` die lesbare Sicht liefern.

### 4.3 Genetischer Algorithmus (Erzeugung der Vorschläge)
Der Mediator hält eine **Population** von Kontrakten und verbessert sie evolutionär:
- **Selektion:** Jeder Agent löscht den für *sich* schlechtesten Kontrakt (`delete`) → die Population
  driftet zu beidseitig akzeptablen Lösungen.
- **Rekombination (Crossover):** arithmetisches/BLX-Blending zweier Eltern je Gen
  (`child = a·Elter1 + (1−a)·Elter2`).
- **Mutation:** Gauß-Störung je Gen, anschließend **Clamping** auf gültige Grenzen
  (Menge in `[0, X_max]`, Preis in `[f_t, r_t]`).
- **Init:** zufällige, sofort **zulässige** Kontrakte.

> Gegenüber der Scheduling-Basis (Permutationen, OX1-Crossover, Swap-Mutation) sind die Operatoren
> auf **reellwertige Vektoren** umgestellt – das ist der Hauptunterschied im Code.

### 4.4 Abstimmung + Simulated Annealing (Akzeptanz)
Ein Agent akzeptiert einen Vorschlag relativ zum aktuellen Stand:
- **Verbesserung** (Δ ≥ 0) → immer Ja.
- **Verschlechterung** (Δ < 0) → Ja mit Wahrscheinlichkeit `exp(Δ / T)` (**Metropolis-Kriterium**).
- Die **Temperatur `T`** gibt der Mediator vor und kühlt sie über die Runden auf 0 ab
  (anfangs viel Exploration, am Ende strikt).

Zweck: das **dokumentierte Problem der Basis** (frühe Stagnation, weil nur strikte Verbesserungen
akzeptiert werden) entschärfen. `T = 0` ⇒ rein gieriges Verhalten wie in der Basis (als Vergleich nutzbar).

### 4.5 Private Zielfunktionen
Konvention: `utility` – **höher = besser** für beide (vereinheitlicht Abstimmung & Annealing).

**Supplier (Profit, maximieren):**
```
profit = Σ_t [ delivered·price
             + max(generation − delivered, 0)·feedInTariff   // Rest-Überschuss billig ins Netz
             − max(delivered − generation, 0)·gridBuyPrice ] // Übermenge teuer aus Netz zukaufen
```
**Customer (Kosten, minimieren; utility = −Kosten):**
```
cost   = Σ_t [ delivered·price
             + max(demand − delivered, 0)·retailPrice        // Defizit teuer aus Netz
             + max(delivered − demand, 0)·surplusPenalty ]   // unerwünschte Übermenge (Default 0)
```
Jeder Agent vergleicht `delivered` nur mit **seiner eigenen** privaten Größe (`generation` bzw.
`demand`) – deshalb bleibt das Ziel privat.

### 4.6 Warum eine Einigung existiert (Verhandlungszone)
- Pro Slot lohnt der Deal für **beide** genau dann, wenn `f_t < p_t < r_t` und `x_t ≈ min(g_t, d_t)`.
- Der Zahlungsterm `delivered·price` ist ein **reiner Transfer** zwischen den beiden – er hebt sich
  im **Gesamtwohlstand** heraus. Daraus folgt die zentrale Aussage:
  - **Effizienz** hängt nur an der **Menge**: optimal ist `x_t = min(g_t, d_t)` (so viel wie möglich direkt handeln).
  - **Fairness/Verteilung** hängt nur am **Preis**: *wo* `p_t` im Band `[f_t, r_t]` landet.
- Fachbegriffe fürs Gespräch: **Individuelle Rationalität** (jeder besser als Netz), **Pareto-Effizienz**,
  **soziale Wohlfahrt**, **Zone of Possible Agreement (ZOPA)**.

**Stichworte zum Nennen:** mediierte Multi-Attribut-Verhandlung (Klein) · genetischer Algorithmus
(Selektion/Crossover/Mutation) · Simulated Annealing (Metropolis, Kirkpatrick) · Mechanismus-Design
(Privatheit, IR, Pareto) · Peer-to-Peer-Energiemärkte.

---

## 5. Ablauf einer Verhandlung (Schritt für Schritt)

Aus [Verhandlung.java](src/Verhandlung.java), pro Runde (Default: 10 000 Runden):

1. **Temperatur bestimmen:** `T(round)` linear fallend von `startTemperature` auf 0 (bei 60 % der Runden).
2. **Löschen:** `deletedSize`-mal löscht ein **zufällig gewählter** Agent seinen für ihn schlechtesten Kontrakt.
3. **Reproduzieren:** Der Mediator füllt die frei gewordenen Plätze per Crossover (Rate 0,5) oder Mutation.
4. **Abstimmen:** Für jeden Kontrakt der Population: akzeptieren **beide** ihn gegenüber dem aktuellen
   Stand `current` (mit Annealing-Toleranz), wird er der neue `current`.
5. **Archivieren:** Ist dieser akzeptierte Kontrakt **Win-Win** (beide besser als ihre Netz-Baseline) und
   hat er die bisher **höchste soziale Wohlfahrt**, wird er als `bestEver` gemerkt.
6. Alle 500 Runden: Status-Log.

**Ergebnis = `bestEver`** (bzw. `current`, falls nie ein Win-Win-Kontrakt gefunden wurde).

Wichtig: `current` darf durch das Annealing auch mal „bergab" wandern, aber **`bestEver` verschlechtert
sich nie** (Best-so-far-Archiv aus dem Simulated Annealing). Das gemeldete Ergebnis ist daher immer
beidseitig vorteilhaft und möglichst effizient.

---

## 6. Ergebnisse lesen und einordnen

### 6.1 Die Kennzahlen im Report
Beispielausgabe (ein Lauf; Zahlen schwanken leicht, s. [§8](#8-bekannte-grenzen--offene-punkte)):
```
Supplier-Profit :      694,8 ct  (Baseline     483,2)  -> +   2,12 EUR
Customer-Kosten :     1953,1 ct  (Baseline    2059,5)  -> -   1,06 EUR
Sozialwohlstand :    -1258,4 ct  (Baseline   -1576,3, Optimum   -1226,5 -> 90,9%)
Matching-Rate   :  100,0% des möglichen Direkthandels (min(g,d) gedeckt)
Überlieferung   :    3,98 kWh (vom Customer bezahlt, aber ungenutzt)
Netz-Bezug Rest :   37,41 kWh    Netz-Einspeisung Rest:   40,32 kWh
Win-Win (beide besser als Netz)? Supplier=ja, Customer=ja
```
- **Supplier-Profit / Customer-Kosten + EUR:** absolutes Ergebnis und **Ersparnis gegenüber dem Netz** –
  die eigentliche „Verkaufszahl".
- **Sozialwohlstand + %:** wie nah das Ergebnis am theoretischen Effizienz-Optimum liegt (s. u.).
- **Matching-Rate:** wie viel des **möglichen** Direkthandels `min(g_t, d_t)` tatsächlich gedeckt ist.
- **Überlieferung:** kWh über dem Bedarf, die der Customer bezahlt, aber nicht braucht (Ineffizienz).
- **Netz-Bezug/-Einspeisung Rest:** verbleibende Grid-Abhängigkeit (Ziel: minimieren).
- **Win-Win:** Plausibilitäts-Check – muss für beide „ja" sein, sonst stimmt das Modell nicht.

### 6.2 Baselines und Optimum (die Bezugsgrößen)
Ohne diese drei Referenzwerte ist eine Zahl wie „694 ct Profit" bedeutungslos:
- **Supplier-Baseline** = alles einspeisen: `Σ g_t·f_t`. (Was er *ohne* Deal bekäme.)
- **Customer-Baseline** = alles aus dem Netz: `Σ d_t·r_t`. (Was er *ohne* Deal zahlen müsste.)
- **Optimum** = Wohlstand bei `x_t = min(g_t, d_t)`: maximal möglicher Direkthandel.
  Erinnerung: der Preis ist hier egal (reiner Transfer), nur die Menge zählt.

Das Ergebnis ist gut, wenn beide Baselines geschlagen werden **und** der Wohlstand nahe ans Optimum kommt.

> **Warum darf die Auswertung beide Zielfunktionen sehen, obwohl sie privat sind?**
> Weil die *Verhandlung selbst* (Mediator + Abstimmung) nie auf fremde Zielinfos zugreift – nur die
> **Analyse** (`Metrics`, „God-View") rechnet im Nachhinein beide Seiten zusammen, um zu *bewerten*.
> Privatheit im Mechanismus bleibt also gewahrt.

### 6.3 Beispiel-Ergebnis interpretiert
- **Mengen** `x_t` folgen `min(g_t, d_t)`: nachts wenig (kein PV), mittags PV-Überschuss trifft auf
  geringen Bedarf → wenig Handel; in den Last-Peaks (morgens/abends) ist wenig PV da → der Customer
  muss Restbedarf aus dem Netz decken. Das **zeitliche Auseinanderfallen** von PV (mittags) und Last
  (abends) ist genau der spannende Teil und der Grund, warum nicht 100 % direkt gehandelt werden können.
- **Preise** `p_t` liegen immer im Band – das ist **erzwungen** (Clamping), nicht „erkämpft". Verhandelt
  wird nur die **Lage** im Band (Verteilung des Gewinns).
- **~90–95 % des Optimums + Win-Win:** solides, plausibles Ergebnis für v1.

### 6.4 Empirische Befunde
- **Win-Win wird robust erreicht**, Wohlstand ~90–95 % des Optimums, Konvergenz deutlich vor Rundenende.
- **Annealing ehrlich eingeordnet:** Bei *nur 2 Agenten* + diverser GA-Population stagniert auch die
  **gierige** Variante (`T = 0`) kaum – die GA-Vielfalt trägt die Suche. Das Annealing ist hier **ein**
  Hebel von mehreren, kein Wundermittel. Sein Nutzen wächst, je stärker die *Akzeptanz* zum Flaschenhals
  wird (z. B. mehr Agenten, oder wenn der akzeptierte Konsens die Suche steuert statt einer separaten GA).
  → Das ist ein **gutes Diskussionsergebnis**, kein Mangel.

---

## 7. Parameter (Stellschrauben)
Alle in [Verhandlung.java](src/Verhandlung.java) oben gebündelt:

| Parameter | Default | Wirkung |
|---|---|---|
| `popSize` | 200 | Größe der Kontrakt-Population (Vielfalt vs. Rechenzeit). |
| `maxRounds` | 10 000 | Verhandlungsdauer. |
| `deletedSize` | 30 | Löschungen/Runde = Selektionsdruck. |
| `startTemperature` | 250 | Annealing-Start (`0` = gierig wie Basis). Auch per Arg: `java Verhandlung 0`. |
| `coolRounds` | 60 % von `maxRounds` | Wann die Temperatur 0 erreicht. |
| `mutationSigma` | 0,08 | Mutationsstärke (Anteil der Spanne). |
| `crossoverRate` | 0,5 | Anteil Crossover vs. Mutation bei Reproduktion. |
| `seed` | 42 | Seed der **Szenario- und GA-**Zufallsquelle. |

---

## 8. Bekannte Grenzen / offene Punkte
- **Reproduzierbarkeit unvollständig:** Die Wahl des löschenden Agenten und die Annealing-Akzeptanz
  nutzen das **ungeseedete** `Math.random()`. Nur Szenario und GA-Operatoren hängen am `seed`.
  ⇒ Läufe schwanken trotz gleichem Seed. **Fix (empfohlen, klein):** eine zentrale `Random`-Instanz
  mit Seed an alle Stellen durchreichen. **Nötig, bevor man Parameter sauber vergleicht.**
- **`current` startet beliebig** (zufälliger Kontrakt), nicht am Netz-Status quo – nur das Archiv
  garantiert Win-Win.
- **Preisband wird erzwungen**, nicht ausgehandelt – bewusste Vereinfachung, aber dokumentieren.
- **Slot-Unabhängigkeit:** ohne Speicher kann mittags zu viel PV nicht in den Abend-Peak verschoben werden.
- **Matching-Rate** misst nur Unterdeckung, nicht Überlieferung (dafür gibt es die separate Zeile).

## 9. Wie wir weitermachen (Fahrplan)

**Kurzfristig (Methodik sauber machen):**
1. **Reproduzierbarkeit fixen** (zentraler Seed) – Voraussetzung für alles Weitere.
2. **Experimente:** Parameter-Sweeps (`startTemperature`, `mutationSigma`, `crossoverRate`, `popSize`),
   je *n* Wiederholungen, Mittelwert + Streuung; **Annealing vs. gierig** sauber vergleichen.
3. **Stagnations-Metrik** definieren (z. B. „Runde, ab der sich `bestEver` nicht mehr verbessert") und plotten.
4. **CSV-Export + Plots** (Konvergenzkurve, Tagesprofile g/d/x) für den Bericht.

**Mechanik-Varianten (die Hebel ausspielen):**
5. **Akzeptanz-getriebene Variante:** Mediator mutiert nur den *aktuellen Konsens* statt einer separaten
   GA-Population → hier wird das Annealing zum Hauptmechanismus.
6. **Mindestakzeptanzrate** als alternativer Mediator-Hebel (Agent muss Anteil X der Vorschläge akzeptieren).

**Domänen-Erweiterungen (aus unserer [README](README.md)-Ideenliste):**
7. **Batterie/Speicher** als Zustandsvariable (koppelt Slots, verschiebt PV-Überschuss in den Abend) –
   großer Realismus-Gewinn, „Grid-Abhängigkeit minimieren".
8. **Variable/realistische Gridkosten**, mehrere Tage hintereinander, je Tag anderes Profil.
9. **Mehrere/​wechselnde** Supplier & Customer (multilateral) – verändert die Stagnationsdynamik
   spürbar (hier wird Annealing relevanter).
10. **Prognose-Unsicherheit** in `g_t`/`d_t` (Robustheit der Verträge).

## 10. Glossar

| Konzept | Code-Name | Bedeutung | Einheit |
|---|---|---|---|
| `x_t` | `delivered` / `amount(t)` | Liefermenge in Slot t | kWh |
| `p_t` | `price` / `price(t)` | Preis in Slot t | ct/kWh |
| `g_t` | `generation` | Erzeugung des Suppliers (privat) | kWh |
| `d_t` | `demand` | Bedarf des Customers (privat) | kWh |
| `f_t` | `feedInTariff` / `feedIn` | Einspeisevergütung (öffentlich, niedrig) | ct/kWh |
| `r_t` | `retailPrice` / `retail` / `gridBuyPrice` | Netzbezugspreis (öffentlich, hoch) | ct/kWh |
| `s` | `surplusPenalty` | Strafkosten je überschüssiger kWh (Default 0) | ct/kWh |
| `U_S` | `utility` (Supplier) | Profit (höher = besser) | ct |
| `C_C` | `cost` (Customer) | Kosten (niedriger = besser); `utility = −cost` | ct |
| `T` | `temperature` | Annealing-Temperatur | — |

## 11. Ausführen
```powershell

javac -encoding UTF-8 -d bin (Get-ChildItem src\*.java).FullName
java -cp bin Verhandlung        # Annealing (Default, startTemperature = 250)
java -cp bin Verhandlung 0      # rein gieriger Vergleichslauf (wie Basis)
```
Hinweis: Umlaut-„Mojibake" in der Windows-Konsole ist nur eine Anzeige-Sache (Codepage); die
Quelldateien sind UTF-8. In der Run-Konfig ggf. `-Dfile.encoding=UTF-8` setzen.
