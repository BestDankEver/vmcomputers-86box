package mcvmcomputers.utils;

import mcvmcomputers.emu.Catalog.CardCategory;
import mcvmcomputers.emu.HardwareSpec;
import mcvmcomputers.emu.HardwareSpec.CardPart;
import mcvmcomputers.emu.HardwareSpec.CpuPart;
import mcvmcomputers.emu.HardwareSpec.DiskPart;
import mcvmcomputers.emu.HardwareSpec.Part;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

/** Stores a {@link HardwareSpec} in NBT (PC entities, built PC items). */
public final class HardwareNbt {
	private static final int TAG_STRING = 8;
	private static final int TAG_COMPOUND = 10;

	private HardwareNbt() {
	}

	public static NbtCompound write(HardwareSpec s) {
		NbtCompound t = new NbtCompound();
		if (s.motherboard != null) {
			t.put("Motherboard", partToTag(s.motherboard));
		}
		if (s.cpu != null) {
			NbtCompound c = new NbtCompound();
			c.putString("Family", s.cpu.family);
			c.putLong("Hz", s.cpu.hz);
			c.putDouble("Multi", s.cpu.multi);
			c.putString("Name", s.cpu.name);
			if (!s.cpu.fpu.isEmpty()) {
				c.putString("Fpu", s.cpu.fpu);
			}
			t.put("Cpu", c);
		}
		int[] ram = new int[s.ramKb.size()];
		for (int i = 0; i < ram.length; i++) {
			ram[i] = s.ramKb.get(i);
		}
		t.putIntArray("RamKb", ram);
		NbtList cards = new NbtList();
		for (CardPart c : s.cards) {
			NbtCompound ct = new NbtCompound();
			ct.putString("Category", c.category.key);
			ct.putString("Id", c.id);
			ct.putString("Name", c.name);
			if (!c.options.isEmpty()) {
				ct.put("Options", optionsToTag(c.options));
			}
			cards.add(ct);
		}
		t.put("Cards", cards);
		t.put("Floppies", parts(s.floppies));
		t.put("Cdroms", parts(s.cdroms));
		NbtList disks = new NbtList();
		for (DiskPart d : s.hardDisks) {
			NbtCompound dt = diskToTag(d);
			if (!d.customName.isEmpty()) {
				dt.putString("CustomName", d.customName);
			}
			disks.add(dt);
		}
		t.put("HardDisks", disks);
		if (s.keyboard != null) {
			t.put("Keyboard", partToTag(s.keyboard));
		}
		if (s.mouse != null) {
			t.put("Mouse", partToTag(s.mouse));
		}
		t.put("FloppyImages", strings(s.floppyImages));
		t.put("CdImages", strings(s.cdImages));
		return t;
	}

	public static HardwareSpec read(NbtCompound t) {
		HardwareSpec s = new HardwareSpec();
		if (t == null) {
			return s;
		}
		if (t.contains("Motherboard", TAG_COMPOUND)) {
			s.motherboard = tagToPart(t.getCompound("Motherboard"));
		}
		if (t.contains("Cpu", TAG_COMPOUND)) {
			NbtCompound c = t.getCompound("Cpu");
			s.cpu = new CpuPart(c.getString("Family"), c.getLong("Hz"), c.getDouble("Multi"), c.getString("Name"), c.getString("Fpu"));
		}
		for (int kb : t.getIntArray("RamKb")) {
			if (kb > 0) {
				s.ramKb.add(kb);
			}
		}
		NbtList cards = t.getList("Cards", TAG_COMPOUND);
		for (int i = 0; i < cards.size(); i++) {
			NbtCompound ct = cards.getCompound(i);
			CardCategory cat = CardCategory.byKey(ct.getString("Category"));
			if (cat != null) {
				s.cards.add(new CardPart(cat, ct.getString("Id"), ct.getString("Name"), tagToOptions(ct.getCompound("Options"))));
			}
		}
		readParts(t.getList("Floppies", TAG_COMPOUND), s.floppies);
		readParts(t.getList("Cdroms", TAG_COMPOUND), s.cdroms);
		NbtList disks = t.getList("HardDisks", TAG_COMPOUND);
		for (int i = 0; i < disks.size(); i++) {
			NbtCompound dt = disks.getCompound(i);
			s.hardDisks.add(tagToDisk(dt, dt.getString("CustomName")));
		}
		if (t.contains("Keyboard", TAG_COMPOUND)) {
			s.keyboard = tagToPart(t.getCompound("Keyboard"));
		}
		if (t.contains("Mouse", TAG_COMPOUND)) {
			s.mouse = tagToPart(t.getCompound("Mouse"));
		}
		NbtList fi = t.getList("FloppyImages", TAG_STRING);
		for (int i = 0; i < fi.size(); i++) {
			s.floppyImages.add(fi.getString(i));
		}
		NbtList ci = t.getList("CdImages", TAG_STRING);
		for (int i = 0; i < ci.size(); i++) {
			s.cdImages.add(ci.getString(i));
		}
		return s;
	}

	/** A card's 86Box settings, name to value. */
	public static NbtCompound optionsToTag(java.util.Map<String, String> options) {
		NbtCompound t = new NbtCompound();
		for (java.util.Map.Entry<String, String> e : options.entrySet()) {
			t.putString(e.getKey(), e.getValue());
		}
		return t;
	}

	public static java.util.Map<String, String> tagToOptions(NbtCompound t) {
		java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
		if (t != null) {
			for (String k : t.getKeys()) {
				if (t.contains(k, TAG_STRING)) {
					m.put(k, t.getString(k));
				}
			}
		}
		return m;
	}

	/** A hard disk's file, size, geometry, model and format (used by the PC and the Hard Drive item). */
	public static NbtCompound diskToTag(DiskPart d) {
		NbtCompound dt = new NbtCompound();
		dt.putString("File", d.file);
		dt.putInt("SizeMb", d.sizeMb);
		if (d.hasGeometry()) {
			dt.putInt("Cyl", d.cylinders);
			dt.putInt("Heads", d.heads);
			dt.putInt("Spt", d.sectors);
		}
		if (!d.model.isEmpty()) {
			dt.putString("Model", d.model);
		}
		if (!DiskPart.RAW.equals(d.format)) {
			dt.putString("Format", d.format);
		}
		if (!d.parent.isEmpty()) {
			dt.putString("Parent", d.parent);
		}
		return dt;
	}

	public static DiskPart tagToDisk(NbtCompound dt, String customName) {
		return new DiskPart(dt.getString("File"), dt.getInt("SizeMb"), customName, dt.getInt("Cyl"), dt.getInt("Heads"),
				dt.getInt("Spt"), dt.getString("Model"), dt.getString("Format"), dt.getString("Parent"));
	}

	public static NbtCompound partToTag(Part p) {
		NbtCompound t = new NbtCompound();
		t.putString("Id", p.id);
		t.putString("Name", p.name);
		if (!p.options.isEmpty()) {
			t.put("Options", optionsToTag(p.options));
		}
		return t;
	}

	public static Part tagToPart(NbtCompound t) {
		return new Part(t.getString("Id"), t.getString("Name"), tagToOptions(t.getCompound("Options")));
	}

	private static NbtList parts(java.util.List<Part> list) {
		NbtList l = new NbtList();
		for (Part p : list) {
			l.add(partToTag(p));
		}
		return l;
	}

	private static void readParts(NbtList l, java.util.List<Part> into) {
		for (int i = 0; i < l.size(); i++) {
			into.add(tagToPart(l.getCompound(i)));
		}
	}

	private static NbtList strings(java.util.List<String> list) {
		NbtList l = new NbtList();
		for (String s : list) {
			l.add(NbtString.of(s == null ? "" : s));
		}
		return l;
	}
}
