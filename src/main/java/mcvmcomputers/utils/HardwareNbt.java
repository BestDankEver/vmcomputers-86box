package mcvmcomputers.utils;

import mcvmcomputers.emu.Catalog.CardCategory;
import mcvmcomputers.emu.HardwareSpec;
import mcvmcomputers.emu.HardwareSpec.CardPart;
import mcvmcomputers.emu.HardwareSpec.CpuPart;
import mcvmcomputers.emu.HardwareSpec.DiskPart;
import mcvmcomputers.emu.HardwareSpec.Part;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

/** Stores a {@link HardwareSpec} in NBT (PC entities, built PC items). */
public final class HardwareNbt {
	private static final int TAG_STRING = 8;
	private static final int TAG_COMPOUND = 10;

	private HardwareNbt() {
	}

	public static CompoundTag write(HardwareSpec s) {
		CompoundTag t = new CompoundTag();
		if (s.motherboard != null) {
			t.put("Motherboard", partToTag(s.motherboard));
		}
		if (s.cpu != null) {
			CompoundTag c = new CompoundTag();
			c.putString("Family", s.cpu.family);
			c.putLong("Hz", s.cpu.hz);
			c.putDouble("Multi", s.cpu.multi);
			c.putString("Name", s.cpu.name);
			t.put("Cpu", c);
		}
		int[] ram = new int[s.ramKb.size()];
		for (int i = 0; i < ram.length; i++) {
			ram[i] = s.ramKb.get(i);
		}
		t.putIntArray("RamKb", ram);
		ListTag cards = new ListTag();
		for (CardPart c : s.cards) {
			CompoundTag ct = new CompoundTag();
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
		ListTag disks = new ListTag();
		for (DiskPart d : s.hardDisks) {
			CompoundTag dt = new CompoundTag();
			dt.putString("File", d.file);
			dt.putInt("SizeMb", d.sizeMb);
			if (!d.customName.isEmpty()) {
				dt.putString("CustomName", d.customName);
			}
			disks.add(dt);
		}
		t.put("HardDisks", disks);
		t.put("FloppyImages", strings(s.floppyImages));
		t.put("CdImages", strings(s.cdImages));
		return t;
	}

	public static HardwareSpec read(CompoundTag t) {
		HardwareSpec s = new HardwareSpec();
		if (t == null) {
			return s;
		}
		if (t.contains("Motherboard", TAG_COMPOUND)) {
			s.motherboard = tagToPart(t.getCompound("Motherboard"));
		}
		if (t.contains("Cpu", TAG_COMPOUND)) {
			CompoundTag c = t.getCompound("Cpu");
			s.cpu = new CpuPart(c.getString("Family"), c.getLong("Hz"), c.getDouble("Multi"), c.getString("Name"));
		}
		for (int kb : t.getIntArray("RamKb")) {
			if (kb > 0) {
				s.ramKb.add(kb);
			}
		}
		ListTag cards = t.getList("Cards", TAG_COMPOUND);
		for (int i = 0; i < cards.size(); i++) {
			CompoundTag ct = cards.getCompound(i);
			CardCategory cat = CardCategory.byKey(ct.getString("Category"));
			if (cat != null) {
				s.cards.add(new CardPart(cat, ct.getString("Id"), ct.getString("Name"), tagToOptions(ct.getCompound("Options"))));
			}
		}
		readParts(t.getList("Floppies", TAG_COMPOUND), s.floppies);
		readParts(t.getList("Cdroms", TAG_COMPOUND), s.cdroms);
		ListTag disks = t.getList("HardDisks", TAG_COMPOUND);
		for (int i = 0; i < disks.size(); i++) {
			CompoundTag dt = disks.getCompound(i);
			s.hardDisks.add(new DiskPart(dt.getString("File"), dt.getInt("SizeMb"), dt.getString("CustomName")));
		}
		ListTag fi = t.getList("FloppyImages", TAG_STRING);
		for (int i = 0; i < fi.size(); i++) {
			s.floppyImages.add(fi.getString(i));
		}
		ListTag ci = t.getList("CdImages", TAG_STRING);
		for (int i = 0; i < ci.size(); i++) {
			s.cdImages.add(ci.getString(i));
		}
		return s;
	}

	/** A card's 86Box settings, name to value. */
	public static CompoundTag optionsToTag(java.util.Map<String, String> options) {
		CompoundTag t = new CompoundTag();
		for (java.util.Map.Entry<String, String> e : options.entrySet()) {
			t.putString(e.getKey(), e.getValue());
		}
		return t;
	}

	public static java.util.Map<String, String> tagToOptions(CompoundTag t) {
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

	private static CompoundTag partToTag(Part p) {
		CompoundTag t = new CompoundTag();
		t.putString("Id", p.id);
		t.putString("Name", p.name);
		return t;
	}

	private static Part tagToPart(CompoundTag t) {
		return new Part(t.getString("Id"), t.getString("Name"));
	}

	private static ListTag parts(java.util.List<Part> list) {
		ListTag l = new ListTag();
		for (Part p : list) {
			l.add(partToTag(p));
		}
		return l;
	}

	private static void readParts(ListTag l, java.util.List<Part> into) {
		for (int i = 0; i < l.size(); i++) {
			into.add(tagToPart(l.getCompound(i)));
		}
	}

	private static ListTag strings(java.util.List<String> list) {
		ListTag l = new ListTag();
		for (String s : list) {
			l.add(StringTag.of(s == null ? "" : s));
		}
		return l;
	}
}
