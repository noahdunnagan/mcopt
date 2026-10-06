package mcopt.metal;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Debug instrument, -Dmcopt.metal.probe=N: records submit N's Sodium terrain draws, replays their quads into an id buffer
 * (mcprobe.m) and prints how many reached a pixel, and why the rest didn't. It prices culling before anyone builds one.
 */
final class MetalProbe {
	static final long SUBMIT = Long.getLong("mcopt.metal.probe", -1);
	private static final String[] KINDS = {"solid", "cutout", "translucent"};
	private static final int[] CLUSTERS = {16, 32, 64, 128, 256};

	private record Draw(long vb, long offset, int quads, int quadBase, float x, float y, float z, int kind, boolean cull, int section) {
	}

	private final List<Draw> draws = new ArrayList<>();
	/** With MetalTerrain culling: the quads it kept this frame, as arena handle -> first vertices; null without. */
	private @Nullable Map<Long, Set<Integer>> kept;
	private long globals, globalsOffset, atlas, atlasSampler;
	private int depthCompare, width, height, totalQuads;
	/** The region's camera-relative origin, from the latest push constants. */
	private float x, y, z;

	void pushConstants(long address) {
		this.x = MemoryUtil.memGetFloat(address);
		this.y = MemoryUtil.memGetFloat(address + 4);
		this.z = MemoryUtil.memGetFloat(address + 8);
	}

	/** One Sodium region multi-draw: quads are 4 consecutive vertices from each vertexOffset, indexCount / 6 of them. */
	void record(MetalPipeline pipeline, MetalBuffer vb, long vbOffset, MetalBuffer globals, long globalsOffset, long atlas, long atlasSampler,
		IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount, int width, int height) {
		int kind = pipeline.name.contains("translucent") ? 2 : pipeline.name.contains("cutout") ? 1 : 0;
		for (int i = 0; i < drawCount; i++) {
			int quads = indexCounts.get(indexCounts.position() + i) / 6;
			long offset = vbOffset + vertexOffsets.get(vertexOffsets.position() + i) * 20L;
			int section = quads == 0 ? 0 : MemoryUtil.memGetByte(vb.address + offset + 19) & 0xFF;
			this.draws.add(new Draw(vb.handle, offset, quads, this.totalQuads, this.x, this.y, this.z, kind, pipeline.cull, section));
			this.totalQuads += quads;
		}
		this.globals = globals.handle;
		this.globalsOffset = globalsOffset;
		this.atlas = atlas;
		this.atlasSampler = atlasSampler;
		this.depthCompare = pipeline.depthCompare;
		this.width = width;
		this.height = height;
	}

	void run(long ctx, List<MetalTerrain.Drawn> culled) {
		if (this.draws.isEmpty()) {
			System.out.println("mcopt-probe: no terrain draws in submit " + SUBMIT);
			return;
		}
		this.kept = keptQuads(culled);
		int words = this.totalQuads / 32 + 1;
		long drawsPtr = MemoryUtil.nmemAlloc(this.draws.size() * 48L), bits = MemoryUtil.nmemAlloc(words * 4L), classes = MemoryUtil.nmemAlloc(this.totalQuads + 1L);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (int i = 0; i < this.draws.size(); i++) {
				Draw d = this.draws.get(i);
				long p = drawsPtr + i * 48L;
				MemoryUtil.memPutFloat(p, d.x);
				MemoryUtil.memPutFloat(p + 4, d.y);
				MemoryUtil.memPutFloat(p + 8, d.z);
				MemoryUtil.memPutInt(p + 12, d.quadBase);
				MemoryUtil.memPutInt(p + 16, d.quads);
				MemoryUtil.memPutInt(p + 20, d.kind);
				MemoryUtil.memPutLong(p + 24, d.vb);
				MemoryUtil.memPutLong(p + 32, d.offset);
				MemoryUtil.memPutInt(p + 40, d.cull ? 1 : 0);
			}
			long pixels = stack.ncalloc(4, 1, 4), err = stack.nmalloc(1, 1024);
			if (Native.probe(ctx, drawsPtr, this.draws.size(), this.totalQuads, this.globals, this.globalsOffset, this.atlas, this.atlasSampler, this.width, this.height,
				this.depthCompare, 1, bits, classes, pixels, err, 1024) != 0) {
				System.out.println("mcopt-probe: failed: " + MemoryUtil.memUTF8(err));
				return;
			}
			this.report(bits, classes, MemoryUtil.memGetInt(pixels));
		} finally {
			MemoryUtil.nmemFree(drawsPtr);
			MemoryUtil.nmemFree(bits);
			MemoryUtil.nmemFree(classes);
		}
	}

	private void report(long bits, long classes, int pixels) {
		long[][] byKind = new long[3][6]; // offscreen, backface, no pixel center, occluded, visible, visible-but-classified-culled (must be 0)
		Map<List<Object>, long[]> sections = new HashMap<>(); // (region origin, section) -> {quads, visible}; regions can share a vertex buffer
		long visible = 0, emptyDraws = 0, emptyDrawQuads = 0;
		long[] clusters = new long[CLUSTERS.length], emptyClusterQuads = new long[CLUSTERS.length];
		long[] culling = new long[4]; // solid + cutout quads, kept by MetalTerrain, kept but showing nothing, showing yet culled
		for (Draw d : this.draws) {
			long[] section = sections.computeIfAbsent(List.of(d.x, d.y, d.z, d.section), k -> new long[2]);
			long drawVisible = section[1];
			for (int c = 0; c < CLUSTERS.length; c++) {
				for (int start = 0; start < d.quads; start += CLUSTERS[c]) {
					int end = Math.min(d.quads, start + CLUSTERS[c]);
					clusters[c]++;
					if (!anyBit(bits, d.quadBase + start, d.quadBase + end)) emptyClusterQuads[c] += end - start;
				}
			}
			for (int q = 0; q < d.quads; q++) {
				int id = d.quadBase + q;
				boolean seen = (MemoryUtil.memGetInt(bits + (id >>> 5) * 4L) >>> (id & 31) & 1) != 0;
				int cls = MemoryUtil.memGetByte(classes + id);
				long[] k = byKind[d.kind];
				if (cls < 3) {
					k[cls]++;
					if (seen) k[5]++;
				} else k[seen ? 4 : 3]++;
				if (seen) {
					visible++;
					section[1]++;
				}
				section[0]++;
				if (this.kept != null && d.kind < 2) {
					boolean isKept = this.kept.getOrDefault(d.vb, Set.of()).contains((int) (d.offset / 20 + q * 4L));
					culling[0]++;
					if (isKept) culling[1]++;
					if (isKept && !seen) culling[2]++;
					if (seen && !isKept) culling[3]++;
				}
			}
			if (section[1] == drawVisible) {
				emptyDraws++;
				emptyDrawQuads += d.quads;
			}
		}
		System.out.printf("mcopt-probe: submit %d, %dx%d, %d draws, %d quads, %d visible (%.1f%%), %d pixels covered (%.1f px per visible quad)%n", SUBMIT,
			this.width, this.height, this.draws.size(), this.totalQuads, visible, 100.0 * visible / this.totalQuads, pixels, (double) pixels / Math.max(1, visible));
		for (int kind = 0; kind < 3; kind++) {
			long[] k = byKind[kind];
			long n = k[0] + k[1] + k[2] + k[3] + k[4];
			if (n == 0) continue;
			System.out.printf("mcopt-probe: %-11s %8d quads: off screen %5.1f%%, back-facing %5.1f%%, no pixel center %5.1f%%, occluded %5.1f%%, visible %5.1f%% (%d visible yet classified culled)%n",
				KINDS[kind], n, 100.0 * k[0] / n, 100.0 * k[1] / n, 100.0 * k[2] / n, 100.0 * k[3] / n, 100.0 * k[4] / n, k[5]);
		}
		long emptySections = 0, emptySectionQuads = 0;
		for (long[] s : sections.values()) {
			if (s[1] == 0) {
				emptySections++;
				emptySectionQuads += s[0];
			}
		}
		System.out.printf("mcopt-probe: culling whole units that show nothing would skip: sections %.1f%% of quads (%d of %d), Sodium draws %.1f%% (%d of %d)%n",
			100.0 * emptySectionQuads / this.totalQuads, emptySections, sections.size(), 100.0 * emptyDrawQuads / this.totalQuads, emptyDraws, this.draws.size());
		StringBuilder line = new StringBuilder("mcopt-probe: ... clusters of");
		for (int c = 0; c < CLUSTERS.length; c++) line.append(String.format(" %d quads %.1f%% (of %d),", CLUSTERS[c], 100.0 * emptyClusterQuads[c] / this.totalQuads, clusters[c]));
		System.out.println(line);
		if (this.kept != null) {
			System.out.printf("mcopt-probe: terrain culling kept %d of %d solid+cutout quads (%.1f%%), %d of them showing nothing; visible quads it culled: %d (must be 0)%n",
				culling[1], culling[0], 100.0 * culling[1] / Math.max(1, culling[0]), culling[2], culling[3]);
		}
	}

	private static @Nullable Map<Long, Set<Integer>> keptQuads(List<MetalTerrain.Drawn> culled) {
		if (culled.isEmpty()) return null;
		Map<Long, Set<Integer>> kept = new HashMap<>();
		for (MetalTerrain.Drawn d : culled) {
			int n = 64 * MemoryUtil.memGetInt(d.work().address + d.args() + 4);
			for (int i = 0; i < n; i++) {
				long at = d.work().address + d.quads() + i * 8L;
				int first = MemoryUtil.memGetInt(at);
				if (first == -1) continue; // padding
				kept.computeIfAbsent(d.arenas()[MemoryUtil.memGetInt(at + 4) >>> 6 & 7].handle, k -> new HashSet<>()).add(first);
			}
		}
		return kept;
	}

	private static boolean anyBit(long bits, int from, int to) {
		for (int id = from; id < to; id++) {
			if ((MemoryUtil.memGetInt(bits + (id >>> 5) * 4L) >>> (id & 31) & 1) != 0) return true;
		}
		return false;
	}
}
