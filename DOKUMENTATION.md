# Projektdokumentation – Bilaterale Strom-Verhandlung über einen Mediator

Technische Dokumentation des Projekts: Modell, Aufbau, Methoden, Parameter und Auswertung.
Quellcode in [src/](src/), Hintergrund und Ideen in [README.md](README.md).

---

## Inhalt
1. [Grundidee](#1-grundidee)
2. [Annahmen](#2-annahmen)
3. [Bestandteile der Verhandlung](#3-bestandteile-der-verhandlung)
4. [Methoden](#4-methoden)
   4.10. [Stufen des Projekts](#410-stufen-des-projekts)
5. [Ablauf einer Verhandlung](#5-ablauf-einer-verhandlung)
6. [Ergebnisse lesen und einordnen](#6-ergebnisse-lesen-und-einordnen)
7. [Parameter](#7-parameter)
8. [Modellgrenzen / Vereinfachungen](#8-modellgrenzen--vereinfachungen)
9. [Glossar](#9-glossar)
10. [Ausführen](#10-ausführen)

---

## 1. Grundidee

- **Use Case:** Peer-to-Peer-Stromhandel. Ein **Supplier** (PV-Überschuss) und ein **Customer**
  (Tagesbedarf) handeln für **einen Tag mit 24 Slots** je Slot eine **Liefermenge** und einen **Preis** aus.
- **Ziele (privat, gegensätzlich):**
  - Supplier: Preis maximieren, Überschuss loswerden.
  - Customer: Preis minimieren, Bedarf decken.
  - Beide: den **teureren Grid-Fallback vermeiden** (Netzbezug teuer, Einspeisung billig).
- **Kernidee:** Direkter Handel schlägt für **beide** das Netz → Win-Win. Genau dann,
  wenn der Preis im Band **Einspeisevergütung < Preis < Netzbezugspreis** liegt.
- **Bilateral** (genau 2 Parteien) und **Ziele bleiben privat** – der Mediator kennt sie nicht.
- **Aufbauend auf der Basis** (Klein-Verhandlung): Mechanik beibehalten, Domäne auf Strom umgebaut.

## 2. Annahmen

- **Zeit:** 1 Tag = 24 Slots (stündlich). Ohne Batterie sind die Slots **unabhängig**; mit Batterie **koppelt** der Speicher sie (Überschuss eines Slots deckt ein späteres Defizit).
- **Kontrakt:** je Slot Menge `x_t` [kWh] **und** Preis `p_t` [ct/kWh] → 48 verhandelte Werte.
- **Öffentliche Marktdaten** (Mediator + beide kennen sie): Einspeisevergütung `f_t`, Netzbezugspreis `r_t`.
- **Private Daten** (nur der jeweilige Agent): Erzeugung `g_t` (Supplier), Bedarf `d_t` (Customer).
- **Day-Ahead / Prognose-Unsicherheit (optional):** Beim Verhandeln kennen die Agenten ihre Profile nur als
  **Forecast** (`real · (1+N(0,σ))`, σ-Default 0,15); die **echten** Werte zählen erst beim **Settlement**.
  σ = 0 ⇒ Forecast = Real (kein Effekt).
- **Preisband dynamisch je Tagesphase** (Nacht/Morgen/Mittag/Abend), `f_t ≤ p_t ≤ r_t` (durch den
  Mediator erzwungen): nachts günstig & schmal, **mittags günstig** (PV-Schwemme am Markt), **abends
  teuer & breit** (`r_t` bis ~38 ct, `f_t` ~5–9 ct). Spotpreis-Charakter → die Batterie-Arbitrage
  (mittags speichern → abends nutzen) wird besonders wertvoll.
- **Keine Netzengpässe:** beliebige Mengen handelbar; Netz ist unbegrenzter Fallback.
- **Übermenge beim Customer** ist wertlos (Strafkosten `s = 0` als Default), sofern sie nicht in die Batterie passt.
- **Batterie (optional):** Supplier und Customer haben je einen **privaten** Speicher; `capacity = 0` schaltet ihn aus (= slot-unabhängiges Modell).
- **Daten synthetisch & generiert** (PV-Glocke, Last-Doppelpeak); **reproduzierbar** über zwei Seeds (Szenario + Verhandlung).
- **Kosten der Erzeugung** beim Supplier = 0 (PV); modelliert wird nur der Handel, nicht der Anlagenbetrieb.

## 3. Bestandteile der Verhandlung

| Komponente | Datei | Rolle |
|---|---|---|
| **Kontrakt** | [EnergyContract.java](src/EnergyContract.java) | Eine Tagesvereinbarung: Mengen + Preise (flacher `double[2·24]`). |
| **Szenario** | [ScenarioGenerator.java](src/ScenarioGenerator.java) | Erzeugt `g_t`, `d_t` (privat) und das **dynamische Preisband** `f_t`, `r_t` je Tagesphase (öffentlich). |
| **Agent (abstrakt)** | [Agent.java](src/Agent.java) | Schnittstelle: `utility`, `vote`, `delete`. |
| **Supplier** | [SupplierAgent.java](src/SupplierAgent.java) | Private Profit-Zielfunktion. |
| **Customer** | [CustomerAgent.java](src/CustomerAgent.java) | Private Kosten-Zielfunktion. |
| **Batterie** | [Battery.java](src/Battery.java) | Privater Speicher je Agent: greedy Lade-/Entlade-Dispatch mit konfigurierbarer Kapazität, Leistung und round-trip-Effizienz (koppelt Slots). |
| **Mediator** | [Mediator.java](src/Mediator.java) | **Uninformiert.** Erzeugt Vorschläge (GA-Operatoren) + gibt Annealing-Temperatur vor. |
| **Verhandlung (Stufe 2)** | [Verhandlung.java](src/Verhandlung.java) | Einzeltag-Verhandlung: Hauptschleife löschen → reproduzieren → abstimmen → archivieren. Vergleicht Szenarien ohne/mit Batterie. |
| **Mehrtagesverhandlung (Stufe 3)** | [MehrtagesVerhandlung.java](src/MehrtagesVerhandlung.java) | Mehrtägige Verhandlung (30 Tage) mit Lerneffekt: Agenten schätzen adaptiv ihre Prognosefehler σ̂. Vergleicht drei Strategien: Naiv, Learner, Oracle. |
| **Auswertung** | [Metrics.java](src/Metrics.java) | Kennzahlen & Baseline-/Optimum-Vergleich (nur zur Auswertung, nicht Teil der Verhandlung). |

Die **drei Hebel**, an denen man die Verhandlung verändert:
1. **Wie Vorschläge entstehen** → GA-Operatoren des Mediators (Mutation, Crossover, Init).
2. **Wie akzeptiert wird** → Abstimmung der Agenten + Annealing-Temperatur.
3. **Wie selektiert wird** → welche Kontrakte die Agenten löschen (`delete`).

---

## 4. Methoden

Das Verfahren kombiniert vier Bausteine:

### 4.1 Mediierte Verhandlung („Annealing Mediator", Klein et al.)
Ein **neutraler, uninformierter Mediator** schlägt vollständige Kontrakte vor; die Parteien
**stimmen nur mit Ja/Nein** ab (sie geben ihre Zielfunktion nicht preis). So bleiben die Ziele
privat – der Mediator weiß nicht, *warum* etwas abgelehnt wird. Referenz: M. Klein et al.,
*„Negotiating Complex Contracts"* (2003). Das ist die **Basis-Architektur**.

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

Zweck: das Problem der Basis (frühe Stagnation, weil nur strikte Verbesserungen akzeptiert werden)
entschärfen. `T = 0` ⇒ rein gieriges Verhalten wie in der Basis (als Vergleich nutzbar).

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
  im **Gesamtwohlstand** heraus. Daraus folgt:
  - **Effizienz** hängt nur an der **Menge**: optimal ist `x_t = min(g_t, d_t)` (so viel wie möglich direkt handeln).
  - **Fairness/Verteilung** hängt nur am **Preis**: *wo* `p_t` im Band `[f_t, r_t]` landet.
- Verwandte Konzepte: **Individuelle Rationalität** (jeder besser als Netz), **Pareto-Effizienz**,
  **soziale Wohlfahrt**, **Zone of Possible Agreement (ZOPA)**.

### 4.7 Speicher/Batterie (Erweiterung)
Jeder Agent kann einen **privaten Speicher** (`Battery`) haben, der die Slots koppelt: Überschuss wird
geladen und in einem späteren Defizit-Slot genutzt. **Kontrakt-Repräsentation und Mediator bleiben
unverändert** – nur die Zielfunktion wertet den Kontrakt jetzt über einen **greedy Dispatch** aus
(laden bei Überschuss, entladen bei Defizit; je Slot begrenzt durch `maxPower`, Verluste über den
Wirkungsgrad η). `capacity = 0` reproduziert exakt das speicherlose Modell. Effekt: der Supplier
schiebt Mittags-PV in den Abend-Peak, der Customer puffert Überlieferung → **geringere Netzabhängigkeit**
und höherer beidseitiger Nutzen.

### 4.8 Prognose-Unsicherheit & Imbalance Settlement (Erweiterung)
Day-Ahead schließen die Parteien Verträge auf **Schätzwerten** (Forecast) ab; die echte Lieferung weicht
durch Prognosefehler ab. Modell:
- **Verhandlung** auf dem Forecast (bei aktiver Strafe um σ **abgesichert** → konservativeres Bieten).
- **Settlement** mit den echten Profilen über die vorhandene `f_t`/`r_t`-Fallback-Mechanik (Stufe 1),
  plus **Imbalance-Strafe** `α` je kWh **Kontrakt-Fehlmenge** (unerwartete Liefer-Unterdeckung gegenüber
  dem Forecast, x-abhängig) (Stufe 2). σ = 0 bzw. α = 0 schalten den Effekt aus.
- Die **Batterie puffert Prognosefehler** und senkt damit die Imbalance-Strafe.

### 4.9 Mehrtägiges Lernen der Prognosegüte (adaptive σ-Schätzung)
Realistisch kennt ein Agent sein wahres σ nicht, sondern **lernt** es über mehrere Tage: nach jedem
Settlement beobachtet er seine relativen Prognosefehler und aktualisiert eine Schätzung σ̂ (laufende
Streuung), mit der er am nächsten Tag hedged. σ̂ startet bei 0 (naiv) und konvergiert gegen σ.
Treiber: [MehrtagesVerhandlung.java](src/MehrtagesVerhandlung.java). Drei Strategien auf derselben
Tagesfolge (gleiche Seeds, nur der Hedge unterscheidet sich): **Naiv** (σ̂ ≡ 0), **Learner** (σ̂ adaptiv),
**Oracle** (σ̂ ≡ σ).

Ergebnis (30 Tage, ohne Batterie, EUR; Welfare = Profit − Kosten):

| Strategie | Supplier-Profit | Customer-Kosten | Imbalance | Welfare |
|---|---|---|---|---|
| Naiv    | 111,98 | 738,69 | 211,50 | −626,7 |
| Learner |  99,39 | 718,27 | 190,73 | **−618,9** |
| Oracle  |  98,94 | 717,32 | 195,69 | −618,4 |

σ̂ konvergiert gegen σ (Ende: Supplier 0,142 / Customer 0,175 vs. wahr 0,15). Der **Learner erreicht das
Oracle-Niveau und schlägt den Naiven** – Lernen lohnt sich. (Plot über `results/learning_curve.csv`.)

**Ehrliche Einordnung:** Der Vorteil entsteht nur bei **punitiver** Imbalance-Strafe (hier α = 100 ct/kWh).
Bei kleinem α (z. B. 20) ist der optimale Hedge nahe 0 – das Hedging um σ kostet dann mehr Handelsvolumen
als es Imbalance spart, und Naiv ist am besten. Das ist konsistent mit der Realität: Imbalance-Preise sind
gerade deshalb stark punitiv, um genaue Prognosen/Fahrpläne zu erzwingen.

### 4.10 Stufen des Projekts

Das Projekt ist modular in **drei Ausbaustufen** strukturiert:

#### Stufe 1: Basismodell (Referenz)
- **Ziel:** Zeige, dass bilaterale Verhandlung beiden Agenten nutzt.
- **Modul:** [Verhandlung.java](src/Verhandlung.java) mit `startTemperature=0` oder CLI `java Verhandlung 0`.
- **Annahmen:** Slots sind unabhängig, keine Prognose-Unsicherheit (`forecastSigma=0`, `imbalancePrice=0`).
- **Ergebnis:** Tendenziell Win-Win; beide schlagen ihren Netz-Fallback. Keine Batterie, kein Zeitkopplungen.
- **Referenz:** Klein et al. (2003), Annealing Mediator mit GA.

#### Stufe 2: Mit Batterie + Prognose-Unsicherheit (Realismus)
- **Ziel:** Batterie-Mehrwert und Hedging-Effekt messbar machen.
- **Modul:** [Verhandlung.java](src/Verhandlung.java) Standard-Aufruf, `startTemperature > 0`, Batterie aktiv.
- **Annahmen:**
  - Prognose-Unsicherheit: `forecastSigma=0,15` (Agenten verhandeln auf Forecasts, Settlement auf Real).
  - Imbalance-Strafe: `imbalancePrice=20` ct/kWh (Strafen-Anreiz, konservativ zu bieten).
  - Batterie: Supplier 10 kWh/3 kW, Customer 5 kWh/3 kW, je η=0,95.
- **Mechanik:**
  - Batterie-Dispatch: greedy Lade-/Entlade-Arbitrage (mittags speichern → abends nutzen).
  - Settlement: Agenten erkennen ihre (ggü. Forecast unterschiedliche) realen Profile und zahlen Imbalance-Strafeu.
- **Ergebnis:** Speicher reduziert Netz-Abhängigkeit um ~30 %, beide Seiten profitieren.

#### Stufe 3: Mehrtägiges Lernen (adaptive σ-Schätzung)
- **Ziel:** Wie lernen Agenten ihre Prognosefehler über mehrere Tage?
- **Modul:** [MehrtagesVerhandlung.java](src/MehrtagesVerhandlung.java).
- **Szenario:** 30 aufeinanderfolgende Tage (gleiche Seed-Sequenz, tägliche Szenarien unterschiedlich).
- **Mechanik:**
  - Day-Ahead verhandeln beide auf ihrem Forecast mit Hedge σ̂ (gelernte Schätzung des Prognosefehlers).
  - Nach Settlement (mit echten Profilen + Imbalance `α=100` ct/kWh, höher als Stufe 2) beobachten beide ihre
    **relativen Prognosefehler** und updaten σ̂ (laufende Streuung der Abweichungen).
  - σ̂ startet bei 0 (naiv), konvergiert asymptotisch gegen das wahre σ.
- **Vergleich (auf derselben Tagesfolge):**
  - **Naiv** (`σ̂ ≡ 0`): hedged nicht → viele Imbalance-Strafen.
  - **Learner** (`σ̂` adaptiv): lernt aus Feedback → nähert sich dem Oracle an.
  - **Oracle** (`σ̂ ≡ σ` ab Tag 1): kennt σ exakt (obere Schranke).
- **Ergebnis:** Learner erreicht Oracle-Niveau und schlägt Naiv (bei hohem α), proof-of-concept für Lernfähigkeit.
- **Output:** `results/learning_curve.csv` + Python-Plot über 30-Tage-Verlauf von Wohlstand & σ̂.

| Aspekt | Stufe 1 | Stufe 2 | Stufe 3 |
|---|---|---|---|
| Batterie | nein | ja | nein (isoliert Lern-Effekt) |
| Prognose-Fehler | nein | ja | ja (Day-Ahead) |
| Imbalance-Strafe α | 0 | 20 ct/kWh | 100 ct/kWh |
| Zeithorizont | 1 Tag | 1 Tag | 30 Tage |
| Fokus | Mediation + GA | Speicher-Mehrwert | adaptives Lernen |
| Vergleich | Baseline (T=0) | Ohne vs. mit Batterie | Naiv vs. Learner vs. Oracle |

---

## 5. Ablauf einer Verhandlung

Aus [Verhandlung.java](src/Verhandlung.java), pro Runde (Default: 10 000 Runden):

1. **Temperatur bestimmen:** `T(round)` linear fallend von `startTemperature` auf 0 (bei 60 % der Runden).
2. **Löschen:** `deletedSize`-mal löscht ein zufällig gewählter Agent seinen für ihn schlechtesten Kontrakt.
3. **Reproduzieren:** Der Mediator füllt die frei gewordenen Plätze per Crossover (Rate 0,5) oder Mutation.
4. **Abstimmen:** Für jeden Kontrakt der Population: akzeptieren **beide** ihn gegenüber dem aktuellen
   Stand `current` (mit Annealing-Toleranz), wird er der neue `current`.
5. **Archivieren:** Ist dieser akzeptierte Kontrakt **Win-Win** (beide besser als ihre Netz-Baseline) und
   hat er die bisher **höchste soziale Wohlfahrt**, wird er als `bestEver` gemerkt.
6. Alle 500 Runden: Status-Log.

**Ergebnis = `bestEver`** (bzw. `current`, falls nie ein Win-Win-Kontrakt gefunden wurde).

`current` darf durch das Annealing auch mal „bergab" wandern, aber **`bestEver` verschlechtert
sich nie** (Best-so-far-Archiv). Das gemeldete Ergebnis ist daher immer beidseitig vorteilhaft
und möglichst effizient.

---

## 6. Ergebnisse lesen und einordnen

### 6.1 Die Kennzahlen im Report
Das Programm gibt **zwei Reports** aus (ohne/mit Batterie) plus einen Vergleich. Die Werte sind die
**realisierten** (Settlement-)Werte. Geldbeträge in **EUR**, Stückpreise in **ct/kWh**, Energie in **kWh**.
Beispiel mit Batterie (dank Seeds reproduzierbar):
```
Supplier-Profit :     6,82 EUR  (Netz   3,39,  ggü. Netz  +3,42 EUR)
Customer-Kosten :    16,09 EUR  (Netz  19,88,  ggü. Netz  +3,79 EUR)
Sozialwohlstand :    -9,27 EUR  (Netz -16,49, Optimum o. Speicher -13,22 -> 221,1%)
Matching-Rate   :   95,0% des möglichen Direkthandels (min(g,d) gedeckt)
Überlieferung   :    3,16 kWh (geliefert, nicht gebraucht/speicherbar)
Netz-Bezug Rest :   33,97 kWh    Netz-Einspeisung Rest:   26,09 kWh
Batterie SoC Ende: Supplier  0,00/10,0 kWh, Customer  0,33/5,0 kWh
Win-Win (beide besser als Netz)? Supplier=ja, Customer=ja
Erwartet->Real. : Profit   7,03->  6,82 EUR,  Kosten  14,43-> 16,09 EUR
Prognosefehler  : Supplier   7,30 kWh, Customer   6,30 kWh
Imbalance-Strafe: Supplier   0,02 EUR, Customer   0,71 EUR
```
- **Supplier-Profit / Customer-Kosten + EUR:** realisiertes Ergebnis und **Ersparnis gegenüber dem Netz**.
- **Sozialwohlstand + %:** wie nah das Ergebnis am Effizienz-Optimum liegt (mit Speicher >100 % möglich).
- **Matching-Rate:** wie viel des **möglichen** Direkthandels `min(g_t, d_t)` tatsächlich gedeckt ist.
- **Überlieferung:** kWh über dem Bedarf, die der Customer bezahlt, aber nicht braucht (Ineffizienz).
- **Netz-Bezug/-Einspeisung Rest:** verbleibende Grid-Abhängigkeit (Ziel: minimieren).
- **Win-Win:** Plausibilitäts-Check – muss für beide „ja" sein.
- **Erwartet→Real.:** was die Agenten beim Abschluss (Forecast) erwarteten vs. was nach dem Settlement
  herauskam – die Differenz ist der Effekt der Prognosefehler.
- **Prognosefehler / Imbalance-Strafe:** Volumen der Fehlprognose [kWh] und die dafür fällige Strafe [EUR].
  Ohne Batterie kippt der Supplier dadurch oft ins Minus; die Batterie puffert den Fehler ab.

### 6.2 Baselines und Optimum (die Bezugsgrößen)
Ohne diese drei Referenzwerte ist eine Zahl wie „5,70 EUR Profit" bedeutungslos:
- **Supplier-Baseline** = alles einspeisen: `Σ g_t·f_t`. (Was er *ohne* Deal bekäme.)
- **Customer-Baseline** = alles aus dem Netz: `Σ d_t·r_t`. (Was er *ohne* Deal zahlen müsste.)
- **Optimum (ohne Speicher)** = Wohlstand bei `x_t = min(g_t, d_t)`: maximal möglicher Direkthandel.
  Der Preis ist hier egal (reiner Transfer), nur die Menge zählt.

Das Ergebnis ist gut, wenn beide Baselines geschlagen werden **und** der Wohlstand nahe ans Optimum kommt.

> **Warum darf die Auswertung beide Zielfunktionen sehen, obwohl sie privat sind?**
> Weil die *Verhandlung selbst* (Mediator + Abstimmung) nie auf fremde Zielinfos zugreift – nur die
> nachgelagerte **Auswertung** (`Metrics`) rechnet beide Seiten zusammen, um das Ergebnis zu bewerten.
> Die Privatheit im Mechanismus bleibt gewahrt.

### 6.3 Beispiel-Ergebnis interpretiert
- **Mengen** `x_t` folgen `min(g_t, d_t)`: nachts wenig (kein PV), mittags trifft PV-Überschuss auf
  geringen Bedarf → wenig Handel; in den Last-Peaks (morgens/abends) ist wenig PV da → der Customer
  muss Restbedarf aus dem Netz decken. Das **zeitliche Auseinanderfallen** von PV (mittags) und Last
  (abends) ist der Grund, warum ohne Speicher nicht 100 % direkt gehandelt werden können.
- **Preise** `p_t` liegen immer im Band – das ist **erzwungen** (Clamping), nicht „erkämpft". Verhandelt
  wird nur die **Lage** im Band (Verteilung des Gewinns).

### 6.4 Beobachtungen
- Win-Win wird über verschiedene Seeds hinweg erreicht; der Sozialwohlstand erreicht ~90–95 % des
  speicherlosen Optimums – mit Batterie übersteigt er diese Referenz.
- Bei nur zwei Agenten trägt die Vielfalt der GA-Population die Suche; der Annealing-Anteil (`T > 0`
  gegenüber `T = 0`) wirkt sich hier nur gering aus. Beide Varianten konvergieren deutlich vor Rundenende.
- Die Batterie senkt die Netzabhängigkeit deutlich (im Standardlauf ~−30 %) und verbessert beide Seiten.

---

## 7. Parameter
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
| `scenarioSeed` | 42 | Seed der Profil-Erzeugung (auch Arg `[2]`). |
| `negotiationSeed` | 1 | Seed des Verhandlungs-Zufalls: GA, Votes, Lösch-Münze (auch Arg `[1]`). |
| Batterie Supplier | 10 kWh, 3 kW, η 0,95 | `capacity`, `maxPower`, `roundTripEfficiency`; `capacity=0` = aus. |
| Batterie Customer | 5 kWh, 3 kW, η 0,95 | dito; eigener privater Speicher. |
| `forecastSigma` | 0,15 | relativer Prognosefehler (Day-Ahead); 0 = perfekte Prognose. Arg `[3]`. |
| `imbalancePrice` | 20 | α: Strafe je kWh Imbalance [ct/kWh]; 0 = aus. Arg `[4]`. |
| Mehrtage: `days`/`σ`/`α` | 30 / 0,15 / 100 | Lern-Treiber `MehrtagesVerhandlung` (CLI: `α days σ`); α dort punitiv. |

---

## 8. Modellgrenzen / Vereinfachungen
- **Preisband wird erzwungen**, nicht ausgehandelt (Clamping auf `[f_t, r_t]`); verhandelt wird nur die Lage im Band.
- **Greedy-Batterie-Dispatch:** lädt/entlädt sofort, nicht kostenoptimal (z. B. Speicher gezielt für die teuersten Slots aufsparen).
- **Optimum-Referenz ist speicherlos:** die %-Angabe bezieht sich auf das Optimum ohne Speicher; mit Batterie kann der Wohlstand >100 % erreichen.
- **`current` startet bei einem zufälligen Kontrakt**, nicht am Netz-Status-quo; nur das Archiv garantiert ein Win-Win-Ergebnis.
- **Matching-Rate** misst nur Unterdeckung, nicht Überlieferung (dafür gibt es die separate Zeile).
- **Konservatives Bieten** ist als fester Hedge modelliert (Forecast um σ abgesichert), nicht aus einer
  stochastischen Optimierung abgeleitet; die Imbalance-Strafe `α` ist kalibriert (Default 20), nicht aus
  realen Imbalance-Preisen übernommen.

## 9. Glossar

| Konzept | Code-Name | Bedeutung | Einheit |
|---|---|---|---|
| `x_t` | `delivered` / `amount(t)` | Liefermenge in Slot t | kWh |
| `p_t` | `price` / `price(t)` | Preis in Slot t | ct/kWh |
| `g_t` | `generation` | Erzeugung des Suppliers (privat) | kWh |
| `d_t` | `demand` | Bedarf des Customers (privat) | kWh |
| `f_t` | `feedInTariff` / `feedIn` | Einspeisevergütung (öffentlich, niedrig) | ct/kWh |
| `r_t` | `retailPrice` / `retail` / `gridBuyPrice` | Netzbezugspreis (öffentlich, hoch) | ct/kWh |
| `s` | `surplusPenalty` | Strafkosten je überschüssiger kWh (Default 0) | ct/kWh |
| `U_S` | `utility` (Supplier) | Profit (höher = besser); intern ct, Ausgabe EUR | ct / EUR |
| `C_C` | `cost` (Customer) | Kosten (niedriger = besser); `utility = −cost` | ct / EUR |
| `T` | `temperature` | Annealing-Temperatur | — |
| — | `capacity` / `maxPower` / `η` / SoC | Batterie: Kapazität / Lade-Entladeleistung / Wirkungsgrad / Ladestand | kWh / kWh / – / kWh |
| forecast | `generationForecast` / `demandForecast` | Schätzung von `g_t`/`d_t` beim Verhandeln | kWh |
| `σ` | `forecastSigma` | relativer Prognosefehler | – |
| `α` | `imbalancePrice` | Strafe je kWh Kontrakt-Fehlmenge (unerwartete Unterdeckung) | ct/kWh |
| `σ̂` | (Treiber-Zustand) | gelernte Schätzung des eigenen Prognosefehlers σ (Stufe 3) | – |

## 10. Ausführen

### 10.1 Kompilierung
```bash
# Alle Java-Dateien kompilieren (mit UTF-8-Encoding für Umlaute)
javac -encoding UTF-8 -d bin src/*.java
```

### 10.2 Hauptmodule

#### Verhandlung.java (Stufe 1–2)
Einzeltag-Verhandlung mit optionaler Batterie und Prognose-Unsicherheit.

**Standard-Lauf (Stufe 2 mit Batterie):**
```bash
java -cp bin Verhandlung
```
Erzeugt:
- Zwei Console-Reports (ohne/mit Batterie) + Vergleich
- `results/slots_data.csv` (Slot-Details)
- `results/summary_data.csv` (aggregierte Kennzahlen)

**Lauf mit angepasstem Annealing-Start (`startTemperature`):**
```bash
java -cp bin Verhandlung 0      # Stufe 1 (rein gierig, T=0, wie Basis-Vergleich)
java -cp bin Verhandlung 500    # höheres Annealing-Potenzial
```

**Alle Parameter per Kommandozeile überschreiben:**
```bash
java -cp bin Verhandlung <startTemperature> <negotiationSeed> <scenarioSeed> <forecastSigma> <imbalancePrice>
# Beispiel:
java -cp bin Verhandlung 250 1 42 0.15 20
```

#### MehrtagesVerhandlung.java (Stufe 3)
Mehrtägige Verhandlung (30 Tage) mit adaptivem Lernen der Prognosefehler-Schätzung σ̂.

**Standard-Lauf:**
```bash
java -cp bin MehrtagesVerhandlung
```
Laufs 3 Strategien (Naiv, Learner, Oracle) auf 30 Tagen mit `α=100`, `σ=0,15`:
- Console: Tägliche Ergebnisse und Lernverlauf
- `results/learning_curve.csv` (30 Zeilen: Tag, σ̂-Schätzungen, kumulative Wohlstände)

**Mit angepassten Parametern:**
```bash
java -cp bin MehrtagesVerhandlung <imbalancePrice> <days> <trueSigma>
# Beispiel: Learner mit kleinerem Imbalance-Preis:
java -cp bin MehrtagesVerhandlung 50 30 0.15
```

### 10.3 Visualisierung
Nach einem Verhandlungslauf (Stufe 1–2) oder Mehrtage-Lauf (Stufe 3):

```bash
python3 visualize.py
```

Erzeugt (in `results/`):
- `scenario_profile.png` – Tagesprofil (Erzeugung, Bedarf, Preisband)
- `contract_comparison.png` – Vertrag pro Slot (Mengen, Preise)
- `grid_dispatch.png` – Netz-Interaktion (Einspeisung, Bezug)
- `summary_bars.png` – Ergebnis-Balken (Gewinn, Kosten, Wohlstand)
- `welfare_breakdown.png` – Wohlstand im Vergleich (Baseline/Verhandelt/Optimum)
- `learning_curve.png` – (nur bei MehrtagesVerhandlung) 30-Tage-Verlauf von σ̂ und Wohlstand

### 10.4 Hinweise

- **Encoding:** Umlaut-„Mojibake" in der Windows-Konsole ist nur eine Anzeige-Sache (Codepage);
  die Quelldateien sind UTF-8. In IDE-Run-Konfigurationen ggf. `-Dfile.encoding=UTF-8` setzen.
- **Reproduzierbarkeit:** Mit gleichem `scenarioSeed` und `negotiationSeed` sind die Ergebnisse exakt
  reproduzierbar (= Nützlich für Vergleiche).
- **Performance:** Einzeltag (~10 000 Runden) dauert ~2 s; MehrtagesVerhandlung (30 × 4 000 Runden) ~30 s.

