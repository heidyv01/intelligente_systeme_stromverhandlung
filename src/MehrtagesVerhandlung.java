import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Stufe 3 – Mehrtägige Verhandlung mit Lerneffekt (adaptive σ-Schätzung).
 *
 * Day-Ahead schließen die Agenten Verträge auf Forecasts ab und kennen ihr wahres σ NICHT.
 * Sie lernen ihre eigene Prognosegüte über mehrere Tage: nach jedem Settlement beobachten sie
 * ihre relativen Prognosefehler und aktualisieren eine Schätzung σ̂, mit der sie am nächsten Tag
 * hedgen (konservativeres Bieten). Über die Tage konvergiert σ̂ → σ.
 *
 * Verglichen werden drei Strategien auf DERSELBEN Tagesfolge (gleiche Seeds, nur der Hedge unterscheidet sich):
 *   - Naiv:    σ̂ ≡ 0   (hedged nie)          – Untergrenze
 *   - Learner: σ̂ adaptiv (lernt aus Fehlern) – soll sich dem Oracle annähern
 *   - Oracle:  σ̂ ≡ σ   (kennt σ ab Tag 1)    – Obergrenze (= Stufe-2-Verhalten)
 *
 * Fokus auf den Lerneffekt -> ohne Batterie (die würde Prognosefehler zusätzlich puffern).
 * Schreibt results/learning_curve.csv für die Python-Visualisierung.
 */
public class MehrtagesVerhandlung {

	private static final int    T               = 24;
	private static final int    DAYS            = 30;
	private static final double TRUE_SIGMA      = 0.15;   // wahrer Prognosefehler (den Agenten unbekannt)
	private static final double IMBALANCE_PRICE = 100.0;  // α [ct/kWh] – punitiv, damit Hedging/Lernen lohnt
	private static final double START_TEMP      = 250.0;
	private static final int    ROUNDS_PER_DAY  = 4000;   // pro Tag (kürzer als Einzeltag, da viele Läufe)
	private static final long   SCENARIO_SEED   = 42L;
	private static final long   NEGOTIATION_SEED = 1L;
	private static final double EPS             = 0.01;   // Slots mit ~0 Forecast liefern keine Fehlerinfo

	public static void main(String[] args) {
		// Optional per CLI: args[0]=imbalancePrice α, args[1]=days, args[2]=trueSigma
		double alpha     = (args.length > 0) ? Double.parseDouble(args[0]) : IMBALANCE_PRICE;
		int    days      = (args.length > 1) ? Integer.parseInt(args[1])   : DAYS;
		double trueSigma = (args.length > 2) ? Double.parseDouble(args[2]) : TRUE_SIGMA;

		Battery noBat = Battery.none();

		// Online-Schätzung der relativen Prognosefehler-Streuung des Learners
		double sumSqS = 0.0, sumSqC = 0.0;
		long   cntS = 0,    cntC = 0;

		// Kumulierte Settlement-Kennzahlen je Strategie [ct]
		double naivP = 0, naivC = 0, naivImb = 0;
		double lernP = 0, lernC = 0, lernImb = 0;
		double oracP = 0, oracC = 0, oracImb = 0;

		StringBuilder csv = new StringBuilder();
		csv.append("day,sigmaHat_S,sigmaHat_C,"
			+ "naiv_profit,naiv_cost,naiv_imbalance,"
			+ "lern_profit,lern_cost,lern_imbalance,"
			+ "orac_profit,orac_cost,orac_imbalance,"
			+ "naiv_cumProfit,lern_cumProfit,orac_cumProfit,"
			+ "naiv_cumCost,lern_cumCost,orac_cumCost\n");

		System.out.printf(Locale.GERMAN,
			"Mehrtägiges Lernen (adaptive σ-Schätzung) – wahres σ=%.2f, Tage=%d, α=%.0f ct/kWh%n",
			trueSigma, days, alpha);
		System.out.println("Profit/Kosten je Tag in EUR.  L=Learner, O=Oracle, N=Naiv");
		System.out.println(" Tag | sigmaHat S/C |  L:Prof  Kost |  O:Prof  Kost |  N:Prof  Kost");
		System.out.println("-----+--------------+---------------+---------------+--------------");

		for (int day = 0; day < days; day++) {
			long scSeed = SCENARIO_SEED + day;
			long ngSeed = NEGOTIATION_SEED + day;
			ScenarioGenerator scen = new ScenarioGenerator(T, scSeed, trueSigma);

			// Learner hedged mit σ̂ aus den BISHERIGEN Tagen (Tag 1: σ̂ = 0 -> naiv)
			double hatS = (cntS > 0) ? Math.sqrt(sumSqS / cntS) : 0.0;
			double hatC = (cntC > 0) ? Math.sqrt(sumSqC / cntC) : 0.0;

			// Drei Strategien auf demselben Tag, nur Hedge unterschiedlich
			Verhandlung.Outcome naiv = Verhandlung.runNegotiation(scen, noBat, noBat, ngSeed, START_TEMP,
				alpha, 0.0, 0.0, ROUNDS_PER_DAY, false);
			Verhandlung.Outcome lern = Verhandlung.runNegotiation(scen, noBat, noBat, ngSeed, START_TEMP,
				alpha, hatS, hatC, ROUNDS_PER_DAY, false);
			Verhandlung.Outcome orac = Verhandlung.runNegotiation(scen, noBat, noBat, ngSeed, START_TEMP,
				alpha, trueSigma, trueSigma, ROUNDS_PER_DAY, false);

			double nP = naiv.supplier.settledProfit(naiv.deal), nC = naiv.customer.settledCost(naiv.deal);
			double lP = lern.supplier.settledProfit(lern.deal), lC = lern.customer.settledCost(lern.deal);
			double oP = orac.supplier.settledProfit(orac.deal), oC = orac.customer.settledCost(orac.deal);
			double nImb = naiv.supplier.imbalancePenalty(naiv.deal) + naiv.customer.imbalancePenalty(naiv.deal);
			double lImb = lern.supplier.imbalancePenalty(lern.deal) + lern.customer.imbalancePenalty(lern.deal);
			double oImb = orac.supplier.imbalancePenalty(orac.deal) + orac.customer.imbalancePenalty(orac.deal);

			naivP += nP; naivC += nC; naivImb += nImb;
			lernP += lP; lernC += lC; lernImb += lImb;
			oracP += oP; oracC += oC; oracImb += oImb;

			// σ̂ aus den Fehlern DIESES Tages aktualisieren (Learner beobachtet sich selbst)
			for (int t = 0; t < T; t++) {
				if (scen.generationForecast[t] > EPS) {
					double e = (scen.generation[t] - scen.generationForecast[t]) / scen.generationForecast[t];
					sumSqS += e * e; cntS++;
				}
				if (scen.demandForecast[t] > EPS) {
					double e = (scen.demand[t] - scen.demandForecast[t]) / scen.demandForecast[t];
					sumSqC += e * e; cntC++;
				}
			}

			System.out.printf(Locale.GERMAN,
				"%4d | %5.3f/%5.3f | %6.2f %6.2f | %6.2f %6.2f | %6.2f %6.2f%n",
				day + 1, hatS, hatC, lP / 100, lC / 100, oP / 100, oC / 100, nP / 100, nC / 100);

			csv.append(String.format(Locale.US,
				"%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
				day + 1, hatS, hatC,
				nP / 100, nC / 100, nImb / 100,
				lP / 100, lC / 100, lImb / 100,
				oP / 100, oC / 100, oImb / 100,
				naivP / 100, lernP / 100, oracP / 100,
				naivC / 100, lernC / 100, oracC / 100));
		}

		double hatSfin = (cntS > 0) ? Math.sqrt(sumSqS / cntS) : 0.0;
		double hatCfin = (cntC > 0) ? Math.sqrt(sumSqC / cntC) : 0.0;

		System.out.println();
		System.out.printf(Locale.GERMAN, "===== Kumuliert über %d Tage (EUR) =====%n", days);
		System.out.println("Strategie | Supplier-Profit | Customer-Kosten | Imbalance-Strafe");
		System.out.printf(Locale.GERMAN, "Naiv      | %14.2f | %14.2f | %14.2f%n", naivP / 100, naivC / 100, naivImb / 100);
		System.out.printf(Locale.GERMAN, "Learner   | %14.2f | %14.2f | %14.2f%n", lernP / 100, lernC / 100, lernImb / 100);
		System.out.printf(Locale.GERMAN, "Oracle    | %14.2f | %14.2f | %14.2f%n", oracP / 100, oracC / 100, oracImb / 100);
		System.out.printf(Locale.GERMAN,
			"Gelerntes σ̂ am Ende: Supplier %.3f, Customer %.3f  (wahres σ=%.3f)%n",
			hatSfin, hatCfin, trueSigma);

		try {
			Files.createDirectories(Paths.get("results"));
			try (PrintWriter pw = new PrintWriter(new FileWriter("results/learning_curve.csv"))) {
				pw.print(csv);
			}
			System.out.println("[CSV] Geschrieben: results/learning_curve.csv");
		} catch (IOException e) {
			System.err.println("[CSV] Fehler beim Schreiben: " + e.getMessage());
		}
	}
}
