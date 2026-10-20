package mcvmcomputers.item;

import java.util.List;

import mcvmcomputers.MainMod;
import mcvmcomputers.emu.HardwareSpec;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** A hard disk: right-click to create or pick its image file. */
public class ItemHarddrive extends OrderableItem {
	public ItemHarddrive(Settings settings) {
		super(settings, 6);
	}

	@Override
	public boolean shouldSyncTagToClient() {
		return true;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		if (world.isClient) {
			MainMod.hardDriveClick.run();
		}
		return super.use(world, user, hand);
	}

	@Override
	public Text getName(ItemStack stack) {
		String f = fileOf(stack);
		if (!f.isEmpty()) {
			return new TranslatableText("mcvmcomputers.hdd_item_name", f).formatted(Formatting.WHITE);
		}
		return new TranslatableText("mcvmcomputers.hdd_item_name", new TranslatableText("mcvmcomputers.hdd_right_click").getString())
				.formatted(Formatting.WHITE);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		int mb = sizeOf(stack);
		if (mb > 0) {
			tooltip.add(new TranslatableText("mcvmcomputers.hdd_size", HardwareSpec.ramLabel(mb * 1024)).formatted(Formatting.GRAY));
		}
	}

	public static String fileOf(ItemStack stack) {
		CompoundTag t = stack.getTag();
		return (t != null && t.contains("vhdfile")) ? t.getString("vhdfile") : "";
	}

	public static int sizeOf(ItemStack stack) {
		CompoundTag t = stack.getTag();
		return t == null ? 0 : t.getInt("SizeMb");
	}

	/** The anvil name as text JSON, or "" if the drive wasn't renamed. */
	public static String customNameOf(ItemStack stack) {
		CompoundTag t = stack.getTag();
		if (t != null && t.contains("display", 10)) {
			return t.getCompound("display").getString("Name");
		}
		return "";
	}

	public static ItemStack createHardDrive(String fileName, int sizeMb, String customName) {
		ItemStack is = createHardDrive(fileName, sizeMb);
		if (customName != null && !customName.isEmpty()) {
			CompoundTag display = new CompoundTag();
			display.putString("Name", customName);
			is.getOrCreateTag().put("display", display);
		}
		return is;
	}

	public static ItemStack createHardDrive(String fileName, int sizeMb) {
		ItemStack is = new ItemStack(ItemList.ITEM_HARDDRIVE);
		CompoundTag ct = is.getOrCreateTag();
		ct.putString("vhdfile", fileName);
		ct.putInt("SizeMb", sizeMb);
		return is;
	}
}
