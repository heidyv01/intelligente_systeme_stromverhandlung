import java.util.Random;

/**
 * Customer-Agent: möchte seine Kosten minimieren und seinen Bedarf möglichst
 * direkt vom Supplier decken, statt teuer aus dem Netz zu beziehen
 * (Grid-Fallback vermeiden).
 *
 * Day-Ahead-Charakter: Beim Verhandeln kennt der Agent nur einen FORECAST seines
 * Bedarfs; der ECHTE Wert wird erst beim Settlement realisiert.
 *  - demand        : echter Bedarf je Slot [kWh] (zählt beim Settlement)
 *  - demandFc       : Forecast (Schätzung beim Verhandeln)
 *  - negotiationDem : Profil, auf dem verhandelt wird = Forecast, bei aktiver
 *                     Imbalance-Strafe um die Hedge-Fraktion erhöht (sichert mehr Bedarf, konservativer).
 * ÖFFENTLICH: retailPrice (Netzbezugspreis, Fallback für Defizit).
 *
 * Basis-Kosten je Slot (nach Batterie-Dispatch, net_t = delivered − demand):
 *   delivered·price + unmetDeficit·retailPrice + leftoverSurplus·surplusPenalty
 * Settlement zusätzlich: + imbalancePrice · Σ|Netz_real − Netz_forecast|  (Stufe 2).
 *
 * utility = −cost (Konvention: höher = besser).
 * Konzept-Notation: delivered=x_t, price=p_t, demand=d_t, retailPrice=r_t, surplusPenalty=s.
 */
public class CustomerAgent extends Agent {

	private final int slots;
	private final double[] demand;         // echter Bedarf (Settlement) [kWh]
	private final double[] demandFc;       // Forecast-Bedarf (Verhandlung) [kWh]
	private final double[] negotiationDem; // Profil für die Verhandlung (Forecast ggf. erhöht)
	private final double[] retailPrice;    // Netzbezugspreis [ct/kWh]
	private final double surplusPenalty;   // Strafkosten je nicht speicherbarer Überlieferungs-kWh [ct/kWh]
	private final Battery battery;
	private final Random rng;
	private final double imbalancePrice;   // α: Strafe je kWh unerwarteter Netzabweichung [ct/kWh]

	// Wiederverwendbare Puffer für den heißen Pfad (cost)
	private final double[] netScratch;
	private final double[] surplusScratch;
	private final double[] deficitScratch;

	public CustomerAgent(double[] demand, double[] demandForecast, double[] retailPrice,
	                     double surplusPenalty, Battery battery, Random rng,
	                     double imbalancePrice, double hedgeFraction) {
		this.slots = demand.length;
		this.demand = demand.clone();
		this.demandFc = demandForecast.clone();
		this.retailPrice = retailPrice.clone();
		this.surplusPenalty = surplusPenalty;
		this.battery = battery;
		this.rng = rng;
		this.imbalancePrice = imbalancePrice;
		this.negotiationDem = new double[slots];
		for (int t = 0; t < slots; t++) {
			// Hedge: konservativ mit mehr Bedarf rechnen (sichert gegen Imbalance-Strafe ab)
			this.negotiationDem[t] = demandFc[t] * (1.0 + hedgeFraction);
		}
		this.netScratch = new double[slots];
		this.surplusScratch = new double[slots];
		this.deficitScratch = new double[slots];
	}

	public Battery getBattery() {
		return battery;
	}

	/** Basis-Kosten für ein gegebenes Bedarfsprofil (ohne Imbalance-Strafe). Nutzt die Scratch-Puffer. */
	private double baseCost(EnergyContract c, double[] dem) {
		for (int t = 0; t < slots; t++) netScratch[t] = c.amount(t) - dem[t];
		battery.run(netScratch, surplusScratch, deficitScratch);
		double cost = 0.0;
		for (int t = 0; t < slots; t++) {
			cost += c.amount(t) * c.price(t)
			      + deficitScratch[t] * retailPrice[t]
			      + surplusScratch[t] * surplusPenalty;
		}
		return cost;
	}

	/** Unerwartetes Netz-Volumen [kWh]: Abweichung des Netzbezugs (Defizit) real vs. forecast. */
	private double imbalanceVolume(EnergyContract c) {
		double[] netR = new double[slots], sR = new double[slots], dR = new double[slots];
		double[] netF = new double[slots], sF = new double[slots], dF = new double[slots];
		for (int t = 0; t < slots; t++) {
			netR[t] = c.amount(t) - demand[t];
			netF[t] = c.amount(t) - demandFc[t];
		}
		battery.run(netR, sR, dR);
		battery.run(netF, sF, dF);
		double vol = 0.0;
		for (int t = 0; t < slots; t++) {
			vol += Math.abs(dR[t] - dF[t]); // Customer bezieht nur (Defizit), speist nicht ein
		}
		return vol;
	}

	// ---- Verhandlung (heißer Pfad): bewertet auf dem Verhandlungs-Profil ----
	/** Kosten auf dem Verhandlungs-Profil [ct] (niedriger = besser). */
	public double cost(EnergyContract contract) {
		return baseCost(contract, negotiationDem);
	}

	@Override
	public double utility(EnergyContract contract) {
		return -cost(contract);
	}

	@Override
	public boolean vote(EnergyContract contract, EnergyContract proposal, double temperature) {
		double delta = utility(proposal) - utility(contract);
		if (delta >= 0.0) return true;
		if (temperature <= 0.0) return false;
		return rng.nextDouble() < Math.exp(delta / temperature);
	}

	@Override
	public void delete(EnergyContract[] population) {
		int worst = -1;
		double worstUtility = Double.POSITIVE_INFINITY;
		for (int i = 0; i < population.length; i++) {
			if (population[i] == null) continue;
			double util = utility(population[i]);
			if (util < worstUtility) { worstUtility = util; worst = i; }
		}
		if (worst != -1) population[worst] = null;
	}

	/** No-Trade-Baseline auf dem Verhandlungs-Profil (für die Win-Win-Prüfung). */
	public double baseline() {
		double v = 0.0;
		for (int t = 0; t < slots; t++) v += negotiationDem[t] * retailPrice[t];
		return v;
	}

	// ---- Auswertung ----
	/** Erwartete Kosten (auf dem Forecast, ohne Strafe) – was der Agent beim Abschluss annimmt. */
	public double expectedCost(EnergyContract contract) {
		return baseCost(contract, demandFc);
	}

	/** Realisierte Kosten (echter Bedarf) inkl. Imbalance-Strafe. */
	public double settledCost(EnergyContract contract) {
		return baseCost(contract, demand) + imbalancePrice * imbalanceVolume(contract);
	}

	/** Prognosefehler-Volumen [kWh] = Σ|echt − forecast|. */
	public double forecastErrorKWh() {
		double e = 0.0;
		for (int t = 0; t < slots; t++) e += Math.abs(demand[t] - demandFc[t]);
		return e;
	}

	/** Bezahlte Imbalance-Strafe [ct] für diesen Kontrakt. */
	public double imbalancePenalty(EnergyContract contract) {
		return imbalancePrice * imbalanceVolume(contract);
	}

	/** Batterie-Dispatch auf dem ECHTEN Bedarf (Settlement-Sicht, für Metrics). */
	public Battery.Result dispatch(EnergyContract contract) {
		double[] net = new double[slots];
		for (int t = 0; t < slots; t++) net[t] = contract.amount(t) - demand[t];
		return battery.run(net);
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
