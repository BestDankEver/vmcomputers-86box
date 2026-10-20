package mcvmcomputers.client.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import org.apache.commons.lang3.SystemUtils;
import org.lwjgl.glfw.GLFW;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import mcvmcomputers.client.ClientMod;
import net.minecraft.client.MinecraftClient;

/** vm_computers/setup.json */
public class VMSettings {
	public String vmComputersDirectory;

	public int unfocusKey1 = GLFW.GLFW_KEY_LEFT_CONTROL;
	public int unfocusKey2 = GLFW.GLFW_KEY_RIGHT_CONTROL;
	public int unfocusKey3 = GLFW.GLFW_KEY_BACKSPACE;
	public int unfocusKey4 = -1;

	/** Where the nightly 86Box builds are announced. Defaults to the repository the mod was built from. */
	public String manifestUrl;
	/** How often the owner's screen is refreshed from 86Box, in milliseconds. */
	public int frameIntervalMs = 50;
	/** Frames per second sent to other players watching your screen. */
	public int streamFps = 5;
	/** Re-download the ROM set at most this often (it's about 100 MB). */
	public int romUpdateDays = 7;
	/** For testing: use this 86Box and ROM set instead of the nightly. Leave empty normally. */
	public String custom86BoxExe = "";
	public String customRomDirectory = "";

	public VMSettings() {
		if (SystemUtils.IS_OS_MAC) {
			unfocusKey1 = GLFW.GLFW_KEY_LEFT_ALT;
			unfocusKey2 = GLFW.GLFW_KEY_RIGHT_ALT;
		}
		if (ClientMod.vhdDirectory != null) {
			vmComputersDirectory = ClientMod.vhdDirectory.getParentFile().getAbsolutePath();
		} else {
			vmComputersDirectory = new File(MinecraftClient.getInstance().runDirectory, "vm_computers").getAbsolutePath();
		}
		manifestUrl = defaultManifestUrl();
	}

	/** From the mcvmcomputers.properties resource, filled in at build time. */
	public static String defaultManifestUrl() {
		String repo = "OWNER/vmcomputers-86box";
		try (InputStream in = VMSettings.class.getResourceAsStream("/mcvmcomputers.properties")) {
			if (in != null) {
				Properties p = new Properties();
				p.load(in);
				repo = p.getProperty("nightly_repo", repo).trim();
			}
		} catch (IOException e) {
			// keep the default
		}
		return "https://github.com/" + repo + "/releases/latest/download/manifest.json";
	}

	public static File file() {
		return new File(MinecraftClient.getInstance().runDirectory, "vm_computers/setup.json");
	}

	public static VMSettings load() {
		File f = file();
		if (!f.isFile()) {
			return null;
		}
		try {
			VMSettings s = new Gson().fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), VMSettings.class);
			if (s == null) {
				return null;
			}
			if (s.manifestUrl == null || s.manifestUrl.isEmpty() || s.manifestUrl.contains("/OWNER/")) {
				s.manifestUrl = defaultManifestUrl();
			}
			if (s.vmComputersDirectory == null || s.vmComputersDirectory.isEmpty()) {
				s.vmComputersDirectory = new File(MinecraftClient.getInstance().runDirectory, "vm_computers").getAbsolutePath();
			}
			if (s.custom86BoxExe == null) {
				s.custom86BoxExe = "";
			}
			if (s.customRomDirectory == null) {
				s.customRomDirectory = "";
			}
			return s;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public void save() throws IOException {
		File f = file();
		f.getParentFile().mkdirs();
		Gson g = new GsonBuilder().setPrettyPrinting().create();
		Files.write(f.toPath(), g.toJson(this).getBytes(StandardCharsets.UTF_8));
	}
}
