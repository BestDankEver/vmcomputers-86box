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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
		FDC("fdc", "Floppy controller"),
		/** Add-on cards that sit next to the main video card: 3dfx Voodoo, 8514/A, XGA, Display Adapter II. */
		ACCEL("accel", "Graphics accelerator");

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

	/** One choice of a {@link CardOption}. Memory limits only apply to variants. */
	public static final class OptionChoice {
		public final String value;
		public final String label;
		/** Limits this variant puts on the "framebuffer_memory"/"memory" option (1) and "texture_memory" (2); -1 = none. */
		public final int memMin, memMax, mem2Min, mem2Max;
		public final boolean memFixed;

		OptionChoice(String value, String label, int memMin, int memMax, int mem2Min, int mem2Max, boolean memFixed) {
			this.value = value;
			this.label = label;
			this.memMin = memMin;
			this.memMax = memMax;
			this.mem2Min = mem2Min;
			this.mem2Max = mem2Max;
			this.memFixed = memFixed;
		}
	}

	/**
	 * A setting from the card's 86Box Configure dialog. Values are strings
	 * exactly as 86Box writes them in its config file.
	 */
	public static final class CardOption {
		public static final String VARIANT = "variant", TOGGLE = "toggle", SELECT = "select", SPINNER = "spinner";

		public final String name;
		public final String label;
		public final String kind;
		public final String def;
		public final List<OptionChoice> choices;
		public final int min, max, step;

		CardOption(String name, String label, String kind, String def, List<OptionChoice> choices, int min, int max, int step) {
			this.name = name;
			this.label = label;
			this.kind = kind;
			this.def = def;
			this.choices = choices;
			this.min = min;
			this.max = max;
			this.step = step;
		}

		public OptionChoice choice(String value) {
			for (OptionChoice c : choices) {
				if (c.value.equals(value)) {
					return c;
				}
			}
			return null;
		}

		/** Which memory option a variant's limits apply to: 1, 2, or 0 for neither. */
		public static int memorySlot(String name) {
			if ("memory".equals(name) || "framebuffer_memory".equals(name)) {
				return 1;
			}
			return "texture_memory".equals(name) ? 2 : 0;
		}

		/** Whether {@code value} is allowed for this option once the variant {@code v} is chosen. */
		public boolean allowed(String value, OptionChoice v) {
			int slot = memorySlot(name);
			if (v == null || slot == 0) {
				return true;
			}
			int n;
			try {
				n = Integer.parseInt(value);
			} catch (NumberFormatException e) {
				return true;
			}
			if (slot == 1) {
				if (v.memFixed) {
					return value.equals(def);
				}
				return (v.memMin < 0 || n >= v.memMin) && (v.memMax < 0 || n <= v.memMax);
			}
			return (v.mem2Min < 0 || n >= v.mem2Min) && (v.mem2Max < 0 || n <= v.mem2Max);
		}
	}

	public static final class Card {
		public final CardCategory category;
		public final String id;
		public final String name;
		public final long deviceFlags;
		private final BitSet machines;
		/** Add-on cards only: the [Video] key that switches it on (e.g. "voodoo"). */
		public final String configKey;
		/** The 86Box config section holding this card's settings (its device name). */
		public final String section;
		/** Add-on cards only: main video cards it can't be used alongside. */
		public final Set<String> conflicts;
		public final List<CardOption> options;

		Card(CardCategory category, String id, String name, long deviceFlags, BitSet machines, String configKey,
				String section, Set<String> conflicts, List<CardOption> options) {
			this.category = category;
			this.id = id;
			this.name = name;
			this.deviceFlags = deviceFlags;
			this.machines = machines;
			this.configKey = configKey;
			this.section = section;
			this.conflicts = conflicts;
			this.options = options;
		}

		public boolean fits(Machine m) {
			return m != null && machines.get(m.index);
		}

		public CardOption option(String name) {
			for (CardOption o : options) {
				if (o.name.equals(name)) {
					return o;
				}
			}
			return null;
		}

		/** The variant chosen in {@code values}, or the default one. */
		public OptionChoice variant(Map<String, String> values) {
			for (CardOption o : options) {
				if (CardOption.VARIANT.equals(o.kind)) {
					String v = values == null ? null : values.get(o.name);
					OptionChoice c = o.choice(v == null ? o.def : v);
					return c != null ? c : (o.choices.isEmpty() ? null : o.choices.get(0));
				}
			}
			return null;
		}

		/**
		 * Every option with a valid value: what {@code values} sets where it is
		 * still allowed, the default otherwise (moved into the variant's memory
		 * limits the way 86Box's Configure dialog does).
		 */
		public Map<String, String> resolve(Map<String, String> values) {
			Map<String, String> out = new LinkedHashMap<>();
			OptionChoice variant = variant(values);
			for (CardOption o : options) {
				String v = values == null ? null : values.get(o.name);
				if (CardOption.VARIANT.equals(o.kind)) {
					out.put(o.name, variant == null ? o.def : variant.value);
					continue;
				}
				if (v == null || !valid(o, v)) {
					v = o.def;
				}
				if (!o.allowed(v, variant)) {
					v = nearestAllowed(o, v, variant);
				}
				out.put(o.name, v);
			}
			return out;
		}

		private static boolean valid(CardOption o, String v) {
			switch (o.kind) {
			case CardOption.TOGGLE:
				return v.equals("0") || v.equals("1");
			case CardOption.SPINNER:
				try {
					int n = Integer.parseInt(v);
					return n >= o.min && n <= o.max;
				} catch (NumberFormatException e) {
					return false;
				}
			default:
				return o.choice(v) != null;
			}
		}

		private static String nearestAllowed(CardOption o, String v, OptionChoice variant) {
			String best = v;
			long bestDist = Long.MAX_VALUE;
			long want;
			try {
				want = Long.parseLong(v);
			} catch (NumberFormatException e) {
				want = 0;
			}
			for (OptionChoice c : o.choices) {
				if (!o.allowed(c.value, variant)) {
					continue;
				}
				long d;
				try {
					d = Math.abs(Long.parseLong(c.value) - want);
				} catch (NumberFormatException e) {
					d = Long.MAX_VALUE - 1;
				}
				if (d < bestDist) {
					bestDist = d;
					best = c.value;
				}
			}
			return best;
		}

		/** The option that picks the model (CONFIG_BIOS), if the card has one. */
		public CardOption variantOption() {
			for (CardOption o : options) {
				if (CardOption.VARIANT.equals(o.kind)) {
					return o;
				}
			}
			return null;
		}

		/** The card's name with the chosen model, e.g. "[PCI] 3Dfx Voodoo 2". */
		public String displayName(Map<String, String> values) {
			OptionChoice v = variant(values);
			if (v == null || v.label.isEmpty()) {
				return name;
			}
			int b = name.indexOf("] ");
			return (name.startsWith("[") && b > 0 ? name.substring(0, b + 2) : "") + v.label;
		}

		/** A short summary of the options that differ from the defaults (the model is in the name). */
		public String optionSummary(Map<String, String> values) {
			StringBuilder b = new StringBuilder();
			Map<String, String> r = resolve(values);
			for (CardOption o : options) {
				String v = r.get(o.name);
				String part = null;
				if (CardOption.VARIANT.equals(o.kind)) {
					continue;
				}
				if (!v.equals(o.def) || CardOption.memorySlot(o.name) != 0) {
					if (CardOption.TOGGLE.equals(o.kind)) {
						part = o.label + (v.equals("1") ? " on" : " off");
					} else if (CardOption.SPINNER.equals(o.kind)) {
						part = o.label + " " + v;
					} else {
						OptionChoice c = o.choice(v);
						part = o.label + ": " + (c == null ? v : c.label);
					}
				}
				if (part != null) {
					if (b.length() > 0) {
						b.append(", ");
					}
					b.append(part);
				}
			}
			return b.toString();
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
					Set<String> conflicts = new HashSet<>();
					for (Object x : Json.arr(m, "conflicts")) {
						if (x instanceof String) {
							conflicts.add((String) x);
						}
					}
					Card c = new Card(cat, Json.str(m, "id", ""), Json.str(m, "name", ""),
							Json.lng(m, "flags", 0), hexBits(Json.str(m, "machines", ""), ms.size()),
							Json.str(m, "key", null), Json.str(m, "section", null),
							Collections.unmodifiableSet(conflicts), parseOptions(Json.arr(m, "options")));
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

	private static List<CardOption> parseOptions(List<Object> arr) {
		List<CardOption> out = new ArrayList<>();
		for (Object o : arr) {
			Map<String, Object> m = asObj(o);
			List<OptionChoice> choices = new ArrayList<>();
			for (Object c : Json.arr(m, "choices")) {
				Map<String, Object> cm = asObj(c);
				choices.add(new OptionChoice(Json.str(cm, "value", ""), Json.str(cm, "label", ""),
						Json.integer(cm, "mem_min", -1), Json.integer(cm, "mem_max", -1),
						Json.integer(cm, "mem2_min", -1), Json.integer(cm, "mem2_max", -1),
						Json.bool(cm, "mem_fixed", false)));
			}
			String kind = Json.str(m, "kind", "");
			if ((CardOption.VARIANT.equals(kind) || CardOption.SELECT.equals(kind)) && choices.isEmpty()) {
				continue;
			}
			out.add(new CardOption(Json.str(m, "name", ""), Json.str(m, "label", ""), kind, Json.str(m, "default", ""),
					Collections.unmodifiableList(choices), Json.integer(m, "min", 0), Json.integer(m, "max", 0),
					Json.integer(m, "step", 1)));
		}
		return Collections.unmodifiableList(out);
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
