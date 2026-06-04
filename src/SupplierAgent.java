/**
 * Supplier-Agent: möchte seinen Gewinn maximieren und seinen Erzeugungs-
 * überschuss möglichst teuer an den Customer verkaufen, statt ihn billig
 * ins Netz einzuspeisen (Grid-Fallback vermeiden).
 *
 * PRIVAT:     generation   – verfügbarer Erzeugungsüberschuss je Slot [kWh]
 * ÖFFENTLICH: feedInTariff – Einspeisevergütung (Fallback-Erlös)        [ct/kWh]
 *             gridBuyPrice – Netzbezugspreis (Rückkaufpreis bei Übermenge) [ct/kWh]
 *
 * Zielfunktion (höher = besser), je Slot aufsummiert:
 *   profit += delivered · price
 *           + max(generation − delivered, 0) · feedInTariff   // Rest-Überschuss ins Netz
 *           − max(delivered − generation, 0) · gridBuyPrice   // teurer Zukauf bei Übermenge
 *
 * Konzept-Notation: delivered=x_t, price=p_t, generation=g_t, feedInTariff=f_t, gridBuyPrice=r_t.
 */
public class SupplierAgent extends Agent {

	private final int slots;
	private final double[] generation;   // privat:     Erzeugung je Slot        [kWh]
	private final double[] feedInTariff; // öffentlich:  Einspeisevergütung        [ct/kWh]
	private final double[] gridBuyPrice; // öffentlich:  Rückkaufpreis bei Übermenge [ct/kWh]

	public SupplierAgent(double[] generation, double[] feedInTariff, double[] gridBuyPrice) {
		this.slots = generation.length;
		this.generation = generation.clone();
		this.feedInTariff = feedInTariff.clone();
		this.gridBuyPrice = gridBuyPrice.clone();
	}

	@Override
	public double utility(EnergyContract contract) {
		double profit = 0.0;
		for (int t = 0; t < slots; t++) {
			double delivered = contract.amount(t);
			double price     = contract.price(t);
			profit += delivered * price
			        + Math.max(generation[t] - delivered, 0.0) * feedInTariff[t]  // Rest-Überschuss einspeisen
			        - Math.max(delivered - generation[t], 0.0) * gridBuyPrice[t];  // Übermenge teuer zukaufen
		}
		return profit;
	}

	@Override
	public boolean vote(EnergyContract contract, EnergyContract proposal, double temperature) {
		double delta = utility(proposal) - utility(contract);
		if (delta >= 0.0) return true;            // Verbesserung: immer
		if (temperature <= 0.0) return false;     // strikt
		return Math.random() < Math.exp(delta / temperature); // Annealing
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
