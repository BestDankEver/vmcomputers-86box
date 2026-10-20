package mcvmcomputers.emu;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything 86Box can emulate, as generated from an 86Box build by
 * tools/catalog (catalog.json in each nightly release).
 *
 * The catalog carries 86Box's own answers about compatibility (which CPUs a
 * board takes, which cards fit which board), so the mod never has to know
 * the rules itself and keeps working as 86Box adds hardware.
 */
public final class Catalog {
	public static final int FORMAT = 1;

	/** Expansion card categories and the 86Box config keys they map to. */
	public enum CardCategory {
		VIDEO("video", "Video card"),
		SOUND("sound", "Sound card"),
		NETWORK("network", "Network card"),
		SCSI("scsi", "SCSI controller"),
		HDC("hdc", "Hard disk controller"),
		FDC("fdc", "Floppy controller");

		public final String key;
		public final String label;

		CardCategory(String key, String label) {
			this.key = key;
			this.label = label;
		}

		public static CardCategory byKey(String key) {
			for (CardCategory c : values()) {
				if (c.key.equals(key)) {
					return c;
				}
			}
			return null;
		}
	}

	public static final class MachineType {
		public final int id;
		public final String name;

		MachineType(int id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	public static final class CpuModel {
		public final int index;
		public final String name;
		public final long hz;
		public final double multi;

		CpuModel(int index, String name, long hz, double multi) {
			this.index = index;
			this.name = name;
			this.hz = hz;
			this.multi = multi;
		}

		/** "75 MHz" style label from the clock. */
		public String speedLabel() {
			double mhz = hz / 1_000_000.0;
			if (Math.abs(mhz - Math.round(mhz)) < 0.05) {
				return Math.round(mhz) + " MHz";
			}
			return String.format(Locale.ROOT, "%.2f MHz", mhz);
		}
	}

	public static final class CpuFamily {
		public final int index;
		public final String id;
		public final String vendor;
		public final String name;
		public final List<CpuModel> cpus;

		CpuFamily(int index, String id, String vendor, String name, List<CpuModel> cpus) {
			this.index = index;
			this.id = id;
			this.vendor = vendor;
			this.name = name;
			this.cpus = cpus;
		}

		public String displayName() {
			return (vendor == null || vendor.isEmpty()) ? name : vendor + " " + name;
		}

		public CpuModel byClock(long hz, double multi) {
			CpuModel best = null;
			for (CpuModel c : cpus) {
				if (c.hz == hz) {
					if (Math.abs(c.multi - multi) < 0.001) {
						return c;
					}
					if (best == null) {
						best = c;
					}
				}
			}
			return best;
		}
	}

	public static final class Machine {
		public final int index;
		public final String id;
		public final String name;
		public final int type;
		public final long bus;
		public final long flags;
		/** RAM limits in KB, as 86Box's mem_size. */
		public final int ramMinKb, ramMaxKb, ramStepKb;
		public final String onboardVideo, onboardSound, onboardNetwork;
		public final boolean videoFixed;
		/** CPU family index to the CPU indices this board accepts. */
		public final Map<Integer, int[]> cpus;

		Machine(int index, String id, String name, int type, long bus, long flags, int ramMinKb, int ramMaxKb,
				int ramStepKb, String onboardVideo, String onboardSound, String onboardNetwork, boolean videoFixed,
				Map<Integer, int[]> cpus) {
			this.index = index;
			this.id = id;
			this.name = name;
			this.type = type;
			this.bus = bus;
			this.flags = flags;
			this.ramMinKb = ramMinKb;
			this.ramMaxKb = ramMaxKb;
			this.ramStepKb = ramStepKb;
			this.onboardVideo = onboardVideo;
			this.onboardSound = onboardSound;
			this.onboardNetwork = onboardNetwork;
			this.videoFixed = videoFixed;
			this.cpus = cpus;
		}

		public boolean hasFlag(long flag) {
			return (flags & flag) != 0;
		}

		public boolean hasBus(long b) {
			return (bus & b) != 0;
		}
	}

	public static final class Card {
		public final CardCategory category;
		public final String id;
		public final String name;
		public final long deviceFlags;
		private final BitSet machines;

		Card(CardCategory category, String id, String name, long deviceFlags, BitSet machines) {
			this.category = category;
			this.id = id;
			this.name = name;
			this.deviceFlags = deviceFlags;
			this.machines = machines;
		}

		public boolean fits(Machine m) {
			return m != null && machines.get(m.index);
		}
	}

	public static final class DriveType {
		public final String id;
		public final String name;
		public final int bus;
		public final int speed;

		DriveType(String id, String name, int bus, int speed) {
			this.id = id;
			this.name = name;
			this.bus = bus;
			this.speed = speed;
		}
	}

	// Machine flags from 86Box's machine.h that the mod cares about.
	public static final long MACHINE_VIDEO = 0x2L;
	public static final long MACHINE_VIDEO_ONLY = 0x8L;
	public static final long MACHINE_FDC = 0x80L;
	public static final long MACHINE_SOUND = 0x20000L;
	public static final long MACHINE_NIC_PRI = 0x40000L;
	public static final long MACHINE_MFM = 0x800000L;
	public static final long MACHINE_XTA = 0x1000000L;
	public static final long MACHINE_ESDI = 0x2000000L;
	public static final long MACHINE_IDE_PRI = 0x4000000L;
	public static final long MACHINE_IDE_SEC = 0x8000000L;
	public static final long MACHINE_SCSI = 0x40000000L;
	public static final long MACHINE_BUS_ISA16 = 0x20L;
	public static final long MACHINE_BUS_PS2_PORTS = 0x200L;

	public final String emulatorVersion;
	public final String emulatorGitHash;
	public final long generated;
	public final List<MachineType> machineTypes;
	public final List<CpuFamily> cpuFamilies;
	public final List<Machine> machines;
	public final Map<CardCategory, List<Card>> cards;
	public final List<DriveType> floppyDrives;
	public final List<DriveType> cdromDrives;

	private final Map<String, Machine> machinesById = new HashMap<>();
	private final Map<String, CpuFamily> familiesById = new HashMap<>();
	private final Map<String, Card> cardsByKey = new HashMap<>();
	private final Map<String, DriveType> floppyById = new HashMap<>();
	private final Map<String, DriveType> cdromById = new HashMap<>();

	private Catalog(Map<String, Object> root) {
		int format = Json.integer(root, "format", -1);
		if (format != FORMAT) {
			throw new IllegalArgumentException("Unsupported catalog format " + format);
		}
		emulatorVersion = Json.str(root, "emulator_version", "?");
		emulatorGitHash = Json.str(root, "emulator_git_hash", "");
		generated = Json.lng(root, "generated", 0);

		List<MachineType> types = new ArrayList<>();
		for (Object o : Json.arr(root, "machine_types")) {
			Map<String, Object> m = asObj(o);
			types.add(new MachineType(Json.integer(m, "id", 0), Json.str(m, "name", "")));
		}
		machineTypes = Collections.unmodifiableList(types);

		List<CpuFamily> fams = new ArrayList<>();
		for (Object o : Json.arr(root, "cpu_families")) {
			Map<String, Object> m = asObj(o);
			List<CpuModel> cpus = new ArrayList<>();
			for (Object c : Json.arr(m, "cpus")) {
				Map<String, Object> cm = asObj(c);
				cpus.add(new CpuModel(cpus.size(), Json.str(cm, "name", ""), Json.lng(cm, "hz", 0),
						Json.dbl(cm, "multi", 1)));
			}
			CpuFamily f = new CpuFamily(fams.size(), Json.str(m, "id", ""), Json.str(m, "vendor", ""),
					Json.str(m, "name", ""), Collections.unmodifiableList(cpus));
			fams.add(f);
			familiesById.put(f.id, f);
		}
		cpuFamilies = Collections.unmodifiableList(fams);

		List<Machine> ms = new ArrayList<>();
		for (Object o : Json.arr(root, "machines")) {
			Map<String, Object> m = asObj(o);
			Map<String, Object> ram = Json.obj(m, "ram");
			Map<Integer, int[]> cpus = new HashMap<>();
			for (Object e : Json.arr(m, "cpus")) {
				@SuppressWarnings("unchecked")
				List<Object> pair = (List<Object>) e;
				int fam = ((Number) pair.get(0)).intValue();
				@SuppressWarnings("unchecked")
				List<Object> idx = (List<Object>) pair.get(1);
				int[] a = new int[idx.size()];
				for (int k = 0; k < a.length; k++) {
					a[k] = ((Number) idx.get(k)).intValue();
				}
				cpus.put(fam, a);
			}
			Machine mach = new Machine(ms.size(), Json.str(m, "id", ""), Json.str(m, "name", ""),
					Json.integer(m, "type", 0), Json.lng(m, "bus", 0),
					Long.parseUnsignedLong(Json.str(m, "flags", "0"), 16),
					ram == null ? 0 : Json.integer(ram, "min", 0), ram == null ? 0 : Json.integer(ram, "max", 0),
					ram == null ? 0 : Json.integer(ram, "step", 0), Json.str(m, "onboard_video", null),
					Json.str(m, "onboard_sound", null), Json.str(m, "onboard_network", null),
					Json.bool(m, "video_fixed", false), cpus);
			ms.add(mach);
			machinesById.put(mach.id, mach);
		}
		machines = Collections.unmodifiableList(ms);

		Map<CardCategory, List<Card>> cardMap = new EnumMap<>(CardCategory.class);
		Map<String, Object> cardsObj = Json.obj(root, "cards");
		for (CardCategory cat : CardCategory.values()) {
			List<Card> list = new ArrayList<>();
			if (cardsObj != null) {
				for (Object o : Json.arr(cardsObj, cat.key)) {
					Map<String, Object> m = asObj(o);
					Card c = new Card(cat, Json.str(m, "id", ""), Json.str(m, "name", ""),
							Json.lng(m, "flags", 0), hexBits(Json.str(m, "machines", ""), ms.size()));
					list.add(c);
					cardsByKey.put(cat.key + ":" + c.id, c);
				}
			}
			cardMap.put(cat, Collections.unmodifiableList(list));
		}
		cards = Collections.unmodifiableMap(cardMap);

		List<DriveType> fdds = new ArrayList<>();
		for (Object o : Json.arr(root, "floppy_drives")) {
			Map<String, Object> m = asObj(o);
			DriveType d = new DriveType(Json.str(m, "id", ""), Json.str(m, "name", ""), 0, 0);
			fdds.add(d);
			floppyById.put(d.id, d);
		}
		floppyDrives = Collections.unmodifiableList(fdds);

		List<DriveType> cds = new ArrayList<>();
		for (Object o : Json.arr(root, "cdrom_drives")) {
			Map<String, Object> m = asObj(o);
			DriveType d = new DriveType(Json.str(m, "id", ""), Json.str(m, "name", "").trim(),
					Json.integer(m, "bus", 0), Json.integer(m, "speed", 0));
			cds.add(d);
			cdromById.put(d.id, d);
		}
		cdromDrives = Collections.unmodifiableList(cds);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asObj(Object o) {
		return (Map<String, Object>) o;
	}

	/** Bit i set = valid on machine i; 4 machines per hex digit, most significant bit first. */
	static BitSet hexBits(String hex, int count) {
		BitSet b = new BitSet(count);
		for (int i = 0; i < count; i++) {
			int c = i / 4;
			if (c >= hex.length()) {
				break;
			}
			int nib = Character.digit(hex.charAt(c), 16);
			if (((nib >> (3 - (i % 4))) & 1) != 0) {
				b.set(i);
			}
		}
		return b;
	}

	public static Catalog load(File f) throws IOException {
		String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
		return new Catalog(Json.parseObject(text));
	}

	public static Catalog parse(String text) {
		return new Catalog(Json.parseObject(text));
	}

	// ---- Lookups -----------------------------------------------------------------

	public Machine machine(String id) {
		return id == null ? null : machinesById.get(id);
	}

	public CpuFamily cpuFamily(String id) {
		return id == null ? null : familiesById.get(id);
	}

	public Card card(CardCategory cat, String id) {
		return (cat == null || id == null) ? null : cardsByKey.get(cat.key + ":" + id);
	}

	public DriveType floppyDrive(String id) {
		return id == null ? null : floppyById.get(id);
	}

	public DriveType cdromDrive(String id) {
		return id == null ? null : cdromById.get(id);
	}

	public String machineTypeName(int type) {
		for (MachineType t : machineTypes) {
			if (t.id == type) {
				return t.name;
			}
		}
		return "";
	}

	/** Whether the board takes this CPU (family + speed), per 86Box's cpu_is_eligible(). */
	public boolean cpuFits(Machine m, String familyId, long hz, double multi) {
		CpuFamily f = cpuFamily(familyId);
		if (m == null || f == null) {
			return false;
		}
		int[] ok = m.cpus.get(f.index);
		if (ok == null) {
			return false;
		}
		CpuModel c = f.byClock(hz, multi);
		if (c == null) {
			return false;
		}
		for (int i : ok) {
			if (i == c.index) {
				return true;
			}
		}
		return false;
	}

	/** All CPUs this board takes, as (family, model) pairs. */
	public List<CpuChoice> cpusFor(Machine m) {
		List<CpuChoice> out = new ArrayList<>();
		if (m == null) {
			return out;
		}
		for (CpuFamily f : cpuFamilies) {
			int[] ok = m.cpus.get(f.index);
			if (ok == null) {
				continue;
			}
			for (int i : ok) {
				if (i >= 0 && i < f.cpus.size()) {
					out.add(new CpuChoice(f, f.cpus.get(i)));
				}
			}
		}
		return out;
	}

	public static final class CpuChoice {
		public final CpuFamily family;
		public final CpuModel model;

		CpuChoice(CpuFamily family, CpuModel model) {
			this.family = family;
			this.model = model;
		}

		public String displayName() {
			return family.displayName() + " " + model.speedLabel();
		}
	}

	/** Whether the board can take hard disks without an add-in controller. */
	public static boolean hasOnboardIde(Machine m) {
		return m != null && m.hasFlag(MACHINE_IDE_PRI);
	}

	public static boolean hasOnboardFdc(Machine m) {
		return m != null && m.hasFlag(MACHINE_FDC);
	}
}
