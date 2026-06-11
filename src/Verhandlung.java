import java.util.Random;

/**
 * Bilaterale Strom-Verhandlung über einen uninformierten Mediator (P2P-Energiehandel).
 *
 * Ein Supplier (PV-Überschuss) und ein Customer (Tagesbedarf) verhandeln für einen
 * Tag à 24 Slots je Slot eine Liefermenge x_t und einen Preis p_t. Beide wollen den
 * teureren Grid-Fallback vermeiden; ihre Zielfunktionen bleiben privat.
 *
 * Ablauf (Klein-Stil, aus der Basis übernommen, gekapselt in {@link #runNegotiation}):
 *  - Mediator hält eine Population ganzer Tageskontrakte.
 *  - Jede Runde löschen beide Agenten die für sie schlechtesten Kontrakte,
 *    der Mediator reproduziert die Lücken (GA).
 *  - Die Population dient als Vorschläge; ein Vorschlag wird neue tentative
 *    Vereinbarung ("current"), wenn BEIDE zustimmen (mit Annealing-Toleranz).
 *  - Separat wird das beste beidseitig vorteilhafte Ergebnis archiviert.
 *
 * main() fährt ZWEI Läufe auf demselben Szenario und mit demselben negotiationSeed:
 * einmal OHNE Batterie (Referenz) und einmal MIT Batterie auf beiden Seiten, und
 * vergleicht sie – so wird der Speicher-Mehrwert direkt sichtbar.
 *
 * Reproduzierbarkeit: getrennte Seeds für Szenario und Verhandlung; der gesamte
 * Verhandlungs-Zufall (GA, Votes, Lösch-Münze) läuft über EINE geseedete Random-Instanz.
 *
 * CLI (optional): args[0]=startTemperature, args[1]=negotiationSeed, args[2]=scenarioSeed.
 */
public class Verhandlung {

	// ---------- Tuning (klassenweit, damit beide Läufe identisch sind) ----------
	private static final int    T             = 24;     // Slots (Stunden)
	private static final int    POP_SIZE      = 200;    // Populationsgröße
	private static final int    MAX_ROUNDS    = 10000;  // Verhandlungsrunden
	private static final int    DELETED_SIZE  = 30;     // Löschungen je Runde
	private static final double MUTATION_SIGMA = 0.08;  // Mutationsstärke (Anteil der Spanne)
	private static final double CROSSOVER_RATE = 0.5;   // Anteil Crossover vs. Mutation
	private static final int    COOL_ROUNDS   = (int) (0.6 * MAX_ROUNDS); // Annealing-Abkühlung

	public static void main(String[] args) {
		// Annealing-Start (0 = rein gierig wie Basis) + Seeds, optional per Argument.
		double startTemperature = (args.length > 0) ? Double.parseDouble(args[0]) : 250.0;
		long   negotiationSeed  = (args.length > 1) ? Long.parseLong(args[1])     : 1L;
		long   scenarioSeed     = (args.length > 2) ? Long.parseLong(args[2])     : 42L;

		// Szenario EINMAL erzeugen (beide Läufe verhandeln dasselbe).
		ScenarioGenerator scen = new ScenarioGenerator(T, scenarioSeed);

		// Batterie-Konfiguration: capacity, maxPower [kWh/Slot], round-trip-η, initialSoc.
		Battery noBattery       = Battery.none();
		Battery supplierBattery = new Battery(10.0, 3.0, 0.95, 0.0);
		Battery customerBattery = new Battery( 5.0, 3.0, 0.95, 0.0);

		System.out.println("########## LAUF 1: OHNE Batterie (Referenz) ##########");
		Outcome ref = runNegotiation(scen, noBattery, noBattery, negotiationSeed, startTemperature, true);
		System.out.println();
		Metrics.report(scen, ref.supplier, ref.customer, ref.deal);

		System.out.println();
		System.out.println("########## LAUF 2: MIT Batterie (Supplier + Customer) ##########");
		Outcome bat = runNegotiation(scen, supplierBattery, customerBattery, negotiationSeed, startTemperature, true);
		System.out.println();
		Metrics.report(scen, bat.supplier, bat.customer, bat.deal);

		System.out.println();
		compare(scen, ref, bat);
	}

	/** Ein vollständiger Verhandlungslauf. Liefert den Deal + die (privaten) Agenten zur Auswertung. */
	static Outcome runNegotiation(ScenarioGenerator scen, Battery supplierBat, Battery customerBat,
	                              long negotiationSeed, double startTemperature, boolean verbose) {
		// Frische, geseedete Zufallsquelle -> Lauf ist reproduzierbar und (bei gleichem Seed) vergleichbar.
		Random rng = new Random(negotiationSeed);

		SupplierAgent supplier = new SupplierAgent(scen.generation, scen.feedIn, scen.retail, supplierBat, rng);
		CustomerAgent customer = new CustomerAgent(scen.demand, scen.retail, 0.0, customerBat, rng);

		double amountMax = 1.5 * Math.max(maxOf(scen.generation), maxOf(scen.demand));
		Mediator med = new Mediator(T, scen.feedIn, scen.retail, amountMax,
			MUTATION_SIGMA, CROSSOVER_RATE, startTemperature, COOL_ROUNDS, rng);

		EnergyContract[] pop = new EnergyContract[POP_SIZE];
		for (int i = 0; i < pop.length; i++) pop[i] = med.initContract();

		double supplierBase = Metrics.supplierBaseline(scen);
		double customerBase = Metrics.customerBaseline(scen);

		EnergyContract current  = pop[0];
		EnergyContract bestEver = null;            // bestes beidseitig vorteilhaftes Ergebnis
		double bestWelfare = Double.NEGATIVE_INFINITY;

		if (verbose) {
			System.out.println(" Runde |   Profit(S) |   Kosten(C) |   Temp");
			System.out.println("-------+-------------+-------------+--------");
		}

		for (int round = 0; round < MAX_ROUNDS; round++) {
			double temp = med.temperature(round);

			// 1) Agenten löschen die für sie schlechtesten Kontrakte
			for (int j = 0; j < DELETED_SIZE; j++) {
				if (rng.nextDouble() < 0.5) supplier.delete(pop);
				else                        customer.delete(pop);
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

			if (verbose && round % 500 == 0) logRound(round, supplier, customer, current, temp);
		}
		if (verbose) logRound(MAX_ROUNDS, supplier, customer, current, 0.0);

		EnergyContract deal = (bestEver != null) ? bestEver : current;
		if (bestEver == null && verbose) {
			System.out.println("[Hinweis] Kein beidseitig vorteilhafter Kontrakt gefunden – zeige letzten Stand.");
		}
		return new Outcome(deal, supplier, customer);
	}

	/** Gegenüberstellung der beiden Läufe – zeigt den Mehrwert der Batterie. */
	private static void compare(ScenarioGenerator scen, Outcome ref, Outcome bat) {
		double profitRef = ref.supplier.utility(ref.deal);
		double profitBat = bat.supplier.utility(bat.deal);
		double costRef   = ref.customer.cost(ref.deal);
		double costBat   = bat.customer.cost(bat.deal);
		double gridRef   = Metrics.gridDependencyKWh(ref.supplier, ref.customer, ref.deal);
		double gridBat   = Metrics.gridDependencyKWh(bat.supplier, bat.customer, bat.deal);

		System.out.println("================ Vergleich: Batterie-Mehrwert ================");
		System.out.printf("Supplier-Profit : ohne %9.1f  ->  mit %9.1f ct   (%+.2f EUR)%n",
			profitRef, profitBat, (profitBat - profitRef) / 100.0);
		System.out.printf("Customer-Kosten : ohne %9.1f  ->  mit %9.1f ct   (%+.2f EUR)%n",
			costRef, costBat, -(costBat - costRef) / 100.0);
		System.out.printf("Netz-Abhängigkeit: ohne %8.2f  ->  mit %8.2f kWh   (%+.2f kWh)%n",
			gridRef, gridBat, gridBat - gridRef);
		System.out.println("==============================================================");
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

	/** Ergebnis eines Laufs: der ausgehandelte Deal samt der (privaten) Agenten zur Auswertung. */
	static class Outcome {
		final EnergyContract deal;
		final SupplierAgent supplier;
		final CustomerAgent customer;

		Outcome(EnergyContract deal, SupplierAgent supplier, CustomerAgent customer) {
			this.deal = deal;
			this.supplier = supplier;
			this.customer = customer;
		}
	}
}
