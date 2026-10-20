package mcvmcomputers.emu;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Keeps the nightly 86Box, the ROM set and the parts catalog up to date.
 *
 * Everything comes from the manifest.json of the newest release of the mod's
 * nightly repository (see .github/workflows/86box-nightly.yml):
 * <ul>
 * <li>the catalog is small and refreshed whenever the game starts, so every
 * player can browse the shop;</li>
 * <li>86Box itself is only downloaded on PCs that actually run a VM, and is
 * updated whenever a newer nightly exists;</li>
 * <li>the ROM set (about 100 MB) is refreshed at most every
 * {@code romUpdateDays} days, or on demand.</li>
 * </ul>
 * When offline, whatever was installed last keeps working.
 */
public final class Installer {
	public static final class Manifest {
		public final String tag;
		public final String emulatorVersion;
		public final String emulatorGitHash;
		public final String romsGitHash;
		public final String romsUrl;
		public final String windowsUrl;
		public final String windowsSha256;
		public final String catalogUrl;
		public final String catalogSha256;

		Manifest(Map<String, Object> m) {
			tag = Json.str(m, "tag", "");
			emulatorVersion = Json.str(m, "emulator_version", "");
			emulatorGitHash = Json.str(m, "emulator_git_hash", "");
			romsGitHash = Json.str(m, "roms_git_hash", "");
			romsUrl = Json.str(m, "roms_url", "");
			Map<String, Object> w = Json.obj(m, "windows_x64");
			windowsUrl = w == null ? "" : Json.str(w, "url", "");
			windowsSha256 = w == null ? "" : Json.str(w, "sha256", "");
			Map<String, Object> c = Json.obj(m, "catalog");
			catalogUrl = c == null ? "" : Json.str(c, "url", "");
			catalogSha256 = c == null ? "" : Json.str(c, "sha256", "");
		}
	}

	/** An installed 86Box with its ROM set. */
	public static final class Installed {
		public final File exe;
		public final File roms;
		public final String version;

		Installed(File exe, File roms, String version) {
			this.exe = exe;
			this.roms = roms;
			this.version = version;
		}
	}

	private final File base;
	private final String manifestUrl;
	private final int romUpdateDays;

	private volatile String status = "";
	private volatile double progress = -1;
	private volatile Manifest lastManifest;
	private volatile Catalog catalog;
	private volatile boolean busy;

	/** For development: use this 86Box and ROM set instead of downloading. */
	private volatile File overrideExe;
	private volatile File overrideRoms;

	public Installer(File base, String manifestUrl, int romUpdateDays) {
		this.base = base;
		this.manifestUrl = manifestUrl;
		this.romUpdateDays = romUpdateDays;
		base.mkdirs();
	}

	public void setOverride(File exe, File roms) {
		overrideExe = exe;
		overrideRoms = roms;
	}

	public String status() {
		return status;
	}

	/** 0..1, or -1 when unknown/idle. */
	public double progress() {
		return progress;
	}

	public boolean busy() {
		return busy;
	}

	public Catalog catalog() {
		return catalog;
	}

	private File catalogFile() {
		return new File(base, "catalog.json");
	}

	// ---- Catalog --------------------------------------------------------------------

	/** Loads the catalog from disk if there is one. */
	public Catalog loadInstalledCatalog() {
		File f = catalogFile();
		if (f.isFile()) {
			try {
				catalog = Catalog.load(f);
			} catch (Exception e) {
				System.err.println("[VM Computers] Couldn't read " + f + ": " + e);
			}
		}
		return catalog;
	}

	/** Fetches the newest manifest and catalog. Safe to call from a background thread. */
	public synchronized void refreshCatalog() {
		try {
			busy = true;
			status = "Checking for a new 86Box build...";
			Manifest m = fetchManifest();
			File f = catalogFile();
			if (!m.catalogUrl.isEmpty() && (!f.isFile() || !m.catalogSha256.equalsIgnoreCase(sha256(f)))) {
				status = "Downloading the parts catalog...";
				download(m.catalogUrl, f, m.catalogSha256);
			}
			catalog = Catalog.load(f);
			status = "Parts catalog: 86Box " + catalog.emulatorVersion + ", " + catalog.machines.size() + " motherboards.";
		} catch (Exception e) {
			if (catalog == null) {
				loadInstalledCatalog();
			}
			status = "Couldn't check for updates (" + e.getMessage() + ")"
					+ (catalog != null ? "; using the catalog from before." : ".");
			System.err.println("[VM Computers] " + status);
		} finally {
			busy = false;
			progress = -1;
		}
	}

	private Manifest fetchManifest() throws IOException {
		File f = new File(base, "manifest.json");
		download(manifestUrl, f, null);
		Manifest m = new Manifest(Json.parseObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
		lastManifest = m;
		return m;
	}

	// ---- 86Box and ROMs -----------------------------------------------------------------

	/** What is installed right now, without touching the network. Null if nothing usable. */
	public Installed installed() {
		if (overrideExe != null && overrideRoms != null) {
			return new Installed(overrideExe, overrideRoms, "custom");
		}
		String tag = readText(new File(base, "current_build.txt"));
		String roms = readText(new File(base, "current_roms.txt"));
		if (tag.isEmpty() || roms.isEmpty()) {
			return null;
		}
		File exe = findExe(new File(base, "builds/" + tag));
		File romDir = new File(base, "roms/" + roms.split("\\s+")[0]);
		if (exe == null || !romDir.isDirectory()) {
			return null;
		}
		return new Installed(exe, romDir, tag);
	}

	/**
	 * Makes sure the newest 86Box and a ROM set are installed. Blocking; call
	 * from a worker thread. Falls back to the installed copy when offline.
	 *
	 * @param forceRoms refresh the ROM set even if it's recent
	 */
	public synchronized Installed ensureEmulator(boolean forceRoms) throws IOException {
		if (overrideExe != null && overrideRoms != null) {
			return installed();
		}
		busy = true;
		try {
			Manifest m;
			try {
				m = fetchManifest();
			} catch (IOException e) {
				Installed have = installed();
				if (have != null) {
					status = "Offline; using 86Box " + have.version + ".";
					return have;
				}
				throw new IOException("Couldn't download 86Box: " + e.getMessage(), e);
			}
			if (!WindowHider.isWindows() || !System.getProperty("os.arch", "").contains("64")) {
				throw new IOException("Nightly 86Box builds are only made for 64-bit Windows.");
			}

			// 86Box.
			String tag = readText(new File(base, "current_build.txt"));
			File buildDir = new File(base, "builds/" + m.tag);
			if (!m.tag.equals(tag) || findExe(buildDir) == null) {
				status = "Downloading 86Box " + m.emulatorVersion + " (" + m.tag + ")...";
				File zip = new File(base, "download-86box.zip");
				download(m.windowsUrl, zip, m.windowsSha256);
				status = "Unpacking 86Box...";
				File tmp = new File(base, "builds/" + m.tag + ".partial");
				deleteTree(tmp);
				unzip(zip, tmp, false);
				zip.delete();
				deleteTree(buildDir);
				if (!tmp.renameTo(buildDir)) {
					throw new IOException("Couldn't move 86Box into " + buildDir);
				}
				writeText(new File(base, "current_build.txt"), m.tag);
				pruneOldBuilds(m.tag, tag);
			}

			// ROM set.
			String[] romInfo = readText(new File(base, "current_roms.txt")).split("\\s+");
			String romSha = romInfo.length > 0 ? romInfo[0] : "";
			long romTime = romInfo.length > 1 ? parseLong(romInfo[1]) : 0;
			boolean haveRoms = !romSha.isEmpty() && new File(base, "roms/" + romSha).isDirectory();
			boolean stale = System.currentTimeMillis() - romTime > romUpdateDays * 86_400_000L;
			if (!haveRoms || ((forceRoms || stale) && !romSha.equals(m.romsGitHash))) {
				status = "Downloading the 86Box ROM set (about 100 MB)...";
				File zip = new File(base, "download-roms.zip");
				download(m.romsUrl, zip, null);
				status = "Unpacking the ROM set...";
				File tmp = new File(base, "roms/" + m.romsGitHash + ".partial");
				deleteTree(tmp);
				unzip(zip, tmp, true);
				zip.delete();
				File dest = new File(base, "roms/" + m.romsGitHash);
				deleteTree(dest);
				if (!tmp.renameTo(dest)) {
					throw new IOException("Couldn't move the ROM set into " + dest);
				}
				writeText(new File(base, "current_roms.txt"), m.romsGitHash + " " + System.currentTimeMillis());
				if (!romSha.isEmpty() && !romSha.equals(m.romsGitHash)) {
					deleteTree(new File(base, "roms/" + romSha));
				}
			}
			Installed i = installed();
			if (i == null) {
				throw new IOException("86Box was downloaded but couldn't be found on disk.");
			}
			status = "86Box " + m.emulatorVersion + " (" + m.tag + ") is ready.";
			return i;
		} finally {
			busy = false;
			progress = -1;
		}
	}

	private void pruneOldBuilds(String keep, String previous) {
		File[] all = new File(base, "builds").listFiles();
		if (all == null) {
			return;
		}
		for (File f : all) {
			String n = f.getName();
			if (!n.equals(keep) && !n.equals(previous)) {
				deleteTree(f);
			}
		}
	}

	private static File findExe(File dir) {
		if (dir == null || !dir.isDirectory()) {
			return null;
		}
		File[] kids = dir.listFiles();
		if (kids == null) {
			return null;
		}
		for (File f : kids) {
			String n = f.getName();
			if (f.isFile() && (n.equalsIgnoreCase("86Box.exe") || n.equals("86Box"))) {
				return f;
			}
		}
		for (File f : kids) {
			if (f.isDirectory()) {
				File e = findExe(f);
				if (e != null) {
					return e;
				}
			}
		}
		return null;
	}

	// ---- Plumbing --------------------------------------------------------------------

	private void download(String url, File dest, String sha256) throws IOException {
		if (url == null || url.isEmpty()) {
			throw new IOException("No download address in the manifest");
		}
		File part = new File(dest.getPath() + ".part");
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setInstanceFollowRedirects(true);
		c.setConnectTimeout(15_000);
		c.setReadTimeout(60_000);
		c.setRequestProperty("User-Agent", "VMComputers86Box");
		int code = c.getResponseCode();
		if (code != 200) {
			throw new IOException("HTTP " + code + " from " + url);
		}
		long total = c.getContentLengthLong();
		MessageDigest md = sha();
		long done = 0;
		try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new FileOutputStream(part)) {
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				md.update(buf, 0, n);
				done += n;
				progress = total > 0 ? (double) done / total : -1;
			}
		} finally {
			c.disconnect();
		}
		if (sha256 != null && !sha256.isEmpty() && !sha256.equalsIgnoreCase(hex(md.digest()))) {
			part.delete();
			throw new IOException("Download of " + dest.getName() + " was corrupted (checksum mismatch)");
		}
		Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
		progress = -1;
	}

	/** Extracts a zip; with stripRoot, drops the single top-level folder (GitHub source zips have one). */
	static void unzip(File zip, File dest, boolean stripRoot) throws IOException {
		dest.mkdirs();
		String destPath = dest.getCanonicalPath() + File.separator;
		try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip.toPath())))) {
			ZipEntry e;
			byte[] buf = new byte[1 << 16];
			while ((e = zin.getNextEntry()) != null) {
				String name = e.getName().replace('\\', '/');
				if (stripRoot) {
					int slash = name.indexOf('/');
					if (slash < 0) {
						continue;
					}
					name = name.substring(slash + 1);
				}
				if (name.isEmpty()) {
					continue;
				}
				File out = new File(dest, name);
				if (!out.getCanonicalPath().startsWith(destPath)) {
					throw new IOException("Unsafe path in archive: " + e.getName());
				}
				if (e.isDirectory()) {
					out.mkdirs();
					continue;
				}
				out.getParentFile().mkdirs();
				try (OutputStream o = new FileOutputStream(out)) {
					int n;
					while ((n = zin.read(buf)) > 0) {
						o.write(buf, 0, n);
					}
				}
			}
		}
	}

	static String sha256(File f) throws IOException {
		MessageDigest md = sha();
		try (InputStream in = new BufferedInputStream(Files.newInputStream(f.toPath()))) {
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0) {
				md.update(buf, 0, n);
			}
		}
		return hex(md.digest());
	}

	private static MessageDigest sha() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String hex(byte[] b) {
		StringBuilder s = new StringBuilder();
		for (byte x : b) {
			s.append(String.format("%02x", x & 0xff));
		}
		return s.toString();
	}

	private static String readText(File f) {
		try {
			return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim() : "";
		} catch (IOException e) {
			return "";
		}
	}

	private static void writeText(File f, String s) throws IOException {
		Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
	}

	private static long parseLong(String s) {
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	static void deleteTree(File f) {
		if (f == null || !f.exists()) {
			return;
		}
		if (Files.isSymbolicLink(f.toPath())) {
			f.delete(); // never follow links out of our folder
			return;
		}
		File[] kids = f.listFiles();
		if (kids != null) {
			for (File k : kids) {
				deleteTree(k);
			}
		}
		f.delete();
	}
}
