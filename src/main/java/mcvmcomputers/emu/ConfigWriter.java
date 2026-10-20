package mcvmcomputers.emu;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import mcvmcomputers.emu.Catalog.Card;
import mcvmcomputers.emu.Catalog.CardCategory;
import mcvmcomputers.emu.Catalog.Machine;
import mcvmcomputers.emu.HardwareSpec.CardPart;
import mcvmcomputers.emu.HardwareSpec.DiskPart;

/**
 * Turns a {@link HardwareSpec} into an 86box.cfg.
 *
 * Only long-standing keys are written (the ones 86Box itself saves); anything
 * left out falls back to 86Box's defaults, so new 86Box builds keep reading
 * these files without the mod changing.
 */
public final class ConfigWriter {
	private ConfigWriter() {
	}

	/** Where media referenced by the spec live on disk. */
	public static final class Folders {
		public final File hardDisks;
		public final File isos;
		public final File floppies;

		public Folders(File hardDisks, File isos, File floppies) {
			this.hardDisks = hardDisks;
			this.isos = isos;
			this.floppies = floppies;
		}
	}

	/** Disk bus names as 86Box writes them in hdd_NN_parameters. */
	public static String diskBus(HardwareSpec spec, Catalog cat) {
		Machine m = (cat == null || spec.motherboard == null) ? null : cat.machine(spec.motherboard.id);
		for (CardPart c : spec.cards) {
			if (c.category == CardCategory.HDC) {
				String id = c.id.toLowerCase(Locale.ROOT);
				if (id.startsWith("st506")) {
					return "mfm";
				}
				if (id.startsWith("esdi")) {
					return "esdi";
				}
				if (id.startsWith("xta")) {
					return "xta";
				}
				return "ide";
			}
		}
		if (m != null) {
			if (m.hasFlag(Catalog.MACHINE_IDE_PRI)) {
				return "ide";
			}
			if (m.hasFlag(Catalog.MACHINE_MFM)) {
				return "mfm";
			}
			if (m.hasFlag(Catalog.MACHINE_ESDI)) {
				return "esdi";
			}
			if (m.hasFlag(Catalog.MACHINE_XTA)) {
				return "xta";
			}
			if (m.hasFlag(Catalog.MACHINE_SCSI)) {
				return "scsi";
			}
		}
		if (spec.cardCount(CardCategory.SCSI) > 0) {
			return "scsi";
		}
		return "ide";
	}

	/** Cylinders/heads/sectors for a disk of the given size on the given bus. */
	public static int[] geometry(int sizeMb, String bus) {
		long sectors = (long) sizeMb * 2048L;
		int spt;
		int hpc;
		int maxCyl;
		switch (bus) {
		case "mfm":
		case "xta":
			spt = 17;
			hpc = 15;
			maxCyl = 1024;
			break;
		case "esdi":
			spt = 35;
			hpc = 16;
			maxCyl = 1024;
			break;
		default:
			spt = 63;
			hpc = 16;
			maxCyl = 65535;
		}
		long cyl = Math.max(1, sectors / ((long) spt * hpc));
		if (cyl > maxCyl) {
			cyl = maxCyl;
		}
		return new int[] { spt, hpc, (int) cyl };
	}

	public static long imageBytes(int[] g) {
		return (long) g[0] * g[1] * g[2] * 512L;
	}

	/**
	 * Creates a blank raw hard disk image of exactly the size 86Box expects for
	 * this geometry. Does nothing if the file already exists.
	 */
	public static void createDiskImage(File f, int sizeMb, String bus) throws IOException {
		if (f.exists()) {
			return;
		}
		File parent = f.getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
		try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
			raf.setLength(imageBytes(geometry(sizeMb, bus)));
		}
	}

	public static void write(File cfgFile, HardwareSpec spec, Catalog cat, Folders folders) throws IOException {
		Files.write(cfgFile.toPath(), build(spec, cat, folders).getBytes(StandardCharsets.UTF_8));
	}

	public static String build(HardwareSpec spec, Catalog cat, Folders folders) {
		Machine m = (cat == null || spec.motherboard == null) ? null : cat.machine(spec.motherboard.id);
		Ini ini = new Ini();

		// General: software renderer (86Box's window is hidden), keep the VM quiet about itself.
		ini.set("General", "vid_renderer", "qt_software");
		ini.set("General", "window_remember", "0");

		// Machine.
		if (spec.motherboard != null) {
			ini.set("Machine", "machine", spec.motherboard.id);
		}
		if (spec.cpu != null) {
			ini.set("Machine", "cpu_family", spec.cpu.family);
			ini.set("Machine", "cpu_speed", Long.toString(spec.cpu.hz));
			ini.set("Machine", "cpu_multi", multi(spec.cpu.multi));
		}
		if (spec.cpu != null && !spec.cpu.fpu.isEmpty()) {
			ini.set("Machine", "fpu_type", spec.cpu.fpu);
		}
		ini.set("Machine", "cpu_use_dynarec", "1");
		ini.set("Machine", "time_sync", "local");
		int ram = spec.totalRamKb();
		if (m != null) {
			ram = Math.max(m.ramMinKb, Math.min(m.ramMaxKb, ram));
			if (m.ramStepKb > 0) {
				ram = Math.max(m.ramMinKb, ram - (ram % m.ramStepKb));
			}
		}
		if (ram > 0) {
			ini.set("Machine", "mem_size", Integer.toString(ram));
		}

		// Video: the first card is the main display; a board with fixed video keeps it.
		List<CardPart> video = byCategory(spec, CardCategory.VIDEO);
		boolean fixedVideo = m != null && m.videoFixed;
		if (fixedVideo || (video.isEmpty() && m != null && m.onboardVideo != null)) {
			ini.set("Video", "gfxcard", "internal");
			if (!video.isEmpty()) {
				ini.set("Video", "gfxcard_2", video.get(0).id);
			}
		} else if (!video.isEmpty()) {
			ini.set("Video", "gfxcard", video.get(0).id);
			if (video.size() > 1) {
				ini.set("Video", "gfxcard_2", video.get(1).id);
			}
		} else {
			ini.set("Video", "gfxcard", "none");
		}

		// Add-on accelerators (Voodoo, 8514/A, XGA, DA2): a switch in [Video], settings in their own section.
		for (CardPart c : byCategory(spec, CardCategory.ACCEL)) {
			Card card = cat == null ? null : cat.card(CardCategory.ACCEL, c.id);
			if (card == null || card.configKey == null || (m != null && !card.fits(m))) {
				continue;
			}
			ini.set("Video", card.configKey, "1");
		}

		// Sound: up to four cards; the board's own sound when there are none.
		List<CardPart> sound = byCategory(spec, CardCategory.SOUND);
		if (sound.isEmpty()) {
			ini.set("Sound", "sndcard", (m != null && m.onboardSound != null) ? "internal" : "none");
		}
		for (int i = 0; i < Math.min(4, sound.size()); i++) {
			ini.set("Sound", i == 0 ? "sndcard" : "sndcard" + (i + 1), sound.get(i).id);
		}

		// Network: user-mode networking (SLiRP) needs nothing on the host.
		List<CardPart> net = byCategory(spec, CardCategory.NETWORK);
		int nic = 0;
		if (net.isEmpty() && m != null && m.onboardNetwork != null) {
			ini.set("Network", "net_01_card", "internal");
			ini.set("Network", "net_01_net_type", "slirp");
			nic = 1;
		}
		for (int i = 0; i < net.size() && nic < 4; i++, nic++) {
			String n = String.format(Locale.ROOT, "net_%02d_", nic + 1);
			ini.set("Network", n + "card", net.get(i).id);
			ini.set("Network", n + "net_type", "slirp");
		}

		// Storage controllers.
		List<CardPart> fdc = byCategory(spec, CardCategory.FDC);
		ini.set("Storage controllers", "fdc", fdc.isEmpty() ? "internal" : fdc.get(0).id);
		List<CardPart> hdc = byCategory(spec, CardCategory.HDC);
		if (hdc.isEmpty()) {
			if (m != null && (m.hasFlag(Catalog.MACHINE_IDE_PRI) || m.hasFlag(Catalog.MACHINE_MFM)
					|| m.hasFlag(Catalog.MACHINE_ESDI) || m.hasFlag(Catalog.MACHINE_XTA))) {
				ini.set("Storage controllers", "hdc_1", "internal");
			}
		} else {
			for (int i = 0; i < Math.min(4, hdc.size()); i++) {
				ini.set("Storage controllers", "hdc_" + (i + 1), hdc.get(i).id);
			}
		}
		List<CardPart> scsi = byCategory(spec, CardCategory.SCSI);
		for (int i = 0; i < Math.min(4, scsi.size()); i++) {
			ini.set("Storage controllers", "scsicard_" + (i + 1), scsi.get(i).id);
		}

		// Drives. Hard disks first, then CD-ROM drives, in IDE order 0:0, 0:1, 1:0, 1:1
		// (so one disk and one CD share a single-channel controller).
		String bus = diskBus(spec, cat);
		boolean scsiBus = bus.equals("scsi");
		int pos = 0;
		for (int i = 0; i < spec.hardDisks.size() && pos < HardwareSpec.MAX_IDE_DEVICES; i++, pos++) {
			DiskPart d = spec.hardDisks.get(i);
			int[] g = d.hasGeometry() ? new int[] { d.sectors, d.heads, d.cylinders } : geometry(d.sizeMb, bus);
			String n = String.format(Locale.ROOT, "hdd_%02d_", i + 1);
			ini.set("Hard disks", n + "parameters", g[0] + ", " + g[1] + ", " + g[2] + ", 0, " + bus);
			File image = new File(folders.hardDisks, d.file);
			ini.set("Hard disks", n + "fn", image.getAbsolutePath());
			if (!d.model.isEmpty()) {
				ini.set("Hard disks", n + "speed", d.model);
			}
			// A disk that doesn't exist yet is created by 86Box when it starts, in this format.
			// (An existing VHD keeps its own geometry and type.)
			if (!image.exists()) {
				if (HardwareSpec.DiskPart.VHD_DYNAMIC.equals(d.format) || HardwareSpec.DiskPart.VHD_DIFF.equals(d.format)) {
					ini.set("Hard disks", n + "vhd_blocksize", "4096");
				}
				if (HardwareSpec.DiskPart.VHD_DIFF.equals(d.format) && !d.parent.isEmpty()) {
					ini.set("Hard disks", n + "vhd_parent", new File(folders.hardDisks, d.parent).getAbsolutePath());
				}
			}
			switch (bus) {
			case "ide":
				ini.set("Hard disks", n + "ide_channel", (pos >> 1) + ":" + (pos & 1));
				break;
			case "scsi":
				ini.set("Hard disks", n + "scsi_location", String.format(Locale.ROOT, "0:%02d", pos));
				break;
			case "mfm":
				ini.set("Hard disks", n + "mfm_channel", Integer.toString(i));
				break;
			case "esdi":
				ini.set("Hard disks", n + "esdi_channel", Integer.toString(i));
				break;
			case "xta":
				ini.set("Hard disks", n + "xta_channel", Integer.toString(i));
				break;
			default:
				break;
			}
		}
		// CD-ROM drives sit on IDE/ATAPI unless the disks are on SCSI.
		int cdPos = bus.equals("ide") || scsiBus ? pos : 0;
		for (int i = 0; i < spec.cdroms.size() && cdPos < HardwareSpec.MAX_IDE_DEVICES; i++, cdPos++) {
			String n = String.format(Locale.ROOT, "cdrom_%02d_", i + 1);
			ini.set("Floppy and CD-ROM drives", n + "parameters", "1, " + (scsiBus ? "scsi" : "atapi"));
			if (scsiBus) {
				ini.set("Floppy and CD-ROM drives", n + "scsi_location", String.format(Locale.ROOT, "0:%02d", cdPos));
			} else {
				ini.set("Floppy and CD-ROM drives", n + "ide_channel", (cdPos >> 1) + ":" + (cdPos & 1));
			}
			ini.set("Floppy and CD-ROM drives", n + "type", spec.cdroms.get(i).id);
			String iso = spec.cdImage(i);
			if (!iso.isEmpty() && folders.isos != null) {
				ini.set("Floppy and CD-ROM drives", n + "image_path", new File(folders.isos, iso).getAbsolutePath());
			}
		}
		// Floppy drives: 86Box gives drives A: and B: default types unless told "none".
		for (int i = 0; i < HardwareSpec.MAX_FLOPPIES; i++) {
			String n = String.format(Locale.ROOT, "fdd_%02d_", i + 1);
			if (i < spec.floppies.size()) {
				ini.set("Floppy and CD-ROM drives", n + "type", spec.floppies.get(i).id);
				String img = spec.floppyImage(i);
				if (!img.isEmpty() && folders.floppies != null) {
					ini.set("Floppy and CD-ROM drives", n + "fn", new File(folders.floppies, img).getAbsolutePath());
				}
			} else {
				ini.set("Floppy and CD-ROM drives", n + "type", "none");
			}
		}

		// Settings from each part's Configure dialog, in sections named after the device
		// (written last so they don't split the sections above).
		if (m != null && spec.motherboard != null) {
			for (Catalog.SettingsGroup g : m.settings) {
				for (Map.Entry<String, String> e : g.set.resolve(g.pick(spec.motherboard.options)).entrySet()) {
					ini.set(g.section, e.getKey(), e.getValue());
				}
			}
		}
		for (CardPart c : spec.cards) {
			Card card = cat == null ? null : cat.card(c.category, c.id);
			if (card != null && card.section != null && !card.settings.isEmpty()) {
				for (Map.Entry<String, String> e : card.resolve(c.options).entrySet()) {
					ini.set(card.section, e.getKey(), e.getValue());
				}
			}
		}

		// Input: a mouse the automation socket can move.
		String mouse = "msserial";
		if (m != null) {
			if (m.hasFlag(0x40L)) { // MACHINE_MOUSE
				mouse = "internal";
			} else if (m.hasBus(Catalog.MACHINE_BUS_PS2_PORTS)) {
				mouse = "ps2";
			}
		}
		ini.set("Input devices", "mouse_type", mouse);

		return ini.toString();
	}

	private static String multi(double d) {
		if (d == Math.rint(d)) {
			return Long.toString((long) d);
		}
		return Double.toString(d);
	}

	private static List<CardPart> byCategory(HardwareSpec spec, CardCategory cat) {
		List<CardPart> out = new ArrayList<>();
		for (CardPart c : spec.cards) {
			if (c.category == cat) {
				out.add(c);
			}
		}
		return out;
	}

	/** Ordered INI writer. */
	private static final class Ini {
		private final Map<String, Map<String, String>> sections = new LinkedHashMap<>();

		void set(String section, String key, String value) {
			sections.computeIfAbsent(section, k -> new LinkedHashMap<>()).put(key, value);
		}

		@Override
		public String toString() {
			StringBuilder b = new StringBuilder();
			for (Map.Entry<String, Map<String, String>> s : sections.entrySet()) {
				if (b.length() > 0) {
					b.append('\n');
				}
				b.append('[').append(s.getKey()).append("]\n");
				for (Map.Entry<String, String> kv : s.getValue().entrySet()) {
					b.append(kv.getKey()).append(" = ").append(kv.getValue()).append('\n');
				}
			}
			return b.toString();
		}
	}
}
