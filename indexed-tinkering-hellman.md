# Konzept: Bilaterale Strom-Verhandlung über einen uninformierten Mediator

## Kontext

Masterprojekt „Intelligente Systeme". Die vorhandene Basis (`src/`) ist eine **Klein-style Mediator-Verhandlung**: Ein Mediator schlägt eine **Population** ganzer Kontrakte vor, beide Agenten stimmen per `vote()` ab, ein GA (Crossover/Mutation) reproduziert akzeptierte Lösungen, die für einen Agenten schlechtesten werden gelöscht (`delete()`). Aktuell modelliert sie ein **Scheduling-Problem** (Kontrakt = Permutation `int[]`, Bewertung über Kosten-/Delay-Matrix).

**Diese Domäne wird vollständig ersetzt** (nur die Verhandlungs-*mechanik* bleibt Basis), Ziel ist ein **realer, „verkaufbarer" Use Case**:

> Bilaterale Strom-Verhandlung für einen Tag à 24 Slots. **Supplier** will Preis maximieren und Überschuss loswerden, **Customer** will Preis minimieren und Bedarf decken, **beide** wollen den teureren Grid-Fallback vermeiden. Ziele bleiben **privat**, Verhandlung bleibt **bilateral**.

Verkaufsargument = **Peer-to-Peer-Energiehandel**: Supplier und Customer handeln direkt und schlagen damit beide das Netz (Win-Win), statt teuer aus dem Netz zu beziehen bzw. billig einzuspeisen.

Dieser Schritt liefert das **Konzept**; Implementierung folgt als nächster Schritt (siehe §7).

---

## 1. Domänenmodell

Tag = `T = 24` Slots `t ∈ {0..23}`. Pro Slot zwei verhandelte Größen → **Kontrakt = 2·T Werte**:

```
x_t  = gelieferte Energiemenge Supplier -> Customer in Slot t   [kWh],  x_t >= 0
p_t  = Preis dieser Energie in Slot t                           [ct/kWh]
```

**Öffentliche Marktdaten** (Mediator darf sie kennen):
```
f_t  = Einspeisevergütung (Grid feed-in)  -> niedrig, z.B. ~8 ct/kWh    Supplier-Fallback-Erlös
r_t  = Netzbezugspreis     (Grid retail)  -> hoch,   z.B. ~30 ct/kWh    Customer-Fallback-Kosten
```

**Private Daten** (NUR der jeweilige Agent, Mediator sieht sie NICHT):
```
g_t  = verfügbare Erzeugung/Überschuss des Suppliers   [kWh]   (z.B. PV-Glocke)
d_t  = Energiebedarf des Customers                      [kWh]   (z.B. Morgen-/Abendpeak)
```

**Preisband als ökonomischer Kern:** Ein Slot-Deal lohnt sich für beide nur, wenn `f_t < p_t < r_t`. Innerhalb dieses Bandes schlägt direkter Handel das Netz für beide Seiten — das ist die Zone of Agreement und der eigentliche „Wert", den die Verhandlung verteilt.

---

## 2. Private Zielfunktionen (das geheime „IP" der Agenten)

**Supplier — Gewinn maximieren:**
```
U_S = Σ_t [  x_t · p_t                      // Erlös aus Lieferung an Customer
           + max(g_t − x_t, 0) · f_t        // Rest-Überschuss ins Netz (Fallback)
           − max(x_t − g_t, 0) · r_t ]      // Übermenge teuer aus Netz zukaufen (Strafe)
```
- Höherer `p_t` ⇒ höher. Überschuss an Customer statt Netz lohnt, solange `p_t > f_t`. Will nicht mehr zusagen als `g_t` (sonst teurer Zukauf) ⇒ hält `x_t ≤ g_t`.
- **No-Trade-Baseline:** alles einspeisen ⇒ `Σ g_t·f_t`.

**Customer — Kosten minimieren** (`U_C = −C_C`):
```
C_C = Σ_t [  x_t · p_t                      // Zahlung an Supplier
           + max(d_t − x_t, 0) · r_t        // Defizit teuer aus Netz (Fallback)
           + max(x_t − d_t, 0) · s ]        // Übermenge unerwünscht (Straf-/Entsorgungskosten s, default 0)
```
- Niedrigerer `p_t` ⇒ besser. Will `x_t` an `d_t` heranführen (Defizit teuer), aber nicht darüber ⇒ hält `x_t ≤ d_t`.
- **No-Trade-Baseline:** alles aus Netz ⇒ `Σ d_t·r_t`.

**Warum eine Einigung existiert (ökonomischer Beweis fürs „Verkaufen"):**
- Pro Slot: Deal genau dann beidseitig besser als Netz, wenn `f_t < p_t < r_t` und `x_t ≈ min(g_t, d_t)`.
- Die `x_t·p_t`-Terme sind ein **reiner Transfer** zwischen beiden ⇒ heben sich im Gesamtwohlstand auf:
  - **Effizienz** hängt nur an der Menge: sozial-optimal ist `x_t* = min(g_t, d_t)` (so viel wie möglich direkt handeln).
  - **Fairness/Verteilung** hängt nur am Preis: wo `p_t` im Band `[f_t, r_t]` liegt.
- ⇒ Gute Verhandlung treibt `x` Richtung effizientes Matching und `p` zu einer fairen Aufteilung. Das ist die messbare Story.

---

## 3. Verhandlungsmechanismus (Klein-Population beibehalten, Operatoren neu)

Beibehalten aus der Basis:
- **Population** von `N` ganzen Tageskontrakten.
- Pro Runde: beide Agenten `delete()` den/die für sie schlechtesten Kontrakte; Mediator `contractReproduction()` füllt Lücken via GA; bester Kontrakt wird über **gegenseitiges Pareto-Voting** aktualisiert (beide müssen zustimmen — gute Idee der Basis, bleibt).
- **Mediator bleibt uninformiert**: kennt nur Slot-Anzahl + öffentliche Bänder `[f_t, r_t]`, NICHT `g_t/d_t/U`. ⇒ Ziele privat. (Genau wie Basis.)

Was sich ändert (Permutation → reellwertig):
- **Init** (`initContract`): pro Slot zufällig `x_t ∈ [0, X_max]`, `p_t ∈ [f_t, r_t]` (feasible statt Permutation).
- **Mutation** (`constructProposal`): Gauß-Störung pro Gen `gene += N(0, σ·range)`, dann **Clamping** an Grenzen (ersetzt Swap).
- **Crossover**: arithmetisches/BLX-α-Blending pro Gen `child = α·a + (1−α)·b` (ersetzt OX1).

**Anti-Stagnation = euer Haupt-Stellhebel am Mediator** (adressiert das dokumentierte Basis-Problem „frühe Stagnation, weil nur strikte Verbesserungen akzeptiert werden"):
- **Annealing-Akzeptanz:** `vote()` akzeptiert eine Verschlechterung `Δ` mit Wahrscheinlichkeit `exp(−Δ / T(round))`; der Mediator gibt den Temperatur-/Toleranzplan `T(round)` (fallend) vor. Früh mehr Exploration, spät strikt.
- Alternativ/ergänzend **Mindestakzeptanzrate**: Mediator schreibt vor, welchen Anteil Vorschläge ein Agent diese Runde mindestens akzeptieren muss; Agent akzeptiert seine relativ besten.
- Tunables (= Experimentierfläche der Arbeit): `T`-Startwert + Abkühlrate, Mutations-`σ`, Crossover-Mix, Populationsgröße `N`, Anzahl Deletes/Runde, Anzahl Vorschläge/Runde.

---

## 4. Synthetische Daten (reproduzierbar, parametrierbar)

Generator im Code (fester Seed), als realistische Tagesprofile:
- **Supplier `g_t`:** PV-Glockenkurve, Peak ~12–14 Uhr, nachts ~0.
- **Customer `d_t`:** Haushalts-Lastprofil mit Morgen- und Abendpeak.
- **Grid `f_t`, `r_t`:** `f_t` niedrig (~8 ct), `r_t` höher in Peak-Stunden (TOU-Tarif, z.B. 25–35 ct) → spannend, weil PV-Überschuss (mittags) und Last-Peak (abends) zeitlich auseinanderliegen.

So entsteht echtes Verhandlungs-Spannungsfeld (Matching über Slots, nicht trivial).

---

## 5. Metriken (für Analyse & „Verkauf")

Pro Lauf ausgeben/vergleichen gegen die **No-Trade-Baseline** (beide nur Netz):
- **€ gespart** für Supplier (`U_S` vs `Σ g_t·f_t`) und Customer (`C_C` vs `Σ d_t·r_t`) — die Verkaufszahl.
- **Sozialer Gesamtwohlstand** `U_S + U_C` vs theoretisches Optimum bei `x_t* = min(g_t,d_t)`.
- **Grid-Abhängigkeit:** Rest-kWh aus/ins Netz (niedriger = besser gehandelt).
- **Matching-Rate:** `Σ min(x_t, d_t, g_t) / Σ min(g_t, d_t)`.
- **Fairness:** Lage von `p_t` im Band `[f_t, r_t]` (wer bekommt wie viel vom Kuchen).
- **Konvergenz:** Runden bis Stabilisierung — explizit gegen die Basis-Stagnation gestellt.

---

## 6. Basis-Mapping: was bleibt, was wird ersetzt

| Datei | Aktion | Inhalt |
|---|---|---|
| `Agent.java` | **Interface anpassen** | `vote(EnergyContract, EnergyContract)`, `printUtility`, `getSlots()`, `delete(EnergyContract[])`. Mechanik-Rollen identisch. |
| `EnergyContract.java` | **neu** | Repräsentation: flacher `double[2*T]` (24 Mengen + 24 Preise) mit Gettern `amount(t)/price(t)`. Flach ⇒ GA-Operatoren bleiben generische Array-Schleifen. |
| `SupplierAgent.java` | **neu schreiben** | privat `g_t, f_t`; `evaluate = U_S`; `vote` (mit Annealing-Toleranz); `delete` (entfernt min-`U_S`). |
| `CustomerAgent.java` | **neu schreiben** | privat `d_t, r_t, s`; `evaluate = C_C`; `vote`; `delete`. |
| `Mediator.java` | **Operatoren ersetzen** | `initContract` (feasible random), `constructProposal` (Gauß+Clamp), `crossover` (BLX-α), `contractReproduction` (bleibt strukturell), Annealing-Schedule `T(round)`. Bleibt uninformiert. |
| `Verhandlung.java` | **main neu** | Szenario generieren, Verhandlungsschleife, Metriken + Baseline-Vergleich ausgeben. |
| `ScenarioGenerator.java` | **neu** | synthetische `g_t/d_t/f_t/r_t` (Seed). |
| `Metrics.java` | **neu (optional)** | Welfare/Grid/Matching/Fairness-Auswertung gebündelt. |
| `data/*.txt` | **ungenutzt** | bleiben liegen (Daten synthetisch). |

---

## 7. Implementierungsstruktur (nächster Schritt)

1. `EnergyContract` + `ScenarioGenerator` anlegen (Domänen-Fundament).
2. `Agent`-Interface auf `EnergyContract` umstellen.
3. `SupplierAgent` / `CustomerAgent` mit privaten Profilen + `evaluate` + `vote` + `delete`.
4. `Mediator`-Operatoren (init/mutate/crossover) reellwertig + Annealing.
5. `Verhandlung.main`: Szenario → Schleife → Metriken/Baseline.
6. `Metrics` + Konsolen-Report (inkl. Konvergenzverlauf alle ~100 Runden, wie Basis).

## 8. Scope, Annahmen, Erweiterungen

**v1 (jetzt):** 1 Supplier + 1 Customer (bilateral), deterministische Tagesprofile, **kein Speicher/Batterie**, Mengen frei ohne Netz-Engpass, `s=0` (Übermenge wertlos). Preisband `[f_t,r_t]` öffentlich, Profile privat.

**Spätere Erweiterungen (bewusst ausgeklammert):** Batterie/Speicher (Slot-Kopplung), Unsicherheit/Prognosefehler in `g_t/d_t`, multilaterale Verhandlung (mehrere Agenten), Ausgleichszahlungen, echte Last-/PV-Profile aus Dateien.

## 9. Verifikation

- **Kompilieren/Laufen:** `javac -d bin src/*.java; java -cp bin Verhandlung` (JDK 21 vorhanden; Eclipse-`.classpath` 1.8 ist unkritisch, Language-Server mappt auf 21).
- **Sanity-Checks (müssen gelten, sonst Modellfehler):**
  - Ausgehandeltes Ergebnis ist für **beide** besser als No-Trade-Baseline (`U_S`↑, `C_C`↓).
  - `x_t` konvergiert Richtung `min(g_t, d_t)`; `p_t` bleibt im Band `[f_t, r_t]`.
  - Sozialwohlstand nähert sich dem Optimum bei `x_t*=min(g_t,d_t)`.
  - Mit Annealing **weniger/spätere Stagnation** als ohne (Vergleichslauf `T=0` vs `T>0`).
- **Demo-Output:** Konvergenzkurve + Endkontrakt + €-Ersparnis je Agent + Grid-Abhängigkeit.
