package mcvmcomputers.item;

import mcvmcomputers.entities.EntityPC;
import net.minecraft.item.ItemStack;

/** A PC case with a glass side panel. */
public class ItemPCCaseSidepanel extends ItemPCCase {
	public ItemPCCaseSidepanel(Settings settings) {
		super(settings, 6, true);
	}

	public static ItemStack createPCStackByEntity(EntityPC pc) {
		return stackFor(pc, ItemList.PC_CASE_SIDEPANEL);
	}
}
