/**
 * Customer-Agent: möchte seine Kosten minimieren und seinen Bedarf möglichst
 * direkt vom Supplier decken, statt teuer aus dem Netz zu beziehen
 * (Grid-Fallback vermeiden).
 *
 * PRIVAT:     demand      – Energiebedarf je Slot [kWh]
 * ÖFFENTLICH: retailPrice – Netzbezugspreis (Fallback für Defizit) [ct/kWh]
 *
 * Kostenfunktion, je Slot aufsummiert:
 *   cost += delivered · price
 *         + max(demand − delivered, 0) · retailPrice     // Defizit teuer aus dem Netz
 *         + max(delivered − demand, 0) · surplusPenalty  // unerwünschte Übermenge (Default 0)
 *
 * utility = −cost (Konvention: höher = besser).
 * Konzept-Notation: delivered=x_t, price=p_t, demand=d_t, retailPrice=r_t, surplusPenalty=s.
 */
public class CustomerAgent extends Agent {

	private final int slots;
	private final double[] demand;        // privat:    Bedarf je Slot      [kWh]
	private final double[] retailPrice;   // öffentlich: Netzbezugspreis     [ct/kWh]
	private final double surplusPenalty;  // Strafkosten je überschüssiger kWh [ct/kWh]

	public CustomerAgent(double[] demand, double[] retailPrice, double surplusPenalty) {
		this.slots = demand.length;
		this.demand = demand.clone();
		this.retailPrice = retailPrice.clone();
		this.surplusPenalty = surplusPenalty;
	}

	/** Gesamtkosten des Kontrakts [ct] (niedriger = besser). */
	public double cost(EnergyContract contract) {
		double cost = 0.0;
		for (int t = 0; t < slots; t++) {
			double delivered = contract.amount(t);
			double price     = contract.price(t);
			cost += delivered * price
			      + Math.max(demand[t] - delivered, 0.0) * retailPrice[t]   // Defizit teuer aus Netz
			      + Math.max(delivered - demand[t], 0.0) * surplusPenalty;   // Übermenge unerwünscht
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
		return Math.random() < Math.exp(delta / temperature); // Annealing
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
