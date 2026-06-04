/**
 * Basis-Agent der bilateralen Strom-Verhandlung.
 *
 * Jeder Agent bewertet Kontrakte über eine PRIVATE Zielfunktion, die der
 * Mediator nicht kennt. Konvention: {@link #utility(EnergyContract)} liefert
 * einen Nutzen, bei dem GRÖSSER stets BESSER ist (Supplier: Gewinn,
 * Customer: negative Kosten) – dadurch ist die Abstimmungs- und
 * Annealing-Logik für beide Agenten identisch.
 */
public abstract class Agent {

	/** Privater Nutzen des Kontrakts (höher = besser). Nur dem Agenten bekannt. */
	public abstract double utility(EnergyContract contract);

	/**
	 * Stimmt über einen Vorschlag relativ zum aktuellen Kontrakt ab.
	 *
	 * Verbesserungen werden immer akzeptiert. Verschlechterungen werden mit
	 * Wahrscheinlichkeit exp(Δ/temperature) akzeptiert (Δ &lt; 0) – das ist der
	 * Annealing-Mechanismus gegen frühe Stagnation. temperature &lt;= 0 ⇒ strikt.
	 *
	 * @return true, wenn der Vorschlag akzeptiert wird.
	 */
	public abstract boolean vote(EnergyContract contract, EnergyContract proposal, double temperature);

	/** Entfernt den für diesen Agenten schlechtesten Kontrakt aus der Population (setzt null). */
	public abstract void delete(EnergyContract[] population);

	/** Gibt die domänennahe Kennzahl des Kontrakts auf der Konsole aus (Profit bzw. Kosten). */
	public abstract void printUtility(EnergyContract contract);

	/** Anzahl der Slots, die dieser Agent erwartet. */
	public abstract int getSlots();
}
