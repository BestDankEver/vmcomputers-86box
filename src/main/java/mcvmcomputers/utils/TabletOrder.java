package mcvmcomputers.utils;

import java.util.List;
import java.util.UUID;

import net.minecraft.item.ItemStack;

public class TabletOrder {
	/** Ordered items, one stack per item, with NBT (which CPU, which card...). */
	public List<ItemStack> items;
	public OrderStatus currentStatus = OrderStatus.PAYMENT_CHEST_ARRIVAL_SOON;
	public int price;
	public boolean entitySpawned;
	public String orderUUID;
	public float tickCount;

	public TabletOrder() {
		orderUUID = UUID.randomUUID().toString();
	}

	public enum OrderStatus {
		PAYMENT_CHEST_ARRIVAL_SOON,
		PAYMENT_CHEST_ARRIVED,
		PAYMENT_CHEST_RECEIVING,
		ORDER_CHEST_ARRIVAL_SOON,
		ORDER_CHEST_ARRIVED,
		ORDER_CHEST_RECEIVED
	}
}
