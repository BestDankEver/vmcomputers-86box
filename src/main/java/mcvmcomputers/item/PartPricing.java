package mcvmcomputers.item;

import mcvmcomputers.emu.Catalog.CardCategory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;

/**
 * Prices in iron ingots, worked out from the part's own NBT so the server can
 * price an order without having the catalog. Newer and faster parts cost more.
 */
public final class PartPricing {
	private PartPricing() {
	}

	public static int price(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		if (!(stack.getItem() instanceof ItemPart)) {
			return stack.getItem() instanceof OrderableItem ? ((OrderableItem) stack.getItem()).getPrice() : 0;
		}
		ItemPart item = (ItemPart) stack.getItem();
		CompoundTag p = ItemPart.partTag(stack);
		if (p == null) {
			return 1;
		}
		switch (item.kind) {
		case MOTHERBOARD:
			// Machine types run from 1979-era 8088 boards to 1998-era Slot 1/Socket 370.
			return clamp(3 + p.getInt("Type") / 3, 3, 14);
		case CPU: {
			long mhz = p.getLong("Hz") / 1_000_000L;
			return clamp(2 + (int) (mhz / 40), 2, 20);
		}
		case RAM: {
			int kb = p.getInt("Kb");
			int bits = 32 - Integer.numberOfLeadingZeros(Math.max(1, kb / 1024)); // ~log2(MB) + 1
			return clamp(1 + bits, 1, 12);
		}
		case CARD: {
			CardCategory c = CardCategory.byKey(p.getString("Category"));
			if (c == null) {
				return 3;
			}
			switch (c) {
			case VIDEO:
				return 6;
			case SOUND:
				return 4;
			case NETWORK:
				return 3;
			case SCSI:
				return 5;
			case HDC:
				return 2;
			default:
				return 2;
			}
		}
		case FLOPPY_DRIVE:
			return 2;
		case CDROM_DRIVE:
			return 3;
		default:
			return 1;
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
