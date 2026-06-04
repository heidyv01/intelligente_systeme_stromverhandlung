/**
 * Ein Tageskontrakt der bilateralen Strom-Verhandlung über T Slots.
 *
 * Repräsentation: flacher double[]-Vektor der Länge 2*T.
 *   Index 0 .. T-1    : Liefermengen x_t  [kWh]   (Supplier -> Customer)
 *   Index T .. 2T-1   : Preise        p_t  [ct/kWh]
 *
 * Bewusst "flach" gehalten, damit die GA-Operatoren des Mediators
 * (Crossover/Mutation) als generische Schleifen über die Gene laufen können,
 * während amount(t)/price(t) eine lesbare, domänennahe Sicht liefern.
 */
public class EnergyContract {

	private final int slots;
	private final double[] genes; // Länge 2*slots: [x_0..x_{T-1}, p_0..p_{T-1}]

	public EnergyContract(int slots) {
		this.slots = slots;
		this.genes = new double[2 * slots];
	}

	private EnergyContract(int slots, double[] genes) {
		this.slots = slots;
		this.genes = genes;
	}

	public int getSlots() {
		return slots;
	}

	/** Gelieferte Menge in Slot t [kWh]. */
	public double amount(int t) {
		return genes[t];
	}

	/** Preis in Slot t [ct/kWh]. */
	public double price(int t) {
		return genes[slots + t];
	}

	public void setAmount(int t, double v) {
		genes[t] = v;
	}

	public void setPrice(int t, double v) {
		genes[slots + t] = v;
	}

	/** Tiefe Kopie – Operatoren erzeugen neue Kontrakte, mutieren nie bestehende. */
	public EnergyContract copy() {
		return new EnergyContract(slots, genes.clone());
	}
}
