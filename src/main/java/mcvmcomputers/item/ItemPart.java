package mcvmcomputers.item;

import java.util.List;
import java.util.Map;

import mcvmcomputers.emu.Catalog;
import mcvmcomputers.emu.Catalog.Card;
import mcvmcomputers.emu.Catalog.CardCategory;
import mcvmcomputers.emu.Catalog.CpuFamily;
import mcvmcomputers.emu.Catalog.CpuModel;
import mcvmcomputers.emu.Catalog.DriveType;
import mcvmcomputers.emu.Catalog.Machine;
import mcvmcomputers.emu.HardwareSpec;
import mcvmcomputers.emu.HardwareSpec.CardPart;
import mcvmcomputers.emu.HardwareSpec.CpuPart;
import mcvmcomputers.emu.HardwareSpec.Part;
import mcvmcomputers.utils.HardwareNbt;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

/**
 * A PC part. One item per kind of part; which motherboard, CPU or card it is
 * lives in the stack's "Part" NBT, so every piece of hardware 86Box adds can be
 * bought without the mod adding items.
 *
 * The NBT carries the display name as well as the 86Box id, so the name shows
 * correctly even for players (and servers) that don't have the catalog.
 */
public class ItemPart extends OrderableItem {
	public enum Kind {
		MOTHERBOARD("motherboard"), CPU("cpu"), RAM("ram"), CARD("card"), FLOPPY_DRIVE("floppy_drive"), CDROM_DRIVE(
				"cdrom_drive");

		public final String key;

		Kind(String key) {
			this.key = key;
		}
	}

	public final Kind kind;

	public ItemPart(Settings settings, Kind kind) {
		super(settings, 1);
		this.kind = kind;
	}

	@Override
	public boolean shouldSyncTagToClient() {
		return true;
	}

	@Override
	public int getPrice(ItemStack stack) {
		return PartPricing.price(stack);
	}

	@Override
	public Text getName(ItemStack stack) {
		CompoundTag p = partTag(stack);
		if (p == null) {
			return new TranslatableText(this.getTranslationKey());
		}
		switch (kind) {
		case RAM:
			return new LiteralText(HardwareSpec.ramLabel(p.getInt("Kb")) + " RAM");
		default:
			String n = p.getString("Name");
			return n.isEmpty() ? new TranslatableText(this.getTranslationKey()) : new LiteralText(n);
		}
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		CompoundTag p = partTag(stack);
		tooltip.add(new TranslatableText(kindLabelKey(stack)).formatted(Formatting.GRAY));
		if (p == null) {
			tooltip.add(new TranslatableText("mcvmcomputers.part.empty").formatted(Formatting.DARK_GRAY));
			return;
		}
		Catalog cat = mcvmcomputers.MainMod.catalogForTooltips();
		if (kind == Kind.MOTHERBOARD && cat != null) {
			Machine m = cat.machine(p.getString("Id"));
			if (m != null) {
				tooltip.add(new LiteralText(cat.machineTypeName(m.type)).formatted(Formatting.DARK_GRAY));
				tooltip.add(new TranslatableText("mcvmcomputers.part.ram_range", HardwareSpec.ramLabel(m.ramMinKb),
						HardwareSpec.ramLabel(m.ramMaxKb)).formatted(Formatting.DARK_GRAY));
				if (m.onboardVideo != null) {
					tooltip.add(new TranslatableText("mcvmcomputers.part.onboard", m.onboardVideo).formatted(Formatting.DARK_GRAY));
				}
				if (m.onboardSound != null) {
					tooltip.add(new TranslatableText("mcvmcomputers.part.onboard", m.onboardSound).formatted(Formatting.DARK_GRAY));
				}
			}
		}
		if (kind == Kind.CARD && p.contains("Options", 10)) {
			Map<String, String> opts = HardwareNbt.tagToOptions(p.getCompound("Options"));
			CardCategory cc = CardCategory.byKey(p.getString("Category"));
			Card card = cat == null || cc == null ? null : cat.card(cc, p.getString("Id"));
			if (card != null) {
				String summary = card.optionSummary(opts);
				if (!summary.isEmpty()) {
					tooltip.add(new LiteralText(summary).formatted(Formatting.GRAY));
				}
			} else {
				for (Map.Entry<String, String> e : opts.entrySet()) {
					tooltip.add(new LiteralText(e.getKey() + " = " + e.getValue()).formatted(Formatting.DARK_GRAY));
				}
			}
		}
		tooltip.add(new TranslatableText("mcvmcomputers.part.id", p.getString("Id").isEmpty() ? p.getString("Family") : p.getString("Id"))
				.formatted(Formatting.DARK_GRAY));
	}

	private String kindLabelKey(ItemStack stack) {
		if (kind == Kind.CARD) {
			CompoundTag p = partTag(stack);
			CardCategory c = p == null ? null : CardCategory.byKey(p.getString("Category"));
			if (c != null) {
				return "mcvmcomputers.part.card." + c.key;
			}
		}
		return "mcvmcomputers.part.kind." + kind.key;
	}

	// ---- NBT helpers ---------------------------------------------------------------

	public static CompoundTag partTag(ItemStack stack) {
		if (stack == null || stack.getTag() == null || !stack.getTag().contains("Part", 10)) {
			return null;
		}
		return stack.getTag().getCompound("Part");
	}

	private static ItemStack make(ItemPart item, CompoundTag part) {
		ItemStack s = new ItemStack(item);
		s.getOrCreateTag().put("Part", part);
		return s;
	}

	public static ItemStack motherboard(Machine m) {
		CompoundTag t = new CompoundTag();
		t.putString("Id", m.id);
		t.putString("Name", m.name);
		t.putInt("Type", m.type);
		return make(ItemList.ITEM_MOTHERBOARD, t);
	}

	public static ItemStack cpu(CpuFamily f, CpuModel c) {
		CompoundTag t = new CompoundTag();
		t.putString("Family", f.id);
		t.putLong("Hz", c.hz);
		t.putDouble("Multi", c.multi);
		t.putString("Name", f.displayName() + " " + c.speedLabel());
		return make(ItemList.ITEM_CPU, t);
	}

	public static ItemStack ram(int kb) {
		CompoundTag t = new CompoundTag();
		t.putInt("Kb", kb);
		return make(ItemList.ITEM_RAM, t);
	}

	public static ItemStack card(Card c) {
		return card(c, null);
	}

	/** A card with its 86Box settings (checked against the catalog; anything invalid gets 86Box's default). */
	public static ItemStack card(Card c, Map<String, String> options) {
		CompoundTag t = new CompoundTag();
		t.putString("Category", c.category.key);
		t.putString("Id", c.id);
		if (c.options.isEmpty()) {
			t.putString("Name", c.name);
		} else {
			Map<String, String> resolved = c.resolve(options);
			t.putString("Name", c.displayName(resolved));
			t.put("Options", HardwareNbt.optionsToTag(resolved));
		}
		return make(ItemList.ITEM_CARD, t);
	}

	public static ItemStack floppyDrive(DriveType d) {
		CompoundTag t = new CompoundTag();
		t.putString("Id", d.id);
		t.putString("Name", d.name);
		return make(ItemList.ITEM_FLOPPY_DRIVE, t);
	}

	public static ItemStack cdromDrive(DriveType d) {
		CompoundTag t = new CompoundTag();
		t.putString("Id", d.id);
		t.putString("Name", d.name);
		return make(ItemList.ITEM_CDROM_DRIVE, t);
	}

	/** Back from installed parts to items, for removing parts from a PC. */
	public static ItemStack fromPart(Kind kind, Part p) {
		CompoundTag t = new CompoundTag();
		t.putString("Id", p.id);
		t.putString("Name", p.name);
		ItemPart item = kind == Kind.MOTHERBOARD ? ItemList.ITEM_MOTHERBOARD
				: kind == Kind.FLOPPY_DRIVE ? ItemList.ITEM_FLOPPY_DRIVE : ItemList.ITEM_CDROM_DRIVE;
		return make(item, t);
	}

	public static ItemStack fromCpu(CpuPart c) {
		CompoundTag t = new CompoundTag();
		t.putString("Family", c.family);
		t.putLong("Hz", c.hz);
		t.putDouble("Multi", c.multi);
		t.putString("Name", c.name);
		return make(ItemList.ITEM_CPU, t);
	}

	public static ItemStack fromCard(CardPart c) {
		CompoundTag t = new CompoundTag();
		t.putString("Category", c.category.key);
		t.putString("Id", c.id);
		t.putString("Name", c.name);
		if (!c.options.isEmpty()) {
			t.put("Options", HardwareNbt.optionsToTag(c.options));
		}
		return make(ItemList.ITEM_CARD, t);
	}

	public static Part toPart(ItemStack s) {
		CompoundTag t = partTag(s);
		return t == null ? null : new Part(t.getString("Id"), t.getString("Name"));
	}

	public static CpuPart toCpu(ItemStack s) {
		CompoundTag t = partTag(s);
		return t == null ? null : new CpuPart(t.getString("Family"), t.getLong("Hz"), t.getDouble("Multi"), t.getString("Name"));
	}

	public static CardPart toCard(ItemStack s) {
		CompoundTag t = partTag(s);
		CardCategory c = t == null ? null : CardCategory.byKey(t.getString("Category"));
		return c == null ? null
				: new CardPart(c, t.getString("Id"), t.getString("Name"), HardwareNbt.tagToOptions(t.getCompound("Options")));
	}

	public static int ramKb(ItemStack s) {
		CompoundTag t = partTag(s);
		return t == null ? 0 : t.getInt("Kb");
	}
}
