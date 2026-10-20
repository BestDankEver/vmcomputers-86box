package mcvmcomputers.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public class OrderableItem extends Item {
	private final int price;

	public OrderableItem(Settings settings, int price) {
		super(settings);
		this.price = price;
	}

	/**
	 * @return Price in Iron Ingots.
	 */
	public int getPrice() {
		return price;
	}

	/** Price of a particular stack; parts are priced by what they are. */
	public int getPrice(ItemStack stack) {
		return price;
	}
}
