"""
Visualisierungen für die bilaterale Strom-Verhandlung.

Erzeugt folgende Diagramme (gespeichert in results/):
  1. scenario_profile.png  – Tagesprofil: Erzeugung, Bedarf, Preisband
  2. contract_comparison.png – Vertrag je Slot: Menge & Preis, ohne vs. mit Batterie
  3. grid_dispatch.png      – Netz-Einspeisung / -Bezug je Slot (ohne vs. mit Batterie)
  4. summary_bars.png       – Ergebnis-Balken: Gewinn, Kosten, Wohlstand, Netz
  5. welfare_breakdown.png  – Wohlstand im Vergleich: Baseline / Verhandelt / Optimum

Verwendung:
  python3 visualize.py
  (Liest results/slots_data.csv und results/summary_data.csv)
"""

import csv
import os
import math
import sys

# ── minimal dependency: only stdlib + (optional) matplotlib ──────────────────
try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.patches as mpatches
    from matplotlib.gridspec import GridSpec
    HAS_MPL = True
except ImportError:
    HAS_MPL = False

RESULTS_DIR = os.path.join(os.path.dirname(__file__), "results")
SLOTS_CSV   = os.path.join(RESULTS_DIR, "slots_data.csv")
SUMMARY_CSV = os.path.join(RESULTS_DIR, "summary_data.csv")

# ── colour palette ────────────────────────────────────────────────────────────
C_GEN    = "#F4A261"   # orange  – Erzeugung
C_DEM    = "#2A9D8F"   # teal    – Bedarf
C_FEEIN  = "#ADB5BD"   # grey    – Einspeisevergütung
C_RETAIL = "#495057"   # dark grey – Netzbezugspreis
C_REF    = "#264653"   # dark blue  – ohne Batterie
C_BAT    = "#E76F51"   # red-orange – mit Batterie
C_BASE   = "#6C757D"   # grey    – Baseline
C_OPT    = "#2A9D8F"   # teal    – Optimum
C_GRID_EXP = "#E9C46A" # yellow  – Netz-Einspeisung
C_GRID_IMP = "#E76F51" # red     – Netz-Bezug

HOUR_LABELS = [f"{h}:00" for h in range(24)]
TICK_HOURS  = [0, 3, 6, 9, 12, 15, 18, 21, 23]


# ─────────────────────────────────────────────────────────────────────────────
# Data loading
# ─────────────────────────────────────────────────────────────────────────────

def load_slots(path: str) -> dict:
    """Returns dict of column_name -> list[float]."""
    data: dict = {}
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            for k, v in row.items():
                data.setdefault(k, []).append(float(v))
    return data


def load_summary(path: str) -> list[dict]:
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append({k: (float(v) if k != "run" else v) for k, v in row.items()})
    return rows


# ─────────────────────────────────────────────────────────────────────────────
# Helper
# ─────────────────────────────────────────────────────────────────────────────

def save(fig, name: str):
    path = os.path.join(RESULTS_DIR, name)
    fig.savefig(path, dpi=150, bbox_inches="tight")
    plt.close(fig)
    print(f"  Gespeichert: {path}")


def xticks(ax, slots):
    idx = [t for t in TICK_HOURS if t < slots]
    ax.set_xticks(idx)
    ax.set_xticklabels([HOUR_LABELS[t] for t in idx], rotation=30, ha="right", fontsize=8)


def hline(ax, y, **kw):
    ax.axhline(y, **kw)


# ─────────────────────────────────────────────────────────────────────────────
# Figure 1 – Szenario-Tagesprofil
# ─────────────────────────────────────────────────────────────────────────────

def fig_scenario_profile(slots: dict):
    T = len(slots["slot"])
    hours = list(range(T))

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(11, 7), sharex=True)
    fig.suptitle("Szenario-Tagesprofil (24 Slots)", fontsize=13, fontweight="bold")

    # Panel 1: Energie-Profile
    ax1.fill_between(hours, slots["generation"], alpha=0.35, color=C_GEN, label="Erzeugung g_t (Supplier)")
    ax1.fill_between(hours, slots["demand"],     alpha=0.35, color=C_DEM, label="Bedarf d_t (Customer)")
    ax1.plot(hours, slots["generation"], color=C_GEN, linewidth=2)
    ax1.plot(hours, slots["demand"],     color=C_DEM, linewidth=2)

    # min-area (potential direct trade)
    pot = [min(g, d) for g, d in zip(slots["generation"], slots["demand"])]
    ax1.fill_between(hours, pot, alpha=0.55, color="#8338EC",
                     label="Direkthandel-Potenzial min(g,d)")
    ax1.plot(hours, pot, color="#8338EC", linewidth=1.5, linestyle="--")

    ax1.set_ylabel("Energie [kWh]", fontsize=9)
    ax1.legend(fontsize=8, loc="upper left")
    ax1.set_title("Energie-Profile", fontsize=10)
    ax1.grid(axis="y", linestyle=":", alpha=0.5)

    # Panel 2: Preisband
    ax2.fill_between(hours, slots["feedIn"], slots["retail"],
                     alpha=0.2, color="#ADB5BD", label="Einigungszone (f_t … r_t)")
    ax2.plot(hours, slots["feedIn"],  color=C_FEEIN,  linewidth=2, label="Einspeisevergütung f_t")
    ax2.plot(hours, slots["retail"],  color=C_RETAIL, linewidth=2, label="Netzbezugspreis r_t")
    ax2.set_ylabel("Preis [ct/kWh]", fontsize=9)
    ax2.set_xlabel("Tageszeit", fontsize=9)
    ax2.legend(fontsize=8, loc="upper right")
    ax2.set_title("Dynamisches Preisband", fontsize=10)
    ax2.grid(axis="y", linestyle=":", alpha=0.5)

    # Phase shading
    phases = [(0,6,"Nacht"), (6,10,"Morgen"), (10,16,"Mittag"), (16,22,"Abend"), (22,24,"Spätnacht")]
    colors  = ["#e8f4f8","#fff3cd","#d4edda","#f8d7da","#e8f4f8"]
    for (s, e, label), col in zip(phases, colors):
        for ax in (ax1, ax2):
            ax.axvspan(s, min(e, T-1), alpha=0.15, color=col, zorder=0)
        # label only on ax2
        ax2.text((s + min(e, T-1)) / 2, ax2.get_ylim()[0] if ax2.get_ylim()[0] > 0 else 0,
                 label, ha="center", va="bottom", fontsize=7, color="#555")

    xticks(ax1, T)
    xticks(ax2, T)
    plt.tight_layout()
    return fig


# ─────────────────────────────────────────────────────────────────────────────
# Figure 2 – Vertrag je Slot: Menge & Preis, ohne vs. mit Batterie
# ─────────────────────────────────────────────────────────────────────────────

def fig_contract_comparison(slots: dict):
    T = len(slots["slot"])
    hours = list(range(T))

    fig, axes = plt.subplots(2, 1, figsize=(11, 7), sharex=True)
    fig.suptitle("Ausgehandelter Vertrag: ohne vs. mit Batterie", fontsize=13, fontweight="bold")

    # Panel 1: Mengen
    ax = axes[0]
    ax.step(hours, slots["ref_amount"], where="mid", color=C_REF, linewidth=2,
            label="Liefermenge x_t – ohne Batterie")
    ax.step(hours, slots["bat_amount"], where="mid", color=C_BAT, linewidth=2,
            linestyle="--", label="Liefermenge x_t – mit Batterie")
    pot = [min(g, d) for g, d in zip(slots["generation"], slots["demand"])]
    ax.fill_between(hours, pot, alpha=0.12, color="#8338EC",
                    step="mid", label="Potenzial min(g,d)")
    ax.set_ylabel("Menge [kWh]", fontsize=9)
    ax.legend(fontsize=8)
    ax.set_title("Vereinbarte Liefermenge je Slot", fontsize=10)
    ax.grid(axis="y", linestyle=":", alpha=0.5)

    # Panel 2: Preise
    ax = axes[1]
    ax.fill_between(hours, slots["feedIn"], slots["retail"],
                    alpha=0.12, color="#ADB5BD", step="mid", label="Preisband f_t…r_t")
    ax.step(hours, slots["feedIn"],   where="mid", color=C_FEEIN,  linewidth=1.2, linestyle=":")
    ax.step(hours, slots["retail"],   where="mid", color=C_RETAIL, linewidth=1.2, linestyle=":")
    ax.step(hours, slots["ref_price"], where="mid", color=C_REF, linewidth=2,
            label="Preis p_t – ohne Batterie")
    ax.step(hours, slots["bat_price"], where="mid", color=C_BAT, linewidth=2,
            linestyle="--", label="Preis p_t – mit Batterie")
    ax.set_ylabel("Preis [ct/kWh]", fontsize=9)
    ax.set_xlabel("Tageszeit", fontsize=9)
    ax.legend(fontsize=8)
    ax.set_title("Vereinbarter Preis je Slot", fontsize=10)
    ax.grid(axis="y", linestyle=":", alpha=0.5)

    xticks(axes[0], T)
    xticks(axes[1], T)
    plt.tight_layout()
    return fig


# ─────────────────────────────────────────────────────────────────────────────
# Figure 3 – Netz-Dispatch je Slot
# ─────────────────────────────────────────────────────────────────────────────

def fig_grid_dispatch(slots: dict):
    T = len(slots["slot"])
    hours = list(range(T))

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(11, 7), sharex=True)
    fig.suptitle("Netz-Abhängigkeit je Slot (nach Batterie-Dispatch)", fontsize=13, fontweight="bold")

    for ax, suffix, title in [
        (ax1, "ref", "Ohne Batterie"),
        (ax2, "bat", "Mit Batterie"),
    ]:
        surplus = slots[f"{suffix}_supplier_surplus"]
        deficit = slots[f"{suffix}_customer_deficit"]

        ax.bar(hours, surplus, color=C_GRID_EXP, alpha=0.8, label="Netz-Einspeisung (Surplus Supplier)")
        ax.bar(hours, [-d for d in deficit], color=C_GRID_IMP, alpha=0.8,
               label="Netz-Bezug Customer (Defizit)")
        ax.axhline(0, color="black", linewidth=0.7)

        total_exp = sum(surplus)
        total_imp = sum(deficit)
        ax.set_title(f"{title}  |  Einspeisung: {total_exp:.1f} kWh, Bezug: {total_imp:.1f} kWh",
                     fontsize=10)
        ax.set_ylabel("kWh", fontsize=9)
        ax.legend(fontsize=8)
        ax.grid(axis="y", linestyle=":", alpha=0.5)

    ax2.set_xlabel("Tageszeit", fontsize=9)
    xticks(ax1, T)
    xticks(ax2, T)
    plt.tight_layout()
    return fig


# ─────────────────────────────────────────────────────────────────────────────
# Figure 4 – Ergebnis-Balken (Summary)
# ─────────────────────────────────────────────────────────────────────────────

def fig_summary_bars(summary: list[dict]):
    ref = next(r for r in summary if "ohne" in r["run"])
    bat = next(r for r in summary if "mit"  in r["run"])

    fig = plt.figure(figsize=(13, 8))
    fig.suptitle("Ergebnisvergleich: Verhandlung ohne vs. mit Batterie", fontsize=13, fontweight="bold")
    gs = GridSpec(2, 2, figure=fig, hspace=0.45, wspace=0.35)

    # ── 1. Supplier-Gewinn ──────────────────────────────────────────────────
    ax = fig.add_subplot(gs[0, 0])
    labels  = ["Baseline\n(nur Netz)", "Ohne\nBatterie", "Mit\nBatterie"]
    values  = [ref["supplier_baseline_eur"], ref["supplier_profit_eur"], bat["supplier_profit_eur"]]
    colors  = [C_BASE, C_REF, C_BAT]
    bars = ax.bar(labels, values, color=colors, alpha=0.85, edgecolor="white", linewidth=1.2)
    for bar, val in zip(bars, values):
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.05,
                f"{val:.2f} €", ha="center", va="bottom", fontsize=9, fontweight="bold")
    ax.set_title("Supplier-Gewinn", fontsize=11)
    ax.set_ylabel("EUR", fontsize=9)
    ax.grid(axis="y", linestyle=":", alpha=0.5)
    ax.set_ylim(0, max(values) * 1.25)

    # ── 2. Customer-Kosten ──────────────────────────────────────────────────
    ax = fig.add_subplot(gs[0, 1])
    values = [ref["customer_baseline_eur"], ref["customer_cost_eur"], bat["customer_cost_eur"]]
    bars = ax.bar(labels, values, color=colors, alpha=0.85, edgecolor="white", linewidth=1.2)
    for bar, val in zip(bars, values):
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.05,
                f"{val:.2f} €", ha="center", va="bottom", fontsize=9, fontweight="bold")
    ax.set_title("Customer-Kosten", fontsize=11)
    ax.set_ylabel("EUR", fontsize=9)
    ax.grid(axis="y", linestyle=":", alpha=0.5)
    ax.set_ylim(0, max(values) * 1.25)

    # ── 3. Sozialwohlstand ──────────────────────────────────────────────────
    ax = fig.add_subplot(gs[1, 0])
    w_labels = ["Baseline\n(nur Netz)", "Ohne\nBatterie", "Mit\nBatterie", "Optimum\n(kein Speicher)"]
    w_values = [
        ref["welfare_baseline_eur"],
        ref["welfare_eur"],
        bat["welfare_eur"],
        ref["welfare_optimal_eur"],
    ]
    w_colors = [C_BASE, C_REF, C_BAT, C_OPT]
    bars = ax.bar(w_labels, w_values, color=w_colors, alpha=0.85, edgecolor="white", linewidth=1.2)
    for bar, val in zip(bars, w_values):
        ypos = bar.get_height() + (0.05 if val >= 0 else -0.3)
        ax.text(bar.get_x() + bar.get_width()/2, ypos,
                f"{val:.2f} €", ha="center", va="bottom", fontsize=9, fontweight="bold")
    ax.axhline(0, color="black", linewidth=0.7)
    ax.set_title("Sozialwohlstand (Supplier − Customer)", fontsize=11)
    ax.set_ylabel("EUR", fontsize=9)
    ax.grid(axis="y", linestyle=":", alpha=0.5)

    # ── 4. Netz-Abhängigkeit & Matching-Rate ────────────────────────────────
    ax = fig.add_subplot(gs[1, 1])
    bar_labels = ["Ohne Batterie", "Mit Batterie"]
    grid_vals  = [ref["grid_kwh"], bat["grid_kwh"]]
    match_vals = [ref["match_rate_pct"], bat["match_rate_pct"]]

    x = [0, 1]
    width = 0.35
    b1 = ax.bar([xi - width/2 for xi in x], grid_vals, width=width,
                color=[C_REF, C_BAT], alpha=0.85, edgecolor="white", label="Netz-Abhängigkeit [kWh]")
    for bar, val in zip(b1, grid_vals):
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.3,
                f"{val:.1f}", ha="center", va="bottom", fontsize=9, fontweight="bold")

    ax2 = ax.twinx()
    b2 = ax2.bar([xi + width/2 for xi in x], match_vals, width=width,
                 color=["#90BE6D", "#43AA8B"], alpha=0.85, edgecolor="white",
                 label="Matching-Rate [%]")
    for bar, val in zip(b2, match_vals):
        ax2.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.3,
                 f"{val:.1f}%", ha="center", va="bottom", fontsize=9, fontweight="bold")

    ax.set_title("Netz-Abhängigkeit & Matching-Rate", fontsize=11)
    ax.set_ylabel("kWh", fontsize=9)
    ax2.set_ylabel("Matching-Rate [%]", fontsize=9)
    ax.set_xticks(x)
    ax.set_xticklabels(bar_labels, fontsize=9)
    ax.grid(axis="y", linestyle=":", alpha=0.4)

    lines1, lbls1 = ax.get_legend_handles_labels()
    lines2, lbls2 = ax2.get_legend_handles_labels()
    ax.legend(lines1 + lines2, lbls1 + lbls2, fontsize=8, loc="upper right")

    return fig


# ─────────────────────────────────────────────────────────────────────────────
# Figure 5 – Wohlstand-Aufschlüsselung (Waterfall-ähnlich)
# ─────────────────────────────────────────────────────────────────────────────

def fig_welfare_breakdown(summary: list[dict]):
    ref = next(r for r in summary if "ohne" in r["run"])
    bat = next(r for r in summary if "mit"  in r["run"])

    fig, ax = plt.subplots(figsize=(10, 6))
    fig.suptitle("Wohlstand-Analyse: Mehrwert der Verhandlung & Batterie", fontsize=13, fontweight="bold")

    base = ref["welfare_baseline_eur"]
    opt  = ref["welfare_optimal_eur"]
    ref_w = ref["welfare_eur"]
    bat_w = bat["welfare_eur"]

    categories = [
        "Baseline\n(nur Netz)",
        "Verhandlung\nohne Batterie",
        "Verhandlung\nmit Batterie",
        "Optimum\n(kein Speicher)",
    ]
    values = [base, ref_w, bat_w, opt]
    bar_colors = [C_BASE, C_REF, C_BAT, C_OPT]

    bars = ax.bar(categories, values, color=bar_colors, alpha=0.87, edgecolor="white",
                  linewidth=1.5, width=0.5)

    # value labels
    for bar, val in zip(bars, values):
        sign = "+" if val >= 0 else ""
        ax.text(bar.get_x() + bar.get_width()/2,
                bar.get_height() + (0.1 if val >= 0 else -0.4),
                f"{sign}{val:.2f} €", ha="center", va="bottom", fontsize=11, fontweight="bold")

    # delta arrows / annotations
    deltas = [
        (0, 1, ref_w - base,  "Verhandlungs-\nGewinn"),
        (1, 2, bat_w - ref_w, "Batterie-\nMehrwert"),
    ]
    for i, j, delta, label in deltas:
        x_mid = (i + j) / 2
        y_base = min(values[i], values[j])
        y_top  = max(values[i], values[j])
        col = "#2A9D8F" if delta >= 0 else "#E76F51"
        ax.annotate("", xy=(j, values[j]), xytext=(i, values[i]),
                    arrowprops=dict(arrowstyle="->", color=col, lw=1.8))
        ax.text(x_mid, (values[i] + values[j]) / 2 + 0.15,
                f"Δ {delta:+.2f} €\n{label}", ha="center", va="bottom",
                fontsize=9, color=col, fontweight="bold")

    ax.axhline(0, color="black", linewidth=0.8)
    ax.set_ylabel("Sozialwohlstand [EUR]", fontsize=10)
    ax.grid(axis="y", linestyle=":", alpha=0.5)
    ax.set_title(
        f"Baseline: {base:.2f} €  |  Ohne Bat.: {ref_w:.2f} €  |  "
        f"Mit Bat.: {bat_w:.2f} €  |  Optimum: {opt:.2f} €",
        fontsize=9, color="#555"
    )
    plt.tight_layout()
    return fig


# ─────────────────────────────────────────────────────────────────────────────
# Figure 6 – Ergebnis-Dashboard (einzelne Übersichtsseite)
# ─────────────────────────────────────────────────────────────────────────────

def fig_summary_dashboard(slots: dict, summary: list[dict]):
    ref = next(r for r in summary if "ohne" in r["run"])
    bat = next(r for r in summary if "mit"  in r["run"])

    T     = len(slots["slot"])
    hours = list(range(T))

    fig = plt.figure(figsize=(16, 12))
    fig.patch.set_facecolor("#F7F9FB")
    fig.suptitle(
        "Ergebnis-Dashboard: Bilaterale Strom-Verhandlung mit & ohne Batterie",
        fontsize=15, fontweight="bold", y=0.98
    )

    gs = GridSpec(3, 4, figure=fig, hspace=0.55, wspace=0.42,
                  left=0.07, right=0.97, top=0.93, bottom=0.07)

    # ── ROW 0: KPI tiles (4 metrics) ────────────────────────────────────────
    kpis = [
        ("Supplier-Gewinn",   ref["supplier_profit_eur"], bat["supplier_profit_eur"], "€",  True),
        ("Customer-Kosten",   ref["customer_cost_eur"],   bat["customer_cost_eur"],   "€",  False),
        ("Sozialwohlstand",   ref["welfare_eur"],         bat["welfare_eur"],         "€",  True),
        ("Netz-Abhängigkeit", ref["grid_kwh"],            bat["grid_kwh"],            "kWh", False),
    ]

    tile_bg   = "#FFFFFF"
    ref_color = C_REF
    bat_color = C_BAT

    for col, (label, val_ref, val_bat, unit, higher_better) in enumerate(kpis):
        ax = fig.add_subplot(gs[0, col])
        ax.set_facecolor(tile_bg)
        for spine in ax.spines.values():
            spine.set_edgecolor("#DDE3EA")

        delta     = val_bat - val_ref
        pct       = (delta / abs(val_ref) * 100) if val_ref != 0 else 0
        improved  = (delta > 0) == higher_better
        arrow_col = "#2A9D8F" if improved else "#E76F51"
        arrow_sym = "▲" if delta > 0 else "▼"

        ax.text(0.5, 0.85, label, ha="center", va="center", transform=ax.transAxes,
                fontsize=9, color="#555", fontweight="bold")

        # ref / bat values
        ax.text(0.25, 0.52, f"{val_ref:.2f} {unit}", ha="center", va="center",
                transform=ax.transAxes, fontsize=11, color=ref_color, fontweight="bold")
        ax.text(0.75, 0.52, f"{val_bat:.2f} {unit}", ha="center", va="center",
                transform=ax.transAxes, fontsize=11, color=bat_color, fontweight="bold")

        ax.text(0.25, 0.28, "ohne Bat.", ha="center", va="center",
                transform=ax.transAxes, fontsize=7.5, color="#888")
        ax.text(0.75, 0.28, "mit Bat.",  ha="center", va="center",
                transform=ax.transAxes, fontsize=7.5, color="#888")

        # delta badge
        ax.text(0.5, 0.10, f"{arrow_sym} {pct:+.1f}%", ha="center", va="center",
                transform=ax.transAxes, fontsize=10, color=arrow_col, fontweight="bold")

        ax.set_xlim(0, 1); ax.set_ylim(0, 1)
        ax.set_xticks([]); ax.set_yticks([])

    # ── ROW 1 LEFT (span 2): Energie-Tagesprofil ─────────────────────────────
    ax = fig.add_subplot(gs[1, :2])
    ax.set_facecolor(tile_bg)
    ax.fill_between(hours, slots["generation"], alpha=0.30, color=C_GEN)
    ax.fill_between(hours, slots["demand"],     alpha=0.30, color=C_DEM)
    ax.plot(hours, slots["generation"], color=C_GEN, linewidth=2,  label="Erzeugung g_t")
    ax.plot(hours, slots["demand"],     color=C_DEM, linewidth=2,  label="Bedarf d_t")
    pot = [min(g, d) for g, d in zip(slots["generation"], slots["demand"])]
    ax.fill_between(hours, pot, alpha=0.45, color="#8338EC",
                    label="Direkthandel min(g,d)")
    ax.set_title("Energie-Tagesprofil (Supplier & Customer)", fontsize=10, fontweight="bold")
    ax.set_ylabel("kWh", fontsize=9)
    ax.set_xlabel("Tageszeit", fontsize=9)
    ax.legend(fontsize=8, loc="upper left")
    ax.grid(axis="y", linestyle=":", alpha=0.45)
    xticks(ax, T)

    # ── ROW 1 RIGHT (span 2): Verhandlungs-Preise & Mengen ──────────────────
    ax = fig.add_subplot(gs[1, 2:])
    ax.set_facecolor(tile_bg)
    ax.fill_between(hours, slots["feedIn"], slots["retail"],
                    alpha=0.10, color="#ADB5BD", step="mid")
    ax.step(hours, slots["feedIn"],    where="mid", color=C_FEEIN,  lw=1.2, ls=":", label="f_t")
    ax.step(hours, slots["retail"],    where="mid", color=C_RETAIL, lw=1.2, ls=":", label="r_t")
    ax.step(hours, slots["ref_price"], where="mid", color=C_REF, lw=2,   label="Preis ohne Bat.")
    ax.step(hours, slots["bat_price"], where="mid", color=C_BAT, lw=2, ls="--", label="Preis mit Bat.")
    ax.set_title("Ausgehandelter Preis je Slot (im Preisband)", fontsize=10, fontweight="bold")
    ax.set_ylabel("ct/kWh", fontsize=9)
    ax.set_xlabel("Tageszeit", fontsize=9)
    ax.legend(fontsize=8, loc="upper right", ncol=2)
    ax.grid(axis="y", linestyle=":", alpha=0.45)
    xticks(ax, T)

    # ── ROW 2 LEFT (span 2): Wohlstand-Balken ───────────────────────────────
    ax = fig.add_subplot(gs[2, :2])
    ax.set_facecolor(tile_bg)
    cats   = ["Baseline\n(nur Netz)", "Ohne\nBatterie", "Mit\nBatterie", "Optimum"]
    wvals  = [ref["welfare_baseline_eur"], ref["welfare_eur"],
              bat["welfare_eur"],          ref["welfare_optimal_eur"]]
    wcols  = [C_BASE, C_REF, C_BAT, C_OPT]
    bars   = ax.bar(cats, wvals, color=wcols, alpha=0.85, edgecolor="white", lw=1.2, width=0.5)
    for bar, val in zip(bars, wvals):
        offset = 0.12 if val >= 0 else -0.45
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + offset,
                f"{val:.2f} €", ha="center", va="bottom", fontsize=9, fontweight="bold")
    ax.axhline(0, color="black", lw=0.8)
    ax.set_title("Sozialwohlstand im Vergleich", fontsize=10, fontweight="bold")
    ax.set_ylabel("EUR", fontsize=9)
    ax.grid(axis="y", linestyle=":", alpha=0.45)

    # ── ROW 2 RIGHT LEFT (1 col): Netz-Dispatch ─────────────────────────────
    ax = fig.add_subplot(gs[2, 2])
    ax.set_facecolor(tile_bg)
    x      = [0, 1]
    width  = 0.4
    g_vals = [ref["grid_kwh"], bat["grid_kwh"]]
    ax.bar(x, g_vals, width=width, color=[C_REF, C_BAT], alpha=0.85, edgecolor="white")
    for xi, val in zip(x, g_vals):
        ax.text(xi, val + 0.8, f"{val:.1f} kWh", ha="center", va="bottom",
                fontsize=10, fontweight="bold")
    ax.set_title("Netz-Abhängigkeit\n(gesamt)", fontsize=10, fontweight="bold")
    ax.set_ylabel("kWh", fontsize=9)
    ax.set_xticks(x)
    ax.set_xticklabels(["Ohne Batterie", "Mit Batterie"], fontsize=9)
    ax.grid(axis="y", linestyle=":", alpha=0.45)
    reduction = (1 - bat["grid_kwh"] / ref["grid_kwh"]) * 100
    ax.text(0.5, 0.92, f"−{reduction:.0f}% Reduktion", ha="center", va="top",
            transform=ax.transAxes, fontsize=10, color="#2A9D8F", fontweight="bold")

    # ── ROW 2 RIGHT RIGHT (1 col): Matching-Rate ────────────────────────────
    ax = fig.add_subplot(gs[2, 3])
    ax.set_facecolor(tile_bg)
    m_vals = [ref["match_rate_pct"], bat["match_rate_pct"]]
    wedge_colors = [[C_REF, "#DDE3EA"], [C_BAT, "#DDE3EA"]]
    for i, (val, wc) in enumerate(zip(m_vals, wedge_colors)):
        inner_ax_pos = [0.08 + i * 0.5, 0.12, 0.42, 0.78]
        ins = ax.inset_axes(inner_ax_pos)
        ins.pie([val, 100 - val], colors=wc, startangle=90,
                wedgeprops=dict(width=0.35))
        ins.text(0, 0, f"{val:.1f}%", ha="center", va="center",
                 fontsize=10, fontweight="bold", color=wc[0])
    ax.text(0.25, 0.06, "ohne Bat.", ha="center", va="bottom",
            transform=ax.transAxes, fontsize=8, color=C_REF)
    ax.text(0.75, 0.06, "mit Bat.",  ha="center", va="bottom",
            transform=ax.transAxes, fontsize=8, color=C_BAT)
    ax.set_title("Matching-Rate\n(Vertragserfüllung)", fontsize=10, fontweight="bold")
    ax.set_xlim(0, 1); ax.set_ylim(0, 1)
    ax.set_xticks([]); ax.set_yticks([])
    for spine in ax.spines.values():
        spine.set_edgecolor("#DDE3EA")

    return fig


# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────

def main():
    if not HAS_MPL:
        print("matplotlib ist nicht installiert. Bitte installieren: pip install matplotlib")
        sys.exit(1)

    if not os.path.exists(SLOTS_CSV) or not os.path.exists(SUMMARY_CSV):
        print(f"CSV-Dateien nicht gefunden in {RESULTS_DIR}.")
        print("Bitte zuerst die Simulation ausführen: java -cp bin Verhandlung")
        sys.exit(1)

    slots   = load_slots(SLOTS_CSV)
    summary = load_summary(SUMMARY_CSV)

    print("Erzeuge Diagramme …")

    fig = fig_scenario_profile(slots)
    save(fig, "scenario_profile.png")

    fig = fig_contract_comparison(slots)
    save(fig, "contract_comparison.png")

    fig = fig_grid_dispatch(slots)
    save(fig, "grid_dispatch.png")

    fig = fig_summary_bars(summary)
    save(fig, "summary_bars.png")

    fig = fig_welfare_breakdown(summary)
    save(fig, "welfare_breakdown.png")

    fig = fig_summary_dashboard(slots, summary)
    save(fig, "summary_dashboard.png")

    print("\nAlle Diagramme gespeichert in:", RESULTS_DIR)


if __name__ == "__main__":
    main()
