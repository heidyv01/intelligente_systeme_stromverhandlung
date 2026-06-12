import java.util.Random;

/**
 * Supplier-Agent: möchte seinen Gewinn maximieren und seinen Erzeugungs-
 * überschuss möglichst teuer an den Customer verkaufen, statt ihn billig
 * ins Netz einzuspeisen (Grid-Fallback vermeiden).
 *
 * Day-Ahead-Charakter: Beim Verhandeln kennt der Agent nur einen FORECAST seiner
 * Erzeugung; der ECHTE Wert wird erst beim Settlement realisiert.
 *  - generation     : echte Erzeugung je Slot [kWh] (zählt beim Settlement)
 *  - generationFc    : Forecast (Schätzung beim Verhandeln)
 *  - negotiationGen  : Profil, auf dem verhandelt wird = Forecast, bei aktiver
 *                      Imbalance-Strafe um die Hedge-Fraktion abgesichert (konservativer).
 * ÖFFENTLICH: feedInTariff (Einspeisevergütung), gridBuyPrice (Netzbezug/Rückkauf).
 *
 * Basis-Profit je Slot (nach Batterie-Dispatch, net_t = gen − delivered):
 *   delivered·price + leftoverSurplus·feedInTariff − unmetDeficit·gridBuyPrice
 * Settlement zusätzlich: − imbalancePrice · Σ|Netz_real − Netz_forecast|  (Stufe 2).
 *
 * Konzept-Notation: delivered=x_t, price=p_t, generation=g_t, feedInTariff=f_t, gridBuyPrice=r_t.
 */
public class SupplierAgent extends Agent {

	private final int slots;
	private final double[] generation;     // echte Erzeugung (Settlement) [kWh]
	private final double[] generationFc;   // Forecast-Erzeugung (Verhandlung) [kWh]
	private final double[] negotiationGen; // Profil für die Verhandlung (Forecast ggf. abgesichert)
	private final double[] feedInTariff;   // Einspeisevergütung [ct/kWh]
	private final double[] gridBuyPrice;   // Rückkaufpreis bei Übermenge [ct/kWh]
	private final Battery battery;
	private final Random rng;
	private final double imbalancePrice;   // α: Strafe je kWh unerwarteter Netzabweichung [ct/kWh]

	// Wiederverwendbare Puffer für den heißen Pfad (utility)
	private final double[] netScratch;
	private final double[] surplusScratch;
	private final double[] deficitScratch;

	public SupplierAgent(double[] generation, double[] generationForecast,
	                     double[] feedInTariff, double[] gridBuyPrice,
	                     Battery battery, Random rng,
	                     double imbalancePrice, double hedgeFraction) {
		this.slots = generation.length;
		this.generation = generation.clone();
		this.generationFc = generationForecast.clone();
		this.feedInTariff = feedInTariff.clone();
		this.gridBuyPrice = gridBuyPrice.clone();
		this.battery = battery;
		this.rng = rng;
		this.imbalancePrice = imbalancePrice;
		this.negotiationGen = new double[slots];
		for (int t = 0; t < slots; t++) {
			// Hedge: konservativ mit weniger Erzeugung rechnen (sichert gegen Imbalance-Strafe ab)
			this.negotiationGen[t] = Math.max(0.0, generationFc[t] * (1.0 - hedgeFraction));
		}
		this.netScratch = new double[slots];
		this.surplusScratch = new double[slots];
		this.deficitScratch = new double[slots];
	}

	public Battery getBattery() {
		return battery;
	}

	/** Basis-Profit für ein gegebenes Erzeugungsprofil (ohne Imbalance-Strafe). Nutzt die Scratch-Puffer. */
	private double baseProfit(EnergyContract c, double[] gen) {
		for (int t = 0; t < slots; t++) netScratch[t] = gen[t] - c.amount(t);
		battery.run(netScratch, surplusScratch, deficitScratch);
		double profit = 0.0;
		for (int t = 0; t < slots; t++) {
			profit += c.amount(t) * c.price(t)
			        + surplusScratch[t] * feedInTariff[t]
			        - deficitScratch[t] * gridBuyPrice[t];
		}
		return profit;
	}

	/** Unerwartetes Netz-Volumen [kWh]: Abweichung der Netto-Netzposition real vs. forecast. */
	private double imbalanceVolume(EnergyContract c) {
		double[] netR = new double[slots], sR = new double[slots], dR = new double[slots];
		double[] netF = new double[slots], sF = new double[slots], dF = new double[slots];
		for (int t = 0; t < slots; t++) {
			netR[t] = generation[t]   - c.amount(t);
			netF[t] = generationFc[t] - c.amount(t);
		}
		battery.run(netR, sR, dR);
		battery.run(netF, sF, dF);
		double vol = 0.0;
		for (int t = 0; t < slots; t++) {
			vol += Math.abs((sR[t] - dR[t]) - (sF[t] - dF[t])); // Netz = Einspeisung − Bezug
		}
		return vol;
	}

	// ---- Verhandlung (heißer Pfad): bewertet auf dem Verhandlungs-Profil ----
	@Override
	public double utility(EnergyContract contract) {
		return baseProfit(contract, negotiationGen);
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
		double worstProfit = Double.POSITIVE_INFINITY;
		for (int i = 0; i < population.length; i++) {
			if (population[i] == null) continue;
			double profit = utility(population[i]);
			if (profit < worstProfit) { worstProfit = profit; worst = i; }
		}
		if (worst != -1) population[worst] = null;
	}

	/** No-Trade-Baseline auf dem Verhandlungs-Profil (für die Win-Win-Prüfung). */
	public double baseline() {
		double v = 0.0;
		for (int t = 0; t < slots; t++) v += negotiationGen[t] * feedInTariff[t];
		return v;
	}

	// ---- Auswertung ----
	/** Erwarteter Profit (auf dem Forecast, ohne Strafe) – was der Agent beim Abschluss annimmt. */
	public double expectedProfit(EnergyContract contract) {
		return baseProfit(contract, generationFc);
	}

	/** Realisierter Profit (echte Erzeugung) inkl. Imbalance-Strafe. */
	public double settledProfit(EnergyContract contract) {
		return baseProfit(contract, generation) - imbalancePrice * imbalanceVolume(contract);
	}

	/** Prognosefehler-Volumen [kWh] = Σ|echt − forecast|. */
	public double forecastErrorKWh() {
		double e = 0.0;
		for (int t = 0; t < slots; t++) e += Math.abs(generation[t] - generationFc[t]);
		return e;
	}

	/** Bezahlte Imbalance-Strafe [ct] für diesen Kontrakt. */
	public double imbalancePenalty(EnergyContract contract) {
		return imbalancePrice * imbalanceVolume(contract);
	}

	/** Batterie-Dispatch auf der ECHTEN Erzeugung (Settlement-Sicht, für Metrics). */
	public Battery.Result dispatch(EnergyContract contract) {
		double[] net = new double[slots];
		for (int t = 0; t < slots; t++) net[t] = generation[t] - contract.amount(t);
		return battery.run(net);
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
