package mcopt.legacy.metal;

/** A chunk VertexBuffer's mesh in MetalTerrain's vertex pages (added to VertexBuffer by MetalMeshMixin). */
public interface MetalMesh {
	/** The range (page << 21 | first quad), or -1 when the buffer has no Metal mesh. */
	int mcopt$range();

	/** The range's size class: 2^class quads. */
	int mcopt$class();

	/** Quads in the mesh. */
	int mcopt$count();

	void mcopt$set(int range, int sizeClass, int count);

	/**
	 * Quads per facing group (+X, -X, +Y, -Y, +Z, -Z, other) in mesh order, then the six axis groups' outermost planes
	 * toward the camera side (float bits, section-relative): + groups their lowest plane, - groups their highest.
	 */
	int[] mcopt$groups();

	/**
	 * The mesh's draw units as MetalTerrain writes them for the GPU, UNIT_INTS ints each, in mesh order (each group cut
	 * into runs of UNIT_QUADS quads).
	 */
	int[] mcopt$units();

	void mcopt$units(int[] units);

	/** Where the units sit in their layer's unit buffer: first slot (-1 none), 2^class slots, units in use. */
	int mcopt$slot();

	int mcopt$slotClass();

	int mcopt$unitCount();

	/** The layer (0 solid, 1 cutout mipped, 2 cutout) the mesh was uploaded for. */
	int mcopt$layer();

	/** True while the units are written into the unit buffer (drawn); false while the slots hold empty units. */
	boolean mcopt$active();

	void mcopt$slots(int layer, int slot, int slotClass, int unitCount, boolean active);
}
