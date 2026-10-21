package mcvmcomputers.item;

import java.util.List;

import mcvmcomputers.MainMod;
import mcvmcomputers.emu.HardwareSpec;
import mcvmcomputers.emu.HardwareSpec.DiskPart;
import mcvmcomputers.utils.HardwareNbt;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
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
			return Text.translatable("mcvmcomputers.hdd_item_name", f).formatted(Formatting.WHITE);
		}
		return Text.translatable("mcvmcomputers.hdd_item_name", Text.translatable("mcvmcomputers.hdd_right_click").getString())
				.formatted(Formatting.WHITE);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		int mb = sizeOf(stack);
		if (mb > 0) {
			tooltip.add(Text.translatable("mcvmcomputers.hdd_size", HardwareSpec.ramLabel(mb * 1024)).formatted(Formatting.GRAY));
		}
		if (fileOf(stack).isEmpty()) {
			return;
		}
		DiskPart d = toDisk(stack);
		if (d.hasGeometry()) {
			tooltip.add(Text.literal(d.cylinders + " cylinders, " + d.heads + " heads, " + d.sectors + " sectors").formatted(Formatting.DARK_GRAY));
		}
		if (!d.model.isEmpty()) {
			mcvmcomputers.emu.Catalog cat = MainMod.catalogForTooltips();
			tooltip.add(Text.literal(cat == null ? d.model : cat.hddModelName(d.model)).formatted(Formatting.GRAY));
		}
		if (!DiskPart.RAW.equals(d.format)) {
			String f = DiskPart.VHD_FIXED.equals(d.format) ? "Fixed-size VHD"
					: DiskPart.VHD_DYNAMIC.equals(d.format) ? "Dynamic-size VHD" : "Differencing VHD (based on " + d.parent + ")";
			tooltip.add(Text.literal(f).formatted(Formatting.DARK_GRAY));
		}
	}

	/** Everything about the disk stored on the item. */
	public static DiskPart toDisk(ItemStack stack) {
		NbtCompound t = stack.getNbt();
		if (t == null) {
			return new DiskPart("", 0);
		}
		return new DiskPart(fileOf(stack), sizeOf(stack), customNameOf(stack), t.getInt("Cyl"), t.getInt("Heads"), t.getInt("Spt"),
				t.getString("Model"), t.getString("Format"), t.getString("Parent"));
	}

	/** Stores a disk on the item (keeps its anvil name). */
	public static void setDisk(ItemStack stack, DiskPart d) {
		NbtCompound t = stack.getOrCreateNbt();
		for (String k : new String[] { "Cyl", "Heads", "Spt", "Model", "Format", "Parent" }) {
			t.remove(k);
		}
		NbtCompound dt = HardwareNbt.diskToTag(d);
		t.putString("vhdfile", d.file);
		t.putInt("SizeMb", Math.max(1, d.sizeMb));
		for (String k : new String[] { "Cyl", "Heads", "Spt", "Model", "Format", "Parent" }) {
			if (dt.contains(k)) {
				t.put(k, dt.get(k));
			}
		}
	}

	public static ItemStack fromDisk(DiskPart d) {
		ItemStack is = createHardDrive(d.file, d.sizeMb, d.customName);
		setDisk(is, d);
		return is;
	}

	public static String fileOf(ItemStack stack) {
		NbtCompound t = stack.getNbt();
		return (t != null && t.contains("vhdfile")) ? t.getString("vhdfile") : "";
	}

	public static int sizeOf(ItemStack stack) {
		NbtCompound t = stack.getNbt();
		return t == null ? 0 : t.getInt("SizeMb");
	}

	/** The anvil name as text JSON, or "" if the drive wasn't renamed. */
	public static String customNameOf(ItemStack stack) {
		NbtCompound t = stack.getNbt();
		if (t != null && t.contains("display", 10)) {
			return t.getCompound("display").getString("Name");
		}
		return "";
	}

	public static ItemStack createHardDrive(String fileName, int sizeMb, String customName) {
		ItemStack is = createHardDrive(fileName, sizeMb);
		if (customName != null && !customName.isEmpty()) {
			NbtCompound display = new NbtCompound();
			display.putString("Name", customName);
			is.getOrCreateNbt().put("display", display);
		}
		return is;
	}

	public static ItemStack createHardDrive(String fileName, int sizeMb) {
		ItemStack is = new ItemStack(ItemList.ITEM_HARDDRIVE);
		NbtCompound ct = is.getOrCreateNbt();
		ct.putString("vhdfile", fileName);
		ct.putInt("SizeMb", sizeMb);
		return is;
	}
}
