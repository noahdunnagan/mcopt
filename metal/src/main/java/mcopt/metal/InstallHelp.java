package mcopt.metal;

import java.awt.GraphicsEnvironment;
import javax.swing.JOptionPane;

/** The jar's Main-Class: someone ran (double-clicked) the mod jar, so explain that it's a mod and how to install it. */
public final class InstallHelp {
	private InstallHelp() {
	}

	public static void main(String[] args) {
		String version = InstallHelp.class.getPackage().getImplementationVersion();
		String text = "mcopt Metal" + (version == null ? "" : " " + version) + " is a Fabric mod for Minecraft: Java Edition 26.3, not an app to open.\n"
			+ "\n"
			+ "To install it (Apple Silicon Mac, macOS 26 or later):\n"
			+ "1. Install Fabric for Minecraft 26.3: https://fabricmc.net/use/installer/\n"
			+ "2. Put this jar and Sodium 0.9.2 or 0.9.3 for 26.3 in your mods folder:\n"
			+ "   ~/Library/Application Support/minecraft/mods (official launcher)\n"
			+ "3. Start Minecraft with the Fabric profile.\n"
			+ "\n"
			+ "README.txt, next to this jar in the download, has the details.";
		System.out.println(text);
		if (!GraphicsEnvironment.isHeadless()) {
			JOptionPane.showMessageDialog(null, text, "mcopt Metal", JOptionPane.INFORMATION_MESSAGE);
		}
	}
}
