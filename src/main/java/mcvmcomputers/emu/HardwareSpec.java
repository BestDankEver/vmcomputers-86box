package mcvmcomputers.emu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

		public Part(String id, String name) {
			this.id = id;
			this.name = (name == null || name.isEmpty()) ? id : name;
		}
	}

	public static final class CpuPart {
		public final String family;
		public final long hz;
		public final double multi;
		public final String name;

		public CpuPart(String family, long hz, double multi, String name) {
			this.family = family;
			this.hz = hz;
			this.multi = multi;
			this.name = (name == null || name.isEmpty()) ? family : name;
		}
	}

	public static final class CardPart {
		public final CardCategory category;
		public final String id;
		public final String name;

		public CardPart(CardCategory category, String id, String name) {
			this.category = category;
			this.id = id;
			this.name = (name == null || name.isEmpty()) ? id : name;
		}
	}

	public static final class DiskPart {
		/** Image file name inside the hard disk folder. */
		public final String file;
		public final int sizeMb;
		/** The item's custom (anvil) name as Minecraft text JSON, kept so it survives a trip into the PC. */
		public final String customName;

		public DiskPart(String file, int sizeMb) {
			this(file, sizeMb, "");
		}

		public DiskPart(String file, int sizeMb, String customName) {
			this.file = file;
			this.sizeMb = sizeMb;
			this.customName = customName == null ? "" : customName;
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
		if (hardDisks.size() + cdroms.size() > MAX_IDE_DEVICES) {
			out.add(new Problem(true, "Too many drives: hard disks and CD-ROM drives share " + MAX_IDE_DEVICES + " positions."));
		}
		return out;
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
