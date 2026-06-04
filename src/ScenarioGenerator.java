import java.util.Random;

/**
 * Erzeugt reproduzierbare synthetische Tagesprofile für einen Tag à T Slots.
 *
 *  - generation g_t : PV-Erzeugungsüberschuss des Suppliers  [kWh] (Glocke, mittags Peak)
 *  - demand     d_t : Bedarf des Customers                    [kWh] (Morgen-/Abendpeak)
 *  - feedIn     f_t : Einspeisevergütung (Grid)               [ct/kWh] (niedrig, flach)
 *  - retail     r_t : Netzbezugspreis (Grid, TOU)             [ct/kWh] (hoch, Peak teurer)
 *
 * g_t und d_t sind PRIVAT (gehen nur in den jeweiligen Agenten),
 * f_t und r_t sind ÖFFENTLICHE Marktdaten (Mediator + beide Agenten dürfen sie kennen).
 *
 * Reiz des Szenarios: PV-Überschuss (mittags) und Last-Peak (abends) liegen zeitlich
 * auseinander -> nicht-triviales Matching über die Slots.
 */
public class ScenarioGenerator {

	public final int slots;
	public final double[] generation; // g_t [kWh]   (privat Supplier)
	public final double[] demand;     // d_t [kWh]   (privat Customer)
	public final double[] feedIn;     // f_t [ct/kWh](öffentlich)
	public final double[] retail;     // r_t [ct/kWh](öffentlich)

	public ScenarioGenerator(int slots, long seed) {
		this.slots = slots;
		this.generation = new double[slots];
		this.demand = new double[slots];
		this.feedIn = new double[slots];
		this.retail = new double[slots];
		build(seed);
	}

	private void build(long seed) {
		Random rng = new Random(seed);
		for (int t = 0; t < slots; t++) {
			double hour = t * 24.0 / slots;

			// PV-Glocke: 0 nachts, Peak ~12 Uhr (quadriert -> spitzer)
			double pv = Math.max(0.0, Math.sin(Math.PI * (hour - 6.0) / 12.0));
			generation[t] = round1(10.0 * pv * pv + noise(rng, 0.3));

			// Last: Grundlast + Morgenpeak (~7:30) + größerer Abendpeak (~19:30)
			double morning = gauss(hour, 7.5, 1.5);
			double evening = gauss(hour, 19.5, 2.0);
			demand[t] = round1(0.8 + 4.0 * morning + 6.0 * evening + noise(rng, 0.2));

			// Einspeisevergütung: niedrig und flach
			feedIn[t] = 8.0;

			// Bezugstarif (Time-of-Use): teurer in den Lastspitzen
			double peak = Math.max(morning, evening);
			retail[t] = round1(25.0 + 12.0 * peak);
		}
	}

	private static double gauss(double x, double mu, double sigma) {
		double z = (x - mu) / sigma;
		return Math.exp(-0.5 * z * z);
	}

	private static double noise(Random rng, double amp) {
		return amp * (rng.nextDouble() - 0.5);
	}

	/** Auf 1 Nachkommastelle runden, nicht-negativ. */
	private static double round1(double v) {
		return Math.max(0.0, Math.round(v * 10.0) / 10.0);
	}
}
