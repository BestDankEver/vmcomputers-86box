package mcvmcomputers.item;

import java.util.List;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.registry.Registry;
import net.minecraft.registry.Registries;
import net.minecraft.world.World;

/** A delivered package; right-click to unwrap the item inside (with its NBT, e.g. which CPU it is). */
public class ItemPackage extends Item {
	public ItemPackage(Settings settings) {
		super(settings);
	}


	private static ItemStack contents(ItemStack pkg) {
		NbtCompound t = pkg.getNbt();
		if (t == null || !t.contains("packaged_item")) {
			return ItemStack.EMPTY;
		}
		Item item = Registries.ITEM.get(new Identifier(t.getString("packaged_item")));
		ItemStack s = new ItemStack(item);
		if (t.contains("packaged_tag", 10)) {
			s.setNbt(t.getCompound("packaged_tag").copy());
		}
		return s;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack is = user.getStackInHand(hand);
		ItemStack inside = contents(is);
		if (inside.isEmpty()) {
			return TypedActionResult.fail(is);
		}
		if (is.getCount() == 1) {
			// The last package: the item takes its place in the hand. (Handing it out and
			// returning the used-up package made Minecraft clear the slot the item went into.)
			return TypedActionResult.success(inside, world.isClient);
		}
		is.decrement(1);
		if (!world.isClient) {
			user.getInventory().offerOrDrop(inside);
		}
		return TypedActionResult.success(is, world.isClient);
	}

	@Override
	public Text getName(ItemStack stack) {
		ItemStack inside = contents(stack);
		if (!inside.isEmpty()) {
			return Text.translatable("mcvmcomputers.packaged").formatted(Formatting.GRAY)
					.append(inside.getName().copy().formatted(Formatting.GREEN));
		}
		return Text.translatable("mcvmcomputers.invalid_package").formatted(Formatting.RED);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.translatable("mcvmcomputers.open_with_right_click").formatted(Formatting.GRAY));
	}

	public static ItemStack createPackage(ItemStack contents) {
		ItemStack is = new ItemStack(ItemList.ITEM_PACKAGE);
		NbtCompound ct = is.getOrCreateNbt();
		ct.putString("packaged_item", Registries.ITEM.getId(contents.getItem()).toString());
		if (contents.getNbt() != null) {
			ct.put("packaged_tag", contents.getNbt().copy());
		}
		return is;
	}
}
