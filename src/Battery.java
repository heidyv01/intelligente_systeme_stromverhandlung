/**
 * Speicher (Batterie) eines Agenten mit greedy Lade-/Entlade-Dispatch.
 *
 * Der Dispatch koppelt die Slots eines Tages: Überschuss in einem Slot wird gespeichert
 * und in einem späteren Defizit-Slot wieder genutzt (statt ihn zu verlieren bzw. teuer
 * aus dem Netz zu beziehen).
 *
 * Eingabe: pro Slot ein Netto-Wert `net_t`
 *   net_t > 0  => Überschuss (kann geladen werden)
 *   net_t < 0  => Defizit    (kann aus dem Speicher gedeckt werden)
 * Ausgabe (je Slot):
 *   leftoverSurplus_t = Überschuss, der NICHT gespeichert wurde
 *   unmetDeficit_t    = Defizit, das der Speicher NICHT decken konnte
 *
 * Wirkungsgrad: round-trip-Effizienz η wird beim Entladen auf die abgegebene Energie
 * angewandt (Laden 1:1, Entnahme `draw`, nutzbar `draw·η`).
 *
 * Spezialfall capacity = 0 ({@link #none()}): leftoverSurplus = Überschuss,
 * unmetDeficit = Defizit – also exakt das slot-unabhängige Modell ohne Speicher.
 */
public class Battery {

	public final double capacity;            // kWh
	public final double maxPower;            // kWh je Slot (Lade-/Entladeleistung)
	public final double roundTripEfficiency; // η in (0,1], beim Entladen angewandt
	public final double initialSoc;          // Anfangs-Ladestand [kWh]

	public Battery(double capacity, double maxPower, double roundTripEfficiency, double initialSoc) {
		this.capacity = capacity;
		this.maxPower = maxPower;
		this.roundTripEfficiency = roundTripEfficiency;
		this.initialSoc = initialSoc;
	}

	/** Keine Batterie (Kapazität 0) – Dispatch fällt auf das slot-unabhängige Modell zurück. */
	public static Battery none() {
		return new Battery(0.0, 0.0, 1.0, 0.0);
	}

	public boolean isPresent() {
		return capacity > 0.0;
	}

	/**
	 * Greedy-Dispatch in vorhandene Puffer (allokationsfrei – für den heißen Pfad).
	 * Füllt outSurplus/outDeficit je Slot und gibt den End-Ladestand zurück.
	 */
	public double run(double[] net, double[] outSurplus, double[] outDeficit) {
		double soc = Math.min(initialSoc, capacity);
		for (int t = 0; t < net.length; t++) {
			if (net[t] >= 0.0) {
				double charge = Math.min(net[t], Math.min(capacity - soc, maxPower));
				soc += charge;
				outSurplus[t] = net[t] - charge;
				outDeficit[t] = 0.0;
			} else {
				double deficit = -net[t];
				double draw = Math.min(Math.min(soc, maxPower), deficit / roundTripEfficiency);
				double covered = draw * roundTripEfficiency;
				soc -= draw;
				outSurplus[t] = 0.0;
				outDeficit[t] = deficit - covered;
			}
		}
		return soc;
	}

	/** Bequeme Variante mit frischen Arrays (für die Auswertung, selten aufgerufen). */
	public Result run(double[] net) {
		double[] surplus = new double[net.length];
		double[] deficit = new double[net.length];
		double soc = run(net, surplus, deficit);
		return new Result(surplus, deficit, soc);
	}

	/** Ergebnis eines Dispatch-Laufs. */
	public static class Result {
		public final double[] leftoverSurplus;
		public final double[] unmetDeficit;
		public final double finalSoc;

		public Result(double[] leftoverSurplus, double[] unmetDeficit, double finalSoc) {
			this.leftoverSurplus = leftoverSurplus;
			this.unmetDeficit = unmetDeficit;
			this.finalSoc = finalSoc;
		}

		public double totalLeftoverSurplus() {
			double s = 0.0;
			for (double v : leftoverSurplus) s += v;
			return s;
		}

		public double totalUnmetDeficit() {
			double s = 0.0;
			for (double v : unmetDeficit) s += v;
			return s;
		}
	}
}
