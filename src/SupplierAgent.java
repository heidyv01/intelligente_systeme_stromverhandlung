import java.util.Random;

/**
 * Supplier-Agent: möchte seinen Gewinn maximieren und seinen Erzeugungs-
 * überschuss möglichst teuer an den Customer verkaufen, statt ihn billig
 * ins Netz einzuspeisen (Grid-Fallback vermeiden).
 *
 * PRIVAT:     generation   – verfügbarer Erzeugungsüberschuss je Slot [kWh]
 *             battery      – eigener Speicher (kann Mittags-Überschuss in den Abend schieben)
 * ÖFFENTLICH: feedInTariff – Einspeisevergütung (Fallback-Erlös)        [ct/kWh]
 *             gridBuyPrice – Netzbezugspreis (Rückkaufpreis bei Übermenge) [ct/kWh]
 *
 * Zielfunktion (höher = besser), je Slot aufsummiert, NACH Batterie-Dispatch
 * (net_t = generation − delivered):
 *   profit += delivered · price
 *           + leftoverSurplus · feedInTariff   // nicht gespeicherter Überschuss → Netz
 *           − unmetDeficit   · gridBuyPrice    // vom Speicher nicht gedeckte Lieferung → teuer zukaufen
 *
 * Ohne Batterie (capacity 0) ist leftoverSurplus = max(generation−delivered,0) und
 * unmetDeficit = max(delivered−generation,0) – also das ursprüngliche slot-unabhängige Modell.
 *
 * Konzept-Notation: delivered=x_t, price=p_t, generation=g_t, feedInTariff=f_t, gridBuyPrice=r_t.
 */
public class SupplierAgent extends Agent {

	private final int slots;
	private final double[] generation;   // privat:     Erzeugung je Slot        [kWh]
	private final double[] feedInTariff; // öffentlich:  Einspeisevergütung        [ct/kWh]
	private final double[] gridBuyPrice; // öffentlich:  Rückkaufpreis bei Übermenge [ct/kWh]
	private final Battery battery;       // privat:     eigener Speicher
	private final Random rng;            // geteilter, geseedeter Verhandlungs-Zufall

	// Wiederverwendbare Puffer für den Dispatch (allokationsfreier heißer Pfad)
	private final double[] netScratch;
	private final double[] surplusScratch;
	private final double[] deficitScratch;

	public SupplierAgent(double[] generation, double[] feedInTariff, double[] gridBuyPrice,
	                     Battery battery, Random rng) {
		this.slots = generation.length;
		this.generation = generation.clone();
		this.feedInTariff = feedInTariff.clone();
		this.gridBuyPrice = gridBuyPrice.clone();
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
		for (int t = 0; t < slots; t++) net[t] = generation[t] - contract.amount(t);
		return battery.run(net);
	}

	@Override
	public double utility(EnergyContract contract) {
		for (int t = 0; t < slots; t++) netScratch[t] = generation[t] - contract.amount(t);
		battery.run(netScratch, surplusScratch, deficitScratch);
		double profit = 0.0;
		for (int t = 0; t < slots; t++) {
			profit += contract.amount(t) * contract.price(t)
			        + surplusScratch[t] * feedInTariff[t]   // Rest-Überschuss einspeisen
			        - deficitScratch[t] * gridBuyPrice[t];   // ungedeckte Lieferung teuer zukaufen
		}
		return profit;
	}

	@Override
	public boolean vote(EnergyContract contract, EnergyContract proposal, double temperature) {
		double delta = utility(proposal) - utility(contract);
		if (delta >= 0.0) return true;            // Verbesserung: immer
		if (temperature <= 0.0) return false;     // strikt
		return rng.nextDouble() < Math.exp(delta / temperature); // Annealing
	}

	@Override
	public void delete(EnergyContract[] population) {
		int worst = -1;
		double worstProfit = Double.POSITIVE_INFINITY;
		for (int i = 0; i < population.length; i++) {
			if (population[i] == null) continue;
			double profit = utility(population[i]);
			if (profit < worstProfit) { worstProfit = profit; worst = i; } // schlechtester = niedrigster Gewinn
		}
		if (worst != -1) population[worst] = null;
	}

	@Override
	public void printUtility(EnergyContract contract) {
		System.out.printf("Profit=%9.1f ct", utility(contract));
	}

	@Override
	public int getSlots() {
		return slots;
	}
}
