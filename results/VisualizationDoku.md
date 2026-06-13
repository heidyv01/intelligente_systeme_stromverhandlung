# Ergebnisse – Bilaterale Strom-Verhandlung

Alle Dateien in diesem Ordner werden automatisch erzeugt. Manuelle Änderungen werden beim nächsten Lauf überschrieben.

**Neu erzeugen:**
```bash
# Im Projekt-Wurzelverzeichnis:
javac -d bin src/*.java && java -cp bin Verhandlung   # Simulation + CSV-Export
python3 visualize.py                                   # Diagramme aus den CSVs
```

---

## CSV-Rohdaten

### `slots_data.csv`
Werte je Slot (Stunde 0–23) für **beide Verhandlungsläufe** (ohne / mit Batterie).

| Spalte | Einheit | Bedeutung |
|--------|---------|-----------|
| `slot` | – | Slot-Index 0–23 (entspricht Stunden) |
| `generation` | kWh | PV-Erzeugung des Suppliers (echter Wert, Settlement) |
| `demand` | kWh | Bedarf des Customers (echter Wert, Settlement) |
| `feedIn` | ct/kWh | Einspeisevergütung `f_t` (öffentlich, Untergrenze Preisband) |
| `retail` | ct/kWh | Netzbezugspreis `r_t` (öffentlich, Obergrenze Preisband) |
| `ref_amount` | kWh | Vereinbarte Liefermenge `x_t` – **ohne Batterie** |
| `ref_price` | ct/kWh | Vereinbarter Preis `p_t` – **ohne Batterie** |
| `ref_supplier_surplus` | kWh | Nicht gespeicherter Überschuss Supplier – ohne Batterie |
| `ref_customer_deficit` | kWh | Ungedeckter Bedarf Customer – ohne Batterie |
| `bat_amount` | kWh | Vereinbarte Liefermenge `x_t` – **mit Batterie** |
| `bat_price` | ct/kWh | Vereinbarter Preis `p_t` – **mit Batterie** |
| `bat_supplier_surplus` | kWh | Nicht gespeicherter Überschuss Supplier – mit Batterie |
| `bat_customer_deficit` | kWh | Ungedeckter Bedarf Customer – mit Batterie |

### `summary_data.csv`
Eine Zeile je Lauf (ohne / mit Batterie) mit den aggregierten Kennzahlen.

| Spalte | Einheit | Bedeutung |
|--------|---------|-----------|
| `run` | – | Bezeichnung des Laufs |
| `supplier_profit_eur` | EUR | Realisierter Gewinn des Suppliers (Settlement) |
| `supplier_baseline_eur` | EUR | Gewinn bei reiner Netz-Einspeisung (Referenz ohne Handel) |
| `customer_cost_eur` | EUR | Realisierte Kosten des Customers (Settlement) |
| `customer_baseline_eur` | EUR | Kosten bei reinem Netzbezug (Referenz ohne Handel) |
| `welfare_eur` | EUR | Sozialwohlstand = Supplier-Gewinn − Customer-Kosten |
| `welfare_baseline_eur` | EUR | Wohlstand der Baseline (nur Netz) |
| `welfare_optimal_eur` | EUR | Theoretisches Optimum ohne Speicher (min(g,d) direkt gehandelt) |
| `grid_kwh` | kWh | Gesamte Netz-Interaktion nach Batterie-Dispatch |
| `match_rate_pct` | % | Anteil des genutzten Direkthandels-Potenzials min(g,d) |

---

## Diagramme

### `scenario_profile.png`
Überblick über das synthetische Tagesszenario.
- **Oben:** PV-Erzeugung `g_t`, Kundenbedarf `d_t` und das Direkthandels-Potenzial `min(g,d)` je Slot. Die zeitliche Verschiebung zwischen PV-Peak (Mittag) und Lastpeak (Abend) ist das zentrale Motiv des Szenarios – sie macht den Speicher wertvoll.
- **Unten:** Dynamisches Preisband `[f_t, r_t]` je Tagesphase. Abends ist das Band am breitesten (bis 38 ct/kWh Netzbezug), was Batterie-Arbitrage besonders attraktiv macht.

### `contract_comparison.png`
Der ausgehandelte Vertrag je Slot, verglichen zwischen beiden Läufen.
- **Oben:** Vereinbarte Liefermenge `x_t` (ohne vs. mit Batterie) im Vergleich zum Potenzial `min(g,d)`. Mit Batterie können auch Slots beliefert werden, in denen die Erzeugung zu niedrig wäre (gespeicherte Energie aus dem Mittagspeak).
- **Unten:** Vereinbarter Preis `p_t` innerhalb des Preisbandes `[f_t, r_t]`.

### `grid_dispatch.png`
Verbleibende Netz-Interaktion nach Batterie-Dispatch, aufgeteilt nach Slot.
- **Gelbe Balken:** Überschuss des Suppliers, der ins Netz eingespeist wird (nicht handelbar / nicht speicherbar).
- **Rote Balken (negativ):** Ungedeckter Bedarf des Customers, der aus dem Netz bezogen wird.
- Ein direkter Vorher/Nachher-Vergleich zeigt, wie die Batterie die Netz-Abhängigkeit reduziert.

### `summary_bars.png`
Vier-Panel-Übersicht der aggregierten Ergebnisse.
1. **Supplier-Gewinn** – Baseline vs. Verhandlung ohne Batterie vs. mit Batterie.
2. **Customer-Kosten** – gleiche Gegenüberstellung (niedrigere Kosten = besser).
3. **Sozialwohlstand** – Vergleich mit Baseline und theoretischem Optimum.
4. **Netz-Abhängigkeit & Matching-Rate** – wie viel kWh verbleiben im Netz, wie viel des Potenzials wird direkt gehandelt.

### `welfare_breakdown.png`
Wohlstand-Wasserfall: zeigt schrittweise, wie viel Mehrwert die Verhandlung und die Batterie jeweils beitragen.
- **Δ Verhandlungsgewinn:** Differenz Baseline → Ohne Batterie.
- **Δ Batterie-Mehrwert:** Differenz Ohne Batterie → Mit Batterie.
- Das theoretische Optimum (kein Speicher, direkter Match) dient als obere Schranke.
