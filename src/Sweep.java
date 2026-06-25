import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Sweep-/Statistik-Modus: Mittelwert ± Streuung über mehrere Seeds und zwei Parameter-Sweeps.
 *
 * Jede Stichprobe = ein eigenes Szenario UND eigener Verhandlungs-Zufall (scenarioSeed/negotiationSeed
 * = Basis + i). Alle Sweep-Punkte nutzen DIESELBEN Seeds (gepaart) -> fairer Vergleich.
 *
 * Ausgaben:
 *  1) Multi-Seed-Statistik (mit / ohne Batterie) – behebt die Single-Seed-Schwäche
 *  2) Batteriekapazitäts-Sweep  -> results/capacity_sweep.csv   (Sättigungskurve)
 *  3) Annealing-Temperatur-Sweep -> results/temperature_sweep.csv
 *
 * CLI (optional): args[0]=nSeeds, args[1]=roundsPerLauf.
 */
public class Sweep {

	private static final int    T               = 24;
	private static final double START_TEMP       = 250.0;
	private static final double FORECAST_SIGMA   = 0.15;
	private static final double IMBALANCE_PRICE  = 20.0;
	private static final long   SCENARIO_SEED0   = 42L;
	private static final long   NEGOTIATION_SEED0 = 1L;

	public static void main(String[] args) {
		int nSeeds = (args.length > 0) ? Integer.parseInt(args[0]) : 12;
		int rounds = (args.length > 1) ? Integer.parseInt(args[1]) : 5000;

		Battery sBat = new Battery(10.0, 3.0, 0.95, 0.0);
		Battery cBat = new Battery( 5.0, 3.0, 0.95, 0.0);

		System.out.printf(Locale.GERMAN, "Sweep: %d Seeds/Punkt, %d Runden, σ=%.2f, α=%.0f ct/kWh%n%n",
			nSeeds, rounds, FORECAST_SIGMA, IMBALANCE_PRICE);

		// ---- 1) Multi-Seed-Statistik ----
		System.out.println("===== 1) Multi-Seed-Statistik =====");
		runSeeds(Battery.none(), Battery.none(), START_TEMP, nSeeds, rounds).print("ohne Batterie");
		runSeeds(sBat, cBat, START_TEMP, nSeeds, rounds).print("mit Batterie ");

		// ---- 2) Batteriekapazitäts-Sweep (Supplier=cap, Customer=cap/2) ----
		double[] caps = { 0, 2, 4, 6, 8, 10, 14, 20 };
		StringBuilder capCsv = new StringBuilder(
			"capacity_kWh,welfare_mean_eur,welfare_std_eur,grid_mean_kwh,grid_std_kwh,winwin_rate\n");
		System.out.println("\n===== 2) Batteriekapazitäts-Sweep =====");
		System.out.println("cap[kWh] | Welfare(€) Mittel±Std | Grid(kWh) Mittel±Std | WinWin");
		for (double cap : caps) {
			Battery s = (cap > 0) ? new Battery(cap,       3.0, 0.95, 0.0) : Battery.none();
			Battery c = (cap > 0) ? new Battery(cap * 0.5, 3.0, 0.95, 0.0) : Battery.none();
			Stats r = runSeeds(s, c, START_TEMP, nSeeds, rounds);
			System.out.printf(Locale.GERMAN, "%6.0f   | %7.2f ± %4.2f       | %6.2f ± %4.2f      | %3.0f%%%n",
				cap, r.mean(r.sW)/100, r.std(r.sW, r.sW2)/100, r.mean(r.sG), r.std(r.sG, r.sG2), r.winwinRate()*100);
			capCsv.append(String.format(Locale.US, "%.1f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
				cap, r.mean(r.sW)/100, r.std(r.sW, r.sW2)/100, r.mean(r.sG), r.std(r.sG, r.sG2), r.winwinRate()));
		}

		// ---- 3) Annealing-Temperatur-Sweep (mit Batterie) ----
		double[] temps = { 0, 50, 100, 250, 500, 1000 };
		StringBuilder tCsv = new StringBuilder("startTemperature,welfare_mean_eur,welfare_std_eur,winwin_rate\n");
		System.out.println("\n===== 3) Annealing-Temperatur-Sweep (mit Batterie) =====");
		System.out.println("  T0   | Welfare(€) Mittel±Std | WinWin");
		for (double tt : temps) {
			Stats r = runSeeds(sBat, cBat, tt, nSeeds, rounds);
			System.out.printf(Locale.GERMAN, "%6.0f | %7.2f ± %4.2f       | %3.0f%%%n",
				tt, r.mean(r.sW)/100, r.std(r.sW, r.sW2)/100, r.winwinRate()*100);
			tCsv.append(String.format(Locale.US, "%.0f,%.4f,%.4f,%.4f%n",
				tt, r.mean(r.sW)/100, r.std(r.sW, r.sW2)/100, r.winwinRate()));
		}

		writeCsv("results/capacity_sweep.csv", capCsv.toString());
		writeCsv("results/temperature_sweep.csv", tCsv.toString());
	}

	/** n Läufe über verschiedene (gepaarte) Seeds; aggregiert die Settlement-Kennzahlen. */
	static Stats runSeeds(Battery sBat, Battery cBat, double startTemp, int n, int rounds) {
		Stats st = new Stats();
		double hedge = (IMBALANCE_PRICE > 0.0) ? FORECAST_SIGMA : 0.0;
		for (int i = 0; i < n; i++) {
			ScenarioGenerator scen = new ScenarioGenerator(T, SCENARIO_SEED0 + i, FORECAST_SIGMA);
			Verhandlung.Outcome o = Verhandlung.runNegotiation(scen, sBat, cBat, NEGOTIATION_SEED0 + i,
				startTemp, IMBALANCE_PRICE, hedge, hedge, rounds, false);
			double profit = o.supplier.settledProfit(o.deal);
			double cost   = o.customer.settledCost(o.deal);
			double grid   = Metrics.gridDependencyKWh(o.supplier, o.customer, o.deal);
			boolean ww = profit >= Metrics.supplierBaseline(scen) && cost <= Metrics.customerBaseline(scen);
			st.add(profit, cost, profit - cost, grid, ww);
		}
		return st;
	}

	static void writeCsv(String path, String content) {
		try {
			Files.createDirectories(Paths.get(path).getParent());
			try (PrintWriter pw = new PrintWriter(new FileWriter(path))) { pw.print(content); }
			System.out.println("[CSV] geschrieben: " + path);
		} catch (IOException e) {
			System.err.println("[CSV] Fehler: " + e.getMessage());
		}
	}

	/** Laufende Summen für Mittelwert/Stichproben-Streuung (alle Geldgrößen in ct). */
	static class Stats {
		int n = 0, wins = 0;
		double sP, sP2, sC, sC2, sW, sW2, sG, sG2;

		void add(double p, double c, double w, double g, boolean ww) {
			n++; sP += p; sP2 += p*p; sC += c; sC2 += c*c; sW += w; sW2 += w*w; sG += g; sG2 += g*g;
			if (ww) wins++;
		}
		double mean(double s) { return s / n; }
		double std(double s, double s2) {
			if (n < 2) return 0.0;
			double v = (s2 - s * s / n) / (n - 1);
			return Math.sqrt(Math.max(0.0, v));
		}
		double winwinRate() { return (double) wins / n; }

		void print(String label) {
			System.out.printf(Locale.GERMAN,
				"%-13s n=%d | Profit %5.2f±%.2f € | Kosten %6.2f±%.2f € | Welfare %7.2f±%.2f € | Grid %5.2f±%.2f kWh | WinWin %3.0f%%%n",
				label, n,
				mean(sP)/100, std(sP, sP2)/100,
				mean(sC)/100, std(sC, sC2)/100,
				mean(sW)/100, std(sW, sW2)/100,
				mean(sG), std(sG, sG2),
				winwinRate()*100);
		}
	}
}
