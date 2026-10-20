package mcvmcomputers.emu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import mcvmcomputers.emu.Catalog.Card;
import mcvmcomputers.emu.Catalog.CardCategory;
import mcvmcomputers.emu.Catalog.Machine;

/**
 * What is installed in one in-game PC. Plain data: the Minecraft side stores
 * it in the PC entity's NBT ({@code mcvmcomputers.utils.HardwareNbt}) and the
 * emulator side turns it into an 86Box config ({@link ConfigWriter}).
 *
 * Every part keeps its display name next to its 86Box id, so players who have
 * no catalog downloaded still see sensible names.
 */
public final class HardwareSpec {
	public static final int MAX_RAM_STICKS = 8;
	public static final int MAX_CARDS = 7;
	public static final int MAX_FLOPPIES = 2;
	/** Hard disks and CD-ROM drives share the four IDE positions. */
	public static final int MAX_IDE_DEVICES = 4;

	public static final class Part {
		public final String id;
		public final String name;
		/** Settings, as 86Box stores them. A motherboard's built-in chips use prefixed keys ("video/memory"). */
		public final Map<String, String> options;

		public Part(String id, String name) {
			this(id, name, null);
		}

		public Part(String id, String name, Map<String, String> options) {
			this.id = id;
			this.name = (name == null || name.isEmpty()) ? id : name;
			this.options = copy(options);
		}
	}

	static Map<String, String> copy(Map<String, String> options) {
		return options == null || options.isEmpty() ? Collections.<String, String>emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(options));
	}

	public static final class CpuPart {
		public final String family;
		public final long hz;
		public final double multi;
		public final String name;
		/** Math coprocessor (86Box's fpu_type), "" = none/built-in. */
		public final String fpu;

		public CpuPart(String family, long hz, double multi, String name) {
			this(family, hz, multi, name, "");
		}

		public CpuPart(String family, long hz, double multi, String name, String fpu) {
			this.family = family;
			this.hz = hz;
			this.multi = multi;
			this.name = (name == null || name.isEmpty()) ? family : name;
			this.fpu = fpu == null ? "" : fpu;
		}
	}

	public static final class CardPart {
		public final CardCategory category;
		public final String id;
		public final String name;
		/** Settings from the card's 86Box Configure dialog, as 86Box stores them. Empty = 86Box's defaults. */
		public final Map<String, String> options;

		public CardPart(CardCategory category, String id, String name) {
			this(category, id, name, null);
		}

		public CardPart(CardCategory category, String id, String name, Map<String, String> options) {
			this.category = category;
			this.id = id;
			this.name = (name == null || name.isEmpty()) ? id : name;
			this.options = copy(options);
		}
	}

	public static final class DiskPart {
		public static final String RAW = "raw", VHD_FIXED = "vhd_fixed", VHD_DYNAMIC = "vhd_dynamic", VHD_DIFF = "vhd_diff";

		/** Image file name inside the hard disk folder. */
		public final String file;
		public final int sizeMb;
		/** The item's custom (anvil) name as Minecraft text JSON, kept so it survives a trip into the PC. */
		public final String customName;
		/** Geometry; 0 = work it out from the size and the controller (disks made before geometry was stored). */
		public final int cylinders, heads, sectors;
		/** Drive model (86Box's hdd_NN_speed preset), "" = 86Box's default for the bus. */
		public final String model;
		/** How 86Box should create the image if the file doesn't exist yet. */
		public final String format;
		/** Differencing VHDs: the parent disk's file name. */
		public final String parent;

		public DiskPart(String file, int sizeMb) {
			this(file, sizeMb, "");
		}

		public DiskPart(String file, int sizeMb, String customName) {
			this(file, sizeMb, customName, 0, 0, 0, "", RAW, "");
		}

		public DiskPart(String file, int sizeMb, String customName, int cylinders, int heads, int sectors, String model,
				String format, String parent) {
			this.file = file;
			this.customName = customName == null ? "" : customName;
			this.cylinders = Math.max(0, cylinders);
			this.heads = Math.max(0, heads);
			this.sectors = Math.max(0, sectors);
			this.sizeMb = hasGeometry() ? (int) Math.max(1, (long) this.cylinders * this.heads * this.sectors / 2048L) : sizeMb;
			this.model = model == null ? "" : model;
			this.format = format == null || format.isEmpty() ? RAW : format;
			this.parent = parent == null ? "" : parent;
		}

		public boolean hasGeometry() {
			return cylinders > 0 && heads > 0 && sectors > 0;
		}

		public DiskPart withModel(String m) {
			return new DiskPart(file, sizeMb, customName, cylinders, heads, sectors, m, format, parent);
		}
	}

	/** Largest geometry 86Box accepts per disk interface: {sectors, heads, cylinders}. */
	public static int[] geometryLimits(String bus) {
		switch (bus) {
		case "mfm":
			return new int[] { 26, 15, 2047 };
		case "xta":
			return new int[] { 63, 16, 1023 };
		case "esdi":
			return new int[] { 99, 16, 266305 };
		default:
			return new int[] { 255, 255, 266305 };
		}
	}

	public Part motherboard;
	public CpuPart cpu;
	/** RAM sticks, each in KB (old boards use sizes like 256 KB). */
	public final List<Integer> ramKb = new ArrayList<>();
	public final List<CardPart> cards = new ArrayList<>();
	/** Floppy drive types (86Box ids like "35_2hd"), drive A: first. */
	public final List<Part> floppies = new ArrayList<>();
	/** CD-ROM drive models (86Box ids). */
	public final List<Part> cdroms = new ArrayList<>();
	public final List<DiskPart> hardDisks = new ArrayList<>();

	/** Inserted media, by drive position. Empty string = nothing inserted. */
	public final List<String> floppyImages = new ArrayList<>();
	public final List<String> cdImages = new ArrayList<>();

	public int totalRamKb() {
		int t = 0;
		for (int k : ramKb) {
			t += k;
		}
		return t;
	}

	public boolean isEmpty() {
		return motherboard == null && cpu == null && ramKb.isEmpty() && cards.isEmpty() && floppies.isEmpty()
				&& cdroms.isEmpty() && hardDisks.isEmpty();
	}

	public int cardCount(CardCategory cat) {
		int n = 0;
		for (CardPart c : cards) {
			if (c.category == cat) {
				n++;
			}
		}
		return n;
	}

	public String floppyImage(int drive) {
		return drive < floppyImages.size() ? floppyImages.get(drive) : "";
	}

	public String cdImage(int drive) {
		return drive < cdImages.size() ? cdImages.get(drive) : "";
	}

	public void setFloppyImage(int drive, String file) {
		while (floppyImages.size() <= drive) {
			floppyImages.add("");
		}
		floppyImages.set(drive, file == null ? "" : file);
	}

	public void setCdImage(int drive, String file) {
		while (cdImages.size() <= drive) {
			cdImages.add("");
		}
		cdImages.set(drive, file == null ? "" : file);
	}

	public static String ramLabel(int kb) {
		if (kb >= 1024 * 1024 && kb % (1024 * 1024) == 0) {
			return (kb / (1024 * 1024)) + " GB";
		}
		if (kb >= 1024 && kb % 1024 == 0) {
			return (kb / 1024) + " MB";
		}
		if (kb >= 1024) {
			return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
		}
		return kb + " KB";
	}

	// ---- Checks ------------------------------------------------------------------

	/** A problem that keeps the PC from starting, or a warning. */
	public static final class Problem {
		public final boolean blocking;
		public final String message;

		Problem(boolean blocking, String message) {
			this.blocking = blocking;
			this.message = message;
		}
	}

	/**
	 * Checks the build against the catalog (86Box's own compatibility data).
	 * Blocking problems keep the PC from powering on; the rest are warnings.
	 */
	public List<Problem> check(Catalog cat) {
		List<Problem> out = new ArrayList<>();
		if (motherboard == null) {
			out.add(new Problem(true, "No motherboard installed."));
			return out;
		}
		Machine m = cat == null ? null : cat.machine(motherboard.id);
		if (cat != null && m == null) {
			out.add(new Problem(true, "This 86Box build no longer has the " + motherboard.name + " motherboard."));
			return out;
		}
		if (cpu == null) {
			out.add(new Problem(true, "No CPU installed."));
		} else if (cat != null && !cat.cpuFits(m, cpu.family, cpu.hz, cpu.multi)) {
			out.add(new Problem(true, "The " + cpu.name + " doesn't fit this motherboard."));
		}
		int ram = totalRamKb();
		if (m != null) {
			if (ram < m.ramMinKb) {
				out.add(new Problem(true, "This board needs at least " + ramLabel(m.ramMinKb) + " of RAM (has "
						+ ramLabel(ram) + ")."));
			} else if (ram > m.ramMaxKb) {
				out.add(new Problem(false, "This board only uses " + ramLabel(m.ramMaxKb) + " of RAM; the rest is ignored."));
			} else if (m.ramStepKb > 0 && ram % m.ramStepKb != 0) {
				out.add(new Problem(false, "RAM is rounded to steps of " + ramLabel(m.ramStepKb) + " on this board."));
			}
		}
		if (cat != null) {
			for (CardPart c : cards) {
				Card card = cat.card(c.category, c.id);
				if (card == null) {
					out.add(new Problem(true, "This 86Box build no longer has the " + c.name + "."));
				} else if (!card.fits(m)) {
					out.add(new Problem(true, "The " + c.name + " doesn't fit this motherboard's slots."));
				}
			}
			checkAccelerators(cat, m, out);
			if (cardCount(CardCategory.VIDEO) == 0 && (m == null || m.onboardVideo == null)) {
				out.add(new Problem(false, "No video card: the screen will stay black."));
			}
			if (!hardDisks.isEmpty() && !Catalog.hasOnboardIde(m) && !m.hasFlag(Catalog.MACHINE_MFM)
					&& !m.hasFlag(Catalog.MACHINE_XTA) && !m.hasFlag(Catalog.MACHINE_ESDI)
					&& !m.hasFlag(Catalog.MACHINE_SCSI) && cardCount(CardCategory.HDC) == 0
					&& cardCount(CardCategory.SCSI) == 0) {
				out.add(new Problem(false, "This board has no hard disk controller; add one or the disk won't be seen."));
			}
			// No floppy controller check: nearly every board gets one from its Super I/O
			// chip or from 86Box's machine setup, and 86Box doesn't flag those.
		}
		if (cat != null && m != null) {
			String bus = ConfigWriter.diskBus(this, cat);
			int[] lim = geometryLimits(bus);
			for (DiskPart d : hardDisks) {
				if (d.hasGeometry() && (d.sectors > lim[0] || d.heads > lim[1] || d.cylinders > lim[2])) {
					out.add(new Problem(false, "The disk " + d.file + " (" + d.cylinders + "/" + d.heads + "/" + d.sectors
							+ ") is too big for this " + bus.toUpperCase(Locale.ROOT) + " controller; 86Box will cut it down."));
				}
			}
		}
		for (DiskPart d : hardDisks) {
			for (DiskPart p : hardDisks) {
				if (!d.parent.isEmpty() && d.parent.equals(p.file)) {
					out.add(new Problem(true, d.file + " is based on " + p.file + "; they can't be in the same PC (the base disk must stay unchanged)."));
				}
			}
		}
		if (hardDisks.size() + cdroms.size() > MAX_IDE_DEVICES) {
			out.add(new Problem(true, "Too many drives: hard disks and CD-ROM drives share " + MAX_IDE_DEVICES + " positions."));
		}
		return out;
	}

	/** Add-on cards: one of each kind, and not next to a main card that already is one. */
	private void checkAccelerators(Catalog cat, Machine m, List<Problem> out) {
		String main = null;
		for (CardPart c : cards) {
			if (c.category == CardCategory.VIDEO) {
				main = c.id;
				break;
			}
		}
		if (m != null && m.videoFixed) {
			main = null; // the board's own video stays the main display
		}
		List<String> keys = new ArrayList<>();
		for (CardPart c : cards) {
			if (c.category != CardCategory.ACCEL) {
				continue;
			}
			Card card = cat.card(c.category, c.id);
			if (card == null) {
				continue;
			}
			String key = card.configKey == null ? card.id : card.configKey;
			if (keys.contains(key)) {
				out.add(new Problem(true, "Only one " + c.name + " can be installed."));
			}
			keys.add(key);
			if (main != null && card.conflicts.contains(main)) {
				Card mainCard = cat.card(CardCategory.VIDEO, main);
				out.add(new Problem(true, "The " + c.name + " can't be used with the "
						+ (mainCard == null ? main : mainCard.name) + ", which already is one."));
			}
		}
	}

	public static boolean anyBlocking(List<Problem> problems) {
		for (Problem p : problems) {
			if (p.blocking) {
				return true;
			}
		}
		return false;
	}
}
