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

		double supplierProfit = supplier.utility(deal);
		double customerCost   = customer.cost(deal);
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

		System.out.println("================== Verhandlungsergebnis ==================");
		System.out.printf("Supplier-Profit : %10.1f ct  (Baseline %9.1f)  -> +%7.2f EUR%n",
			supplierProfit, supplierBase, (supplierProfit - supplierBase) / 100.0);
		System.out.printf("Customer-Kosten : %10.1f ct  (Baseline %9.1f)  -> -%7.2f EUR%n",
			customerCost, customerBase, (customerBase - customerCost) / 100.0);
		System.out.printf("Sozialwohlstand : %10.1f ct  (Baseline %9.1f, Optimum o. Speicher %9.1f -> %.1f%%)%n",
			welfareDeal, welfareBase, welfareOpt, welfarePct);
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
}
