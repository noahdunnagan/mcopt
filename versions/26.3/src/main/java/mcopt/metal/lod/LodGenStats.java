package mcopt.metal.lod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * -Dmcopt.lod.chunkLag=DIR: whether the game's own chunks keep up, next to the far terrain's generation, for the contention
 * experiments (far-terrain workers vs the integrated server's chunk generation on the same cores). Every 6th frame, on the
 * render thread: the chunks whose centre lies within the render distance of the camera, how many of them the client has not
 * received yet (the server hasn't generated or sent them), how many of those lie ahead of the camera's motion, the nearest
 * missing one; and the far terrain's tiles generated so far and their generation time. One line of DIR/chunklag.csv per
 * sample.
 */
final class LodGenStats {
	private LodGenStats() {
	}

	static final String DIR = System.getProperty("mcopt.lod.chunkLag");
	static final boolean ON = DIR != null && !DIR.isBlank();
	private static BufferedWriter csv;
	private static long frames;
	private static double lastX = Double.NaN, lastZ;
	private static long lastT;
	private static double velX, velZ;

	/** Chunks whose centre lies within the render distance of the camera that the client doesn't have. */
	static int missing(ClientLevel level, double camX, double camZ, int rd) {
		int pcx = (int) Math.floor(camX) >> 4, pcz = (int) Math.floor(camZ) >> 4;
		double r2 = rd * 16.0 * rd * 16.0;
		int missing = 0;
		var chunks = level.getChunkSource();
		for (int dz = -rd - 1; dz <= rd + 1; dz++) {
			for (int dx = -rd - 1; dx <= rd + 1; dx++) {
				double ox = (pcx + dx) * 16 + 8 - camX, oz = (pcz + dz) * 16 + 8 - camZ;
				if (ox * ox + oz * oz > r2) continue;
				if (!chunks.hasChunk(pcx + dx, pcz + dz)) missing++;
			}
		}
		return missing;
	}

	static synchronized void frame(ClientLevel level, LodField field, double camX, double camZ, int rd) {
		if (level == null || frames++ % 6 != 0) return;
		long now = System.nanoTime();
		if (!Double.isNaN(lastX) && now > lastT) {
			double dt = (now - lastT) / 1e9, a = Math.min(1, dt / 0.25);
			velX += ((camX - lastX) / dt - velX) * a;
			velZ += ((camZ - lastZ) / dt - velZ) * a;
		}
		lastX = camX;
		lastZ = camZ;
		lastT = now;
		double speed = Math.hypot(velX, velZ);
		double dirX = speed > 1 ? velX / speed : 0, dirZ = speed > 1 ? velZ / speed : 0;
		int pcx = (int) Math.floor(camX) >> 4, pcz = (int) Math.floor(camZ) >> 4;
		double r = rd * 16.0, r2 = r * r;
		int total = 0, missing = 0, ahead = 0, aheadTotal = 0;
		double nearest = Double.MAX_VALUE;
		var chunks = level.getChunkSource();
		for (int dz = -rd - 1; dz <= rd + 1; dz++) {
			for (int dx = -rd - 1; dx <= rd + 1; dx++) {
				int cx = pcx + dx, cz = pcz + dz;
				double ox = cx * 16 + 8 - camX, oz = cz * 16 + 8 - camZ;
				double d2 = ox * ox + oz * oz;
				if (d2 > r2) continue;
				total++;
				boolean front = ox * dirX + oz * dirZ > 0;
				if (front) aheadTotal++;
				if (chunks.hasChunk(cx, cz)) continue;
				missing++;
				if (front) ahead++;
				nearest = Math.min(nearest, Math.sqrt(d2));
			}
		}
		try {
			if (csv == null) {
				Files.createDirectories(Path.of(DIR));
				csv = Files.newBufferedWriter(Path.of(DIR).resolve("chunklag.csv"));
				Runtime.getRuntime().addShutdownHook(new Thread(LodGenStats::close, "mcopt-lod-chunklag-close"));
				csv.write("epochMs,camX,camZ,speed,rd,inRd,missing,aheadInRd,aheadMissing,nearestMissing,tilesGenerated,tilesLoaded,genNanos,queued,pending,pressure,paused\n");
			}
			csv.write(System.currentTimeMillis() + "," + Math.round(camX) + "," + Math.round(camZ) + "," + Math.round(speed * 10) / 10.0 + "," + rd + "," + total + ","
				+ missing + "," + aheadTotal + "," + ahead + "," + (nearest == Double.MAX_VALUE ? -1 : Math.round(nearest)) + "," + field.generated.get() + ","
				+ field.loaded.get() + "," + field.genNanos.get() + "," + field.queued() + "," + field.pendingJobs() + "," + (LodYield.pressure ? 1 : 0) + ","
				+ LodYield.pausedNow() + "\n");
			if (frames % 600 == 1) csv.flush();
		} catch (IOException e) {
			System.out.println("mcopt-lod: chunkLag: " + e);
		}
	}

	static synchronized void close() {
		try {
			if (csv != null) csv.flush();
		} catch (IOException ignored) {
		}
	}
}
