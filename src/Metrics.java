
import java.io.*;
import java.nio.file.*;
import java.util.Locale;

/**
 * Auswertung des Verhandlungsergebnisses (God-View).
 *
 * Diese Klasse darf – im Gegensatz zu Mediator und Agenten – beide privaten
 * Zielfunktionen UND die Profile sehen. Sie ist NICHT Teil des Verhandlungs-
 * mechanismus, sondern dient ausschließlich der Analyse / dem "Verkaufen" des
 * Ergebnisses (Ersparnis je Partei, Effizienz, Grid-Abhängigkeit).
 */
public class Metrics {

	/** Supplier-No-Trade-Baseline: gesamten Überschuss ins Netz einspeisen. */
	public static double supplierBaseline(ScenarioGenerator scen) {
		double baseline = 0.0;
		for (int t = 0; t < scen.slots; t++) baseline += scen.generation[t] * scen.feedIn[t];
		return baseline;
	}

	/** Customer-No-Trade-Baseline: gesamten Bedarf aus dem Netz beziehen. */
	public static double customerBaseline(ScenarioGenerator scen) {
		double baseline = 0.0;
		for (int t = 0; t < scen.slots; t++) baseline += scen.demand[t] * scen.retail[t];
		return baseline;
	}

	/** Theoretisch maximaler Sozialwohlstand bei directTrade = min(generation, demand) (Preis hebt sich heraus). */
	public static double optimalWelfare(ScenarioGenerator scen) {
		double welfare = 0.0;
		for (int t = 0; t < scen.slots; t++) {
			double directTrade = Math.min(scen.generation[t], scen.demand[t]);
			welfare += Math.max(scen.generation[t] - directTrade, 0.0) * scen.feedIn[t]  // Supplier-Anteil (ohne Transfer)
			         - Math.max(scen.demand[t] - directTrade, 0.0) * scen.retail[t];     // Customer-Anteil (ohne Transfer)
		}
		return welfare;
	}

	/** Gesamte Netz-Interaktion [kWh]: Supplier-Einspeisung + Zukäufe beider Seiten (God-View). */
	public static double gridDependencyKWh(SupplierAgent supplier, CustomerAgent customer, EnergyContract deal) {
		Battery.Result sd = supplier.dispatch(deal);
		Battery.Result cd = customer.dispatch(deal);
		return sd.totalLeftoverSurplus() + sd.totalUnmetDeficit() + cd.totalUnmetDeficit();
	}

	public static void report(ScenarioGenerator scen, SupplierAgent supplier,
	                          CustomerAgent customer, EnergyContract deal) {
		int T = scen.slots;

		// Settlement-Sicht: realisierte Werte (echte Profile + Imbalance-Strafe).
		double supplierProfit = supplier.settledProfit(deal);
		double customerCost   = customer.settledCost(deal);
		double expSupplier    = supplier.expectedProfit(deal); // erwartet (auf Forecast, ohne Strafe)
		double expCustomer    = customer.expectedCost(deal);
		double supplierBase   = supplierBaseline(scen);
		double customerBase   = customerBaseline(scen);

		double welfareDeal = supplierProfit - customerCost; // U_S + U_C, U_C = −Kosten
		double welfareBase = supplierBase   - customerBase;
		double welfareOpt  = optimalWelfare(scen);
		double welfarePct  = welfareOpt - welfareBase != 0.0
			? 100.0 * (welfareDeal - welfareBase) / (welfareOpt - welfareBase) : 100.0;

		// Netz-Kennzahlen NACH Batterie-Dispatch beider Seiten (single-sourced über die Agenten).
		// Ohne Batterie (capacity 0) entspricht dies exakt max(demand−x,0) bzw. max(generation−x,0).
		Battery.Result sd = supplier.dispatch(deal);
		Battery.Result cd = customer.dispatch(deal);
		double gridExport      = sd.totalLeftoverSurplus();  // Supplier -> Netz (nicht gespeicherter Überschuss)
		double supplierGridBuy = sd.totalUnmetDeficit();     // Supplier <- Netz (zur Lieferung zugekauft)
		double customerGridBuy = cd.totalUnmetDeficit();     // Customer <- Netz (ungedeckter Bedarf)
		double gridImport      = supplierGridBuy + customerGridBuy;
		double overDelivery    = cd.totalLeftoverSurplus();  // nicht speicherbare Überlieferung

		double traded = 0, possible = 0;
		for (int t = 0; t < T; t++) {
			double delivered = deal.amount(t);
			traded   += Math.min(delivered, Math.min(scen.generation[t], scen.demand[t]));
			possible += Math.min(scen.generation[t], scen.demand[t]);
		}
		double matchRate = possible > 0 ? 100.0 * traded / possible : 0.0;

		System.out.println("============= Ergebnis (realisiert / Settlement) =============");
		System.out.printf("Supplier-Profit : %8.2f EUR  (Netz %6.2f,  ggü. Netz %+6.2f EUR)%n",
			supplierProfit / 100.0, supplierBase / 100.0, (supplierProfit - supplierBase) / 100.0);
		System.out.printf("Customer-Kosten : %8.2f EUR  (Netz %6.2f,  ggü. Netz %+6.2f EUR)%n",
			customerCost / 100.0, customerBase / 100.0, (customerBase - customerCost) / 100.0);
		System.out.printf("Sozialwohlstand : %8.2f EUR  (Netz %6.2f, Optimum o. Speicher %6.2f -> %.1f%%)%n",
			welfareDeal / 100.0, welfareBase / 100.0, welfareOpt / 100.0, welfarePct);
		System.out.printf("Matching-Rate   : %6.1f%% des möglichen Direkthandels (min(g,d) gedeckt)%n", matchRate);
		System.out.printf("Überlieferung   : %7.2f kWh (geliefert, nicht gebraucht/speicherbar)%n", overDelivery);
		System.out.printf("Netz-Bezug Rest : %7.2f kWh    Netz-Einspeisung Rest: %7.2f kWh%n",
			gridImport, gridExport);
		if (supplier.getBattery().isPresent() || customer.getBattery().isPresent()) {
			System.out.printf("Batterie SoC Ende: Supplier %5.2f/%.1f kWh, Customer %5.2f/%.1f kWh%n",
				sd.finalSoc, supplier.getBattery().capacity, cd.finalSoc, customer.getBattery().capacity);
		}

		boolean irSupplier = supplierProfit >= supplierBase;
		boolean irCustomer = customerCost <= customerBase;
		System.out.printf("Win-Win (beide besser als Netz)? Supplier=%s, Customer=%s%n",
			irSupplier ? "ja" : "NEIN", irCustomer ? "ja" : "NEIN");

		// Prognosefehler / Imbalance (nur wenn Unsicherheit aktiv)
		double fcErrS = supplier.forecastErrorKWh();
		double fcErrC = customer.forecastErrorKWh();
		if (fcErrS + fcErrC > 0.001) {
			System.out.printf("Erwartet->Real. : Profit %6.2f->%6.2f EUR,  Kosten %6.2f->%6.2f EUR%n",
				expSupplier / 100.0, supplierProfit / 100.0, expCustomer / 100.0, customerCost / 100.0);
			System.out.printf("Prognosefehler  : Supplier %6.2f kWh, Customer %6.2f kWh%n", fcErrS, fcErrC);
			System.out.printf("Imbalance-Strafe: Supplier %6.2f EUR, Customer %6.2f EUR%n",
				supplier.imbalancePenalty(deal) / 100.0, customer.imbalancePenalty(deal) / 100.0);
		}
		System.out.println("----------------------------------------------------------");
		printContract(scen, deal);
	}

	private static void printContract(ScenarioGenerator scen, EnergyContract contract) {
		System.out.println("Legende: g=Erzeugung, d=Bedarf, x=Liefermenge [kWh], p=Preis [ct/kWh], Band=[Einspeise..Bezug]");
		System.out.println("Slot |   g_t |   d_t |   x_t |   p_t  [Band f..r]");
		for (int t = 0; t < scen.slots; t++) {
			System.out.printf("%4d | %5.1f | %5.1f | %5.1f | %6.1f  [%4.1f..%5.1f]%n",
				t, scen.generation[t], scen.demand[t], contract.amount(t), contract.price(t),
				scen.feedIn[t], scen.retail[t]);
		}
	}

	// -----------------------------------------------------------------------
	// CSV-Export (für Python-Visualisierung)
	// -----------------------------------------------------------------------

	/**
	 * Schreibt zwei CSV-Dateien in outputDir:
	 *   slots_data.csv   – je Slot: Profile + beide Vertragsvarianten + Batterie-Dispatch
	 *   summary_data.csv – Zusammenfassung beider Läufe (Gewinn, Kosten, Wohlstand, Netz)
	 */
	public static void writeCSV(ScenarioGenerator scen,
	                             SupplierAgent refSupplier, CustomerAgent refCustomer, EnergyContract refDeal,
	                             SupplierAgent batSupplier, CustomerAgent batCustomer, EnergyContract batDeal,
	                             String outputDir) {
		try {
			Files.createDirectories(Paths.get(outputDir));

			// --- slots_data.csv ---
			Battery.Result refSD = refSupplier.dispatch(refDeal);
			Battery.Result refCD = refCustomer.dispatch(refDeal);
			Battery.Result batSD = batSupplier.dispatch(batDeal);
			Battery.Result batCD = batCustomer.dispatch(batDeal);

			try (PrintWriter pw = new PrintWriter(new FileWriter(outputDir + "/slots_data.csv"))) {
				pw.println("slot,generation,demand,feedIn,retail,"
					+ "ref_amount,ref_price,ref_supplier_surplus,ref_customer_deficit,"
					+ "bat_amount,bat_price,bat_supplier_surplus,bat_customer_deficit");
				for (int t = 0; t < scen.slots; t++) {
					pw.printf(Locale.US, "%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
						t,
						scen.generation[t], scen.demand[t], scen.feedIn[t], scen.retail[t],
						refDeal.amount(t), refDeal.price(t),
						refSD.leftoverSurplus[t], refCD.unmetDeficit[t],
						batDeal.amount(t), batDeal.price(t),
						batSD.leftoverSurplus[t], batCD.unmetDeficit[t]);
				}
			}

			// --- summary_data.csv ---
			double refProfitEUR  = refSupplier.settledProfit(refDeal) / 100.0;
			double refCostEUR    = refCustomer.settledCost(refDeal)   / 100.0;
			double batProfitEUR  = batSupplier.settledProfit(batDeal) / 100.0;
			double batCostEUR    = batCustomer.settledCost(batDeal)   / 100.0;
			double baselineS     = supplierBaseline(scen) / 100.0;
			double baselineC     = customerBaseline(scen) / 100.0;
			double welfOpt       = optimalWelfare(scen) / 100.0;
			double welfBase      = baselineS - baselineC;
			double gridRef       = gridDependencyKWh(refSupplier, refCustomer, refDeal);
			double gridBat       = gridDependencyKWh(batSupplier, batCustomer, batDeal);

			// Matching-Rate per run
			int T = scen.slots;
			double refTraded = 0, batTraded = 0, possible = 0;
			for (int t = 0; t < T; t++) {
				double pot = Math.min(scen.generation[t], scen.demand[t]);
				refTraded += Math.min(refDeal.amount(t), pot);
				batTraded += Math.min(batDeal.amount(t), pot);
				possible  += pot;
			}
			double refMatch = possible > 0 ? 100.0 * refTraded / possible : 0.0;
			double batMatch = possible > 0 ? 100.0 * batTraded / possible : 0.0;

			try (PrintWriter pw = new PrintWriter(new FileWriter(outputDir + "/summary_data.csv"))) {
				pw.println("run,supplier_profit_eur,supplier_baseline_eur,customer_cost_eur,"
					+ "customer_baseline_eur,welfare_eur,welfare_baseline_eur,welfare_optimal_eur,"
					+ "grid_kwh,match_rate_pct");
				pw.printf(Locale.US, "ohne Batterie,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.2f%n",
					refProfitEUR, baselineS, refCostEUR, baselineC,
					refProfitEUR - refCostEUR, welfBase, welfOpt, gridRef, refMatch);
				pw.printf(Locale.US, "mit Batterie,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.2f%n",
					batProfitEUR, baselineS, batCostEUR, baselineC,
					batProfitEUR - batCostEUR, welfBase, welfOpt, gridBat, batMatch);
			}

			System.out.println("[CSV] Geschrieben: " + outputDir + "/slots_data.csv, summary_data.csv");
		} catch (IOException e) {
			System.err.println("[CSV] Fehler beim Schreiben: " + e.getMessage());
		}
	}
}
