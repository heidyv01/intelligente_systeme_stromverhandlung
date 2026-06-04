import java.util.Random;

/**
 * Mediator der bilateralen Strom-Verhandlung.
 *
 * Wichtig: Der Mediator ist UNINFORMIERT über die Zielfunktionen der Agenten.
 * Er kennt nur die Struktur (Slot-Anzahl), die obere Mengenschranke und das
 * ÖFFENTLICHE Preisband [f_t, r_t]. Dadurch bleiben die Ziele der Agenten privat.
 *
 * Er erzeugt Vorschläge (Population ganzer Tageskontrakte) und reproduziert
 * akzeptierte Lösungen via Genetischem Algorithmus. Gegenüber der Permutations-
 * Basis sind die Operatoren auf REELLWERTIGE Vektoren umgestellt:
 *   - initContract      : zufällig, feasible (x in [0,X_max], p in [f_t,r_t])
 *   - constructProposal : Gauß-Mutation pro Gen + Clamping
 *   - crossover         : arithmetisches/BLX-Blending pro Gen + Clamping
 *
 * Zusätzlich gibt der Mediator den Annealing-Temperaturplan vor
 * ({@link #temperature(int)}) – der zentrale Stellhebel gegen frühe Stagnation.
 */
public class Mediator {

	private final int slots;
	private final double[] priceLow;  // f_t: öffentliche Untergrenze des Preisbands
	private final double[] priceHigh; // r_t: öffentliche Obergrenze des Preisbands
	private final double amountMax;   // X_max: obere Schranke der Liefermenge je Slot
	private final double mutationSigma; // Mutationsstärke als Anteil der jeweiligen Spanne
	private final double crossoverRate; // Anteil Crossover (sonst Mutation) bei Reproduktion
	private final double startTemperature;
	private final int    coolRounds;    // Temperatur fällt linear auf 0 bis zu dieser Runde
	private final Random rng;

	public Mediator(int slots, double[] priceLow, double[] priceHigh, double amountMax,
	                double mutationSigma, double crossoverRate,
	                double startTemperature, int coolRounds, long seed) {
		if (priceLow.length != slots || priceHigh.length != slots) {
			throw new IllegalArgumentException(
				"Preisband-Länge passt nicht zur Slot-Anzahl – Verhandlung nicht durchführbar.");
		}
		this.slots = slots;
		this.priceLow = priceLow.clone();
		this.priceHigh = priceHigh.clone();
		this.amountMax = amountMax;
		this.mutationSigma = mutationSigma;
		this.crossoverRate = crossoverRate;
		this.startTemperature = startTemperature;
		this.coolRounds = coolRounds;
		this.rng = new Random(seed);
	}

	public int getSlots() {
		return slots;
	}

	/** Annealing-Temperatur: linear fallend von startTemperature auf 0 bei coolRounds. */
	public double temperature(int round) {
		if (round >= coolRounds) return 0.0;
		return startTemperature * (1.0 - (double) round / coolRounds);
	}

	/** Feasibler Zufallskontrakt: x_t ∈ [0, X_max], p_t ∈ [f_t, r_t]. */
	public EnergyContract initContract() {
		EnergyContract c = new EnergyContract(slots);
		for (int t = 0; t < slots; t++) {
			c.setAmount(t, rng.nextDouble() * amountMax);
			c.setPrice(t, priceLow[t] + rng.nextDouble() * (priceHigh[t] - priceLow[t]));
		}
		return c;
	}

	/** Mutation: Gauß-Störung pro Gen, anschließend Clamping an die Grenzen. */
	public EnergyContract constructProposal(EnergyContract parent) {
		EnergyContract child = parent.copy();
		for (int t = 0; t < slots; t++) {
			double amt = child.amount(t) + rng.nextGaussian() * mutationSigma * amountMax;
			child.setAmount(t, clamp(amt, 0.0, amountMax));

			double range = priceHigh[t] - priceLow[t];
			double pr = child.price(t) + rng.nextGaussian() * mutationSigma * range;
			child.setPrice(t, clamp(pr, priceLow[t], priceHigh[t]));
		}
		return child;
	}

	/** Arithmetisches Crossover (BLX-artig): mischt zwei Eltern gen-weise. */
	public EnergyContract crossover(EnergyContract p1, EnergyContract p2) {
		EnergyContract child = new EnergyContract(slots);
		for (int t = 0; t < slots; t++) {
			double a = rng.nextDouble();
			double amt = a * p1.amount(t) + (1.0 - a) * p2.amount(t);
			child.setAmount(t, clamp(amt, 0.0, amountMax));

			double b = rng.nextDouble();
			double pr = b * p1.price(t) + (1.0 - b) * p2.price(t);
			child.setPrice(t, clamp(pr, priceLow[t], priceHigh[t]));
		}
		return child;
	}

	/** Füllt freie (null-)Plätze der Population via Crossover oder Mutation auf. */
	public EnergyContract[] contractReproduction(EnergyContract[] population) {
		int alive = countNonNull(population);
		for (int i = 0; i < population.length; i++) {
			if (population[i] != null) continue;
			int p1 = randomParent(population);
			if (alive > 1 && rng.nextDouble() < crossoverRate) {
				int p2;
				do { p2 = randomParent(population); } while (p2 == p1);
				population[i] = crossover(population[p1], population[p2]);
			} else {
				population[i] = constructProposal(population[p1]);
			}
		}
		return population;
	}

	private int randomParent(EnergyContract[] pop) {
		int idx;
		do { idx = rng.nextInt(pop.length); } while (pop[idx] == null);
		return idx;
	}

	private int countNonNull(EnergyContract[] pop) {
		int n = 0;
		for (EnergyContract c : pop) if (c != null) n++;
		return n;
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}
}
