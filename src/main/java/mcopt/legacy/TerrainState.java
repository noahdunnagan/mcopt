package mcopt.legacy;

/** Shared between the terrain-setup mixins. Touched only on the render thread. */
public final class TerrainState {
	/** Bumped whenever a render section moves to a new position. */
	public static int sectionMoves;

	private TerrainState() {
	}
}
