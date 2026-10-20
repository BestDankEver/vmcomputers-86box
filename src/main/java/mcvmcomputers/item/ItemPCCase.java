package mcvmcomputers.item;

import java.util.List;

import mcvmcomputers.client.ClientMod;
import mcvmcomputers.emu.HardwareSpec;
import mcvmcomputers.emu.HardwareSpec.CardPart;
import mcvmcomputers.entities.EntityPC;
import mcvmcomputers.utils.HardwareNbt;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** A PC case. Picking up a built PC gives a case item that keeps its parts. */
public class ItemPCCase extends OrderableItem {
	private final boolean glass;

	public ItemPCCase(Settings settings) {
		this(settings, 2, false);
	}

	protected ItemPCCase(Settings settings, int price, boolean glass) {
		super(settings, price);
		this.glass = glass;
	}

	@Override
	public boolean shouldSyncTagToClient() {
		return true;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (hand != Hand.MAIN_HAND) {
			return new TypedActionResult<>(ActionResult.PASS, stack);
		}
		if (!world.isClient) {
			CompoundTag tag = stack.getTag() == null ? null : stack.getTag().copy();
			HitResult hr = user.raycast(10, 0f, false);
			EntityPC pc = new EntityPC(world, hr.getPos().getX(), hr.getPos().getY(), hr.getPos().getZ(),
					new Vec3d(user.getPos().x, hr.getPos().getY(), user.getPos().z), user.getUuid(), glass, tag);
			world.spawnEntity(pc);
			stack.decrement(1);
		} else if (ClientMod.thePreviewEntity != null) {
			world.playSound(ClientMod.thePreviewEntity.getX(), ClientMod.thePreviewEntity.getY(), ClientMod.thePreviewEntity.getZ(),
					SoundEvents.BLOCK_METAL_PLACE, SoundCategory.BLOCKS, 1, 1, true);
		}
		return new TypedActionResult<>(ActionResult.SUCCESS, stack);
	}

	private static HardwareSpec specOf(ItemStack stack) {
		CompoundTag t = stack.getTag();
		return (t != null && t.contains("Hardware", 10)) ? HardwareNbt.read(t.getCompound("Hardware")) : null;
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		HardwareSpec s = specOf(stack);
		if (s == null || s.isEmpty()) {
			return;
		}
		if (s.motherboard != null) {
			tooltip.add(new LiteralText(s.motherboard.name).formatted(Formatting.GRAY));
		}
		if (s.cpu != null) {
			tooltip.add(new LiteralText(s.cpu.name).formatted(Formatting.GRAY));
		}
		if (!s.ramKb.isEmpty()) {
			tooltip.add(new TranslatableText("mcvmcomputers.pc_item_ram", HardwareSpec.ramLabel(s.totalRamKb())).formatted(Formatting.GRAY));
		}
		for (CardPart c : s.cards) {
			tooltip.add(new LiteralText(c.name).formatted(Formatting.DARK_GRAY));
		}
		for (HardwareSpec.DiskPart d : s.hardDisks) {
			tooltip.add(new TranslatableText("mcvmcomputers.pc_item_hdd", d.file).formatted(Formatting.DARK_GRAY));
		}
	}

	@Override
	public Text getName(ItemStack stack) {
		HardwareSpec s = specOf(stack);
		if (s != null && !s.isEmpty()) {
			return new TranslatableText("mcvmcomputers.pc_item_built");
		}
		return super.getName(stack);
	}

	public static ItemStack createPCStackByEntity(EntityPC pc) {
		return stackFor(pc, ItemList.PC_CASE);
	}

	protected static ItemStack stackFor(EntityPC pc, OrderableItem item) {
		ItemStack is = new ItemStack(item);
		CompoundTag ct = is.getOrCreateTag();
		ct.put("Hardware", pc.getHardwareTag().copy());
		ct.putString("PcId", pc.getPcId());
		return is;
	}
}
