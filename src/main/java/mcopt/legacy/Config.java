package mcopt.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings from {@code <game dir>/config/mcopt.properties}. Every improvement is on by default; the file exists so a
 * player can turn one off without touching the launcher. A {@code -Dmcopt.KEY} JVM flag wins over the file. The file is
 * written with the defaults and a comment per key the first time the game starts with the mod.
 */
public final class Config {
	private static final String FILE = "mcopt.properties";
	private static final String DEFAULTS = "# mcopt settings. These parts can be turned off here (set a key to false); the rest of the mod's changes are always on.\n"
		+ "# A -Dmcopt.<key>=... JVM flag wins over this file.\n"
		+ "\n"
		+ "# World generation noise in native code (macOS). Bit-exact: the same seed gives the same world.\n"
		+ "nativeNoise=true\n"
		+ "\n"
		+ "# Solid and cutout terrain drawn with Metal instead of OpenGL (macOS).\n"
		+ "metal=true\n"
		+ "\n"
		+ "# The frame counter in the top-left corner. Hidden with the HUD (F1) and while F3 is open.\n"
		+ "fpsHud=true\n";

	private static Properties props;

	private Config() {
	}

	private static synchronized Properties props() {
		if (props != null) return props;
		Properties p = new Properties();
		try {
			Path dir = FabricLoader.getInstance().getConfigDir();
			Path file = dir.resolve(FILE);
			if (Files.isRegularFile(file)) {
				try (InputStream in = Files.newInputStream(file)) {
					p.load(in);
				}
			} else {
				Files.createDirectories(dir);
				try (OutputStream out = Files.newOutputStream(file)) {
					out.write(DEFAULTS.getBytes(StandardCharsets.ISO_8859_1));
				}
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[mcopt] could not read config/" + FILE + " (" + e + "); using the defaults");
		}
		props = p;
		return p;
	}

	/** {@code -Dmcopt.KEY}, else the file's {@code KEY}, else {@code def}. */
	public static boolean flag(String key, boolean def) {
		String v = System.getProperty("mcopt." + key);
		if (v == null) v = props().getProperty(key);
		if (v == null) return def;
		return Boolean.parseBoolean(v.trim());
	}
}
