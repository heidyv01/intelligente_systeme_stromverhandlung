/**
 * Bilaterale Strom-Verhandlung über einen uninformierten Mediator (P2P-Energiehandel).
 *
 * Ein Supplier (PV-Überschuss) und ein Customer (Tagesbedarf) verhandeln für einen
 * Tag à 24 Slots je Slot eine Liefermenge x_t und einen Preis p_t. Beide wollen den
 * teureren Grid-Fallback vermeiden; ihre Zielfunktionen bleiben privat.
 *
 * Ablauf (Klein-Stil, aus der Basis übernommen):
 *  - Mediator hält eine Population ganzer Tageskontrakte.
 *  - Jede Runde löschen beide Agenten die für sie schlechtesten Kontrakte,
 *    der Mediator reproduziert die Lücken (GA).
 *  - Die Population dient als Vorschläge; ein Vorschlag wird neue tentative
 *    Vereinbarung ("current"), wenn BEIDE zustimmen (mit Annealing-Toleranz).
 *  - Separat wird das beste beidseitig vorteilhafte Ergebnis archiviert.
 */
public class Verhandlung {

	public static void main(String[] args) {
		// ---------- Parameter ----------
		final int  T           = 24;     // Slots (Stunden)
		final int  popSize     = 200;    // Populationsgröße
		final int  maxRounds   = 10000;  // Verhandlungsrunden
		final int  deletedSize = 30;     // Löschungen je Runde
		final long seed        = 42L;

		// Annealing (Mediator-Stellhebel gegen Stagnation): T0=0 -> rein gierig (wie Basis).
		// Optional per Argument überschreibbar, z.B. "java Verhandlung 0" für den Vergleichslauf.
		final double startTemperature = (args.length > 0) ? Double.parseDouble(args[0]) : 250.0;
		final int    coolRounds       = (int) (0.6 * maxRounds);
		// GA-Operatoren
		final double mutationSigma = 0.08; // 8% der jeweiligen Spanne
		final double crossoverRate = 0.5;

		// ---------- Szenario (synthetisch, reproduzierbar) ----------
		ScenarioGenerator scen = new ScenarioGenerator(T, seed);

		// ---------- Agenten (private Profile) ----------
		SupplierAgent supplier = new SupplierAgent(scen.generation, scen.feedIn, scen.retail);
		CustomerAgent customer = new CustomerAgent(scen.demand, scen.retail, 0.0);

		// ---------- Mediator (uninformiert; kennt nur öffentliche Bänder) ----------
		double amountMax = 1.5 * Math.max(maxOf(scen.generation), maxOf(scen.demand));
		Mediator med = new Mediator(T, scen.feedIn, scen.retail, amountMax,
			mutationSigma, crossoverRate, startTemperature, coolRounds, seed);

		// ---------- Population initialisieren ----------
		EnergyContract[] pop = new EnergyContract[popSize];
		for (int i = 0; i < pop.length; i++) pop[i] = med.initContract();

		// Individuelle Rationalität (Win-Win) wird gegen die No-Trade-Baselines geprüft.
		double supplierBase = Metrics.supplierBaseline(scen);
		double customerBase = Metrics.customerBaseline(scen);

		EnergyContract current  = pop[0];
		EnergyContract bestEver = null;            // bestes beidseitig vorteilhaftes Ergebnis
		double bestWelfare = Double.NEGATIVE_INFINITY;

		System.out.println(" Runde |   Profit(S) |   Kosten(C) |   Temp");
		System.out.println("-------+-------------+-------------+--------");

		for (int round = 0; round < maxRounds; round++) {
			double temp = med.temperature(round);

			// 1) Agenten löschen die für sie schlechtesten Kontrakte
			for (int j = 0; j < deletedSize; j++) {
				if (Math.random() < 0.5) supplier.delete(pop);
				else                     customer.delete(pop);
			}

			// 2) Mediator füllt die Population wieder auf (Crossover/Mutation)
			pop = med.contractReproduction(pop);

			// 3) Population als Vorschläge; beidseitig akzeptierte verschieben "current"
			for (int i = 0; i < pop.length; i++) {
				EnergyContract prop = pop[i];
				if (prop == null) continue;
				if (supplier.vote(current, prop, temp) && customer.vote(current, prop, temp)) {
					current = prop;
					// Archiv: nur Win-Win-Kontrakte (beide besser als Netz), dort max. Effizienz
					if (supplier.utility(prop) >= supplierBase && customer.cost(prop) <= customerBase) {
						double w = supplier.utility(prop) - customer.cost(prop); // Sozialwohlstand
						if (w > bestWelfare) { bestWelfare = w; bestEver = prop; }
					}
				}
			}

			if (round % 500 == 0) logRound(round, supplier, customer, current, temp);
		}
		logRound(maxRounds, supplier, customer, current, 0.0);

		// ---------- Ergebnis ----------
		EnergyContract deal = (bestEver != null) ? bestEver : current;
		System.out.println();
		if (bestEver == null) {
			System.out.println("[Hinweis] Kein beidseitig vorteilhafter Kontrakt gefunden – zeige letzten Stand.");
		}
		Metrics.report(scen, supplier, customer, deal);
	}

	private static double maxOf(double[] a) {
		double m = 0.0;
		for (double v : a) m = Math.max(m, v);
		return m;
	}

	private static void logRound(int round, SupplierAgent s, CustomerAgent c,
	                             EnergyContract k, double temp) {
		System.out.printf("%6d | %11.1f | %11.1f | %6.1f%n",
			round, s.utility(k), c.cost(k), temp);
	}
}
