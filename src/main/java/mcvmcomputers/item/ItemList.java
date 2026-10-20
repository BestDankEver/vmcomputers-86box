package mcvmcomputers.item;

import java.util.Arrays;
import java.util.List;

import mcvmcomputers.entities.EntityCRTScreen;
import mcvmcomputers.entities.EntityFlatScreen;
import mcvmcomputers.entities.EntityKeyboard;
import mcvmcomputers.entities.EntityMouse;
import mcvmcomputers.entities.EntityWallTV;
import mcvmcomputers.item.ItemPart.Kind;
import net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Item.Settings;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.registry.Registry;

public class ItemList {
	public static final ItemGroup MOD_ITEM_GROUP_PARTS = FabricItemGroupBuilder.build(new Identifier("mcvmcomputers", "parts"), () -> new ItemStack(Blocks.WHITE_STAINED_GLASS));
	public static final ItemGroup MOD_ITEM_GROUP_PERIPHERALS = FabricItemGroupBuilder.build(new Identifier("mcvmcomputers", "peripherals"), () -> new ItemStack(Blocks.WHITE_STAINED_GLASS));
	public static final ItemGroup MOD_ITEM_GROUP_OTHERS = FabricItemGroupBuilder.build(new Identifier("mcvmcomputers", "others"), () -> new ItemStack(Blocks.WHITE_STAINED_GLASS));

	public static final OrderableItem PC_CASE_SIDEPANEL = new ItemPCCaseSidepanel(new Settings().group(MOD_ITEM_GROUP_PARTS));
	public static final OrderableItem PC_CASE = new ItemPCCase(new Settings().group(MOD_ITEM_GROUP_PARTS));
	public static final OrderableItem ITEM_HARDDRIVE = new ItemHarddrive(new Settings().group(MOD_ITEM_GROUP_PARTS));

	// PC parts: which part a stack is lives in its NBT (see ItemPart). They are
	// bought from the parts catalog, so they're not in the creative tab.
	public static final ItemPart ITEM_MOTHERBOARD = new ItemPart(new Settings().maxCount(1), Kind.MOTHERBOARD);
	public static final ItemPart ITEM_CPU = new ItemPart(new Settings().maxCount(1), Kind.CPU);
	public static final ItemPart ITEM_RAM = new ItemPart(new Settings().maxCount(1), Kind.RAM);
	public static final ItemPart ITEM_CARD = new ItemPart(new Settings().maxCount(1), Kind.CARD);
	public static final ItemPart ITEM_FLOPPY_DRIVE = new ItemPart(new Settings().maxCount(1), Kind.FLOPPY_DRIVE);
	public static final ItemPart ITEM_CDROM_DRIVE = new ItemPart(new Settings().maxCount(1), Kind.CDROM_DRIVE);

	public static final OrderableItem ITEM_FLATSCREEN = new PlacableOrderableItem(new Settings().group(MOD_ITEM_GROUP_PERIPHERALS), EntityFlatScreen.class, SoundEvents.BLOCK_METAL_PLACE, 10);
	public static final OrderableItem ITEM_WALLTV = new PlacableOrderableItem(new Settings().group(MOD_ITEM_GROUP_PERIPHERALS), EntityWallTV.class, SoundEvents.BLOCK_METAL_PLACE, 14, true);
	public static final OrderableItem ITEM_CRTSCREEN = new PlacableOrderableItem(new Settings().group(MOD_ITEM_GROUP_PERIPHERALS), EntityCRTScreen.class, SoundEvents.BLOCK_METAL_PLACE, 10);
	public static final OrderableItem ITEM_KEYBOARD = new PlacableOrderableItem(new Settings().group(MOD_ITEM_GROUP_PERIPHERALS), EntityKeyboard.class, SoundEvents.BLOCK_METAL_PLACE, 4);
	public static final OrderableItem ITEM_MOUSE = new PlacableOrderableItem(new Settings().group(MOD_ITEM_GROUP_PERIPHERALS), EntityMouse.class, SoundEvents.BLOCK_METAL_PLACE, 4);
	public static final Item ITEM_TABLET = new ItemOrderingTablet(new Settings().group(MOD_ITEM_GROUP_OTHERS).maxCount(1));
	public static final Item ITEM_PACKAGE = new ItemPackage(new Settings().rarity(Rarity.EPIC));
	public static final Item PC_CASE_NO_PANEL = new Item(new Settings().rarity(Rarity.EPIC));
	public static final Item PC_CASE_ONLY_PANEL = new Item(new Settings().rarity(Rarity.EPIC));
	public static final Item PC_CASE_GLASS_PANEL = new Item(new Settings().rarity(Rarity.EPIC));

	public static final List<Item> PLACABLE_ITEMS = Arrays.asList(PC_CASE, PC_CASE_SIDEPANEL, ITEM_KEYBOARD, ITEM_MOUSE, ITEM_CRTSCREEN, ITEM_FLATSCREEN, ITEM_WALLTV);

	/** Things the tablet store sells directly (parts come from the catalog). */
	public static final List<OrderableItem> STORE_CASES = Arrays.asList(PC_CASE, PC_CASE_SIDEPANEL, ITEM_HARDDRIVE);
	public static final List<OrderableItem> STORE_PERIPHERALS = Arrays.asList(ITEM_KEYBOARD, ITEM_MOUSE, ITEM_CRTSCREEN, ITEM_FLATSCREEN, ITEM_WALLTV);

	public static void init() {
		registerItem("pc_case_sidepanel", PC_CASE_SIDEPANEL);
		registerItem("pc_case", PC_CASE);
		registerItem("motherboard", ITEM_MOTHERBOARD);
		registerItem("cpu", ITEM_CPU);
		registerItem("ram", ITEM_RAM);
		registerItem("expansion_card", ITEM_CARD);
		registerItem("floppy_drive", ITEM_FLOPPY_DRIVE);
		registerItem("cdrom_drive", ITEM_CDROM_DRIVE);
		registerItem("walltv", ITEM_WALLTV);
		registerItem("flatscreen", ITEM_FLATSCREEN);
		registerItem("crtscreen", ITEM_CRTSCREEN);
		registerItem("harddrive", ITEM_HARDDRIVE);
		registerItem("keyboard", ITEM_KEYBOARD);
		registerItem("mouse", ITEM_MOUSE);
		registerItem("ordering_tablet", ITEM_TABLET);
		registerItem("package", ITEM_PACKAGE);

		//Visual items - used for rendering only!
		registerItem("pc_case_no_panel", PC_CASE_NO_PANEL);
		registerItem("pc_case_only_panel", PC_CASE_ONLY_PANEL);
		registerItem("pc_case_only_glass_sidepanel", PC_CASE_GLASS_PANEL);
	}

	private static Item registerItem(String id, Item it) {
		Registry.register(Registry.ITEM, new Identifier("mcvmcomputers", id), it);
		return it;
	}
}
