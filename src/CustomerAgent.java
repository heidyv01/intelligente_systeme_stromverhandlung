import java.util.Random;

/**
 * Customer-Agent: möchte seine Kosten minimieren und seinen Bedarf möglichst
 * direkt vom Supplier decken, statt teuer aus dem Netz zu beziehen
 * (Grid-Fallback vermeiden).
 *
 * PRIVAT:     demand      – Energiebedarf je Slot [kWh]
 *             battery     – eigener Speicher (kann Überlieferung puffern und im Peak nutzen)
 * ÖFFENTLICH: retailPrice – Netzbezugspreis (Fallback für Defizit) [ct/kWh]
 *
 * Kostenfunktion, je Slot aufsummiert, NACH Batterie-Dispatch (net_t = delivered − demand):
 *   cost += delivered · price
 *         + unmetDeficit   · retailPrice    // vom Speicher nicht gedeckter Bedarf → teuer aus Netz
 *         + leftoverSurplus · surplusPenalty // nicht speicherbare Überlieferung → wertlos (Default 0)
 *
 * Ohne Batterie (capacity 0) ist unmetDeficit = max(demand−delivered,0) und
 * leftoverSurplus = max(delivered−demand,0) – also das ursprüngliche slot-unabhängige Modell.
 *
 * utility = −cost (Konvention: höher = besser).
 * Konzept-Notation: delivered=x_t, price=p_t, demand=d_t, retailPrice=r_t, surplusPenalty=s.
 */
public class CustomerAgent extends Agent {

	private final int slots;
	private final double[] demand;        // privat:    Bedarf je Slot      [kWh]
	private final double[] retailPrice;   // öffentlich: Netzbezugspreis     [ct/kWh]
	private final double surplusPenalty;  // Strafkosten je nicht speicherbarer Überlieferungs-kWh [ct/kWh]
	private final Battery battery;        // privat:    eigener Speicher
	private final Random rng;             // geteilter, geseedeter Verhandlungs-Zufall

	// Wiederverwendbare Puffer für den Dispatch (allokationsfreier heißer Pfad)
	private final double[] netScratch;
	private final double[] surplusScratch;
	private final double[] deficitScratch;

	public CustomerAgent(double[] demand, double[] retailPrice, double surplusPenalty,
	                     Battery battery, Random rng) {
		this.slots = demand.length;
		this.demand = demand.clone();
		this.retailPrice = retailPrice.clone();
		this.surplusPenalty = surplusPenalty;
		this.battery = battery;
		this.rng = rng;
		this.netScratch = new double[slots];
		this.surplusScratch = new double[slots];
		this.deficitScratch = new double[slots];
	}

	public Battery getBattery() {
		return battery;
	}

	/** Batterie-Dispatch für diesen Kontrakt (frische Arrays – für die Auswertung). */
	public Battery.Result dispatch(EnergyContract contract) {
		double[] net = new double[slots];
		for (int t = 0; t < slots; t++) net[t] = contract.amount(t) - demand[t];
		return battery.run(net);
	}

	/** Gesamtkosten des Kontrakts [ct] (niedriger = besser). */
	public double cost(EnergyContract contract) {
		for (int t = 0; t < slots; t++) netScratch[t] = contract.amount(t) - demand[t];
		battery.run(netScratch, surplusScratch, deficitScratch);
		double cost = 0.0;
		for (int t = 0; t < slots; t++) {
			cost += contract.amount(t) * contract.price(t)
			      + deficitScratch[t] * retailPrice[t]      // ungedeckter Bedarf teuer aus Netz
			      + surplusScratch[t] * surplusPenalty;      // nicht speicherbare Überlieferung
		}
		return cost;
	}

	@Override
	public double utility(EnergyContract contract) {
		return -cost(contract);
	}

	@Override
	public boolean vote(EnergyContract contract, EnergyContract proposal, double temperature) {
		double delta = utility(proposal) - utility(contract); // = cost(contract) − cost(proposal)
		if (delta >= 0.0) return true;            // billiger: immer
		if (temperature <= 0.0) return false;     // strikt
		return rng.nextDouble() < Math.exp(delta / temperature); // Annealing
	}

	@Override
	public void delete(EnergyContract[] population) {
		int worst = -1;
		double worstUtility = Double.POSITIVE_INFINITY;
		for (int i = 0; i < population.length; i++) {
			if (population[i] == null) continue;
			double util = utility(population[i]);
			if (util < worstUtility) { worstUtility = util; worst = i; } // schlechtester = teuerster
		}
		if (worst != -1) population[worst] = null;
	}

	@Override
	public void printUtility(EnergyContract contract) {
		System.out.printf("Kosten=%9.1f ct", cost(contract));
	}

	@Override
	public int getSlots() {
		return slots;
	}
}
