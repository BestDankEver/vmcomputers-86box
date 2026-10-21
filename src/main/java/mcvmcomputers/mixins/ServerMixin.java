package mcvmcomputers.mixins;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mcvmcomputers.MainMod;
import mcvmcomputers.entities.EntityDeliveryChest;
import mcvmcomputers.networking.PacketList;
import mcvmcomputers.utils.TabletOrder;
import mcvmcomputers.utils.TabletOrder.OrderStatus;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

@Mixin(MinecraftServer.class)
public class ServerMixin {
	private static final Logger LOGGER = LogManager.getLogger();

	@Shadow
	private float tickTime;

	@Shadow
	private PlayerManager playerManager;

	@Inject(at = @At("HEAD"), method = "shutdown")
	protected void shutdown(CallbackInfo ci) {
		LOGGER.info("Stopping VM Computers");
		MainMod.computers.clear();
		MainMod.orders.clear();
	}

	@Inject(at = @At("HEAD"), method = "tick")
	protected void tick(CallbackInfo ci) {
		Iterator<Map.Entry<UUID, TabletOrder>> it = MainMod.orders.entrySet().iterator();
		while (it.hasNext()) {
			TabletOrder order = it.next().getValue();
			ServerPlayerEntity p = playerManager.getPlayer(UUID.fromString(order.orderUUID));
			if (p == null) {
				continue; // owner is offline; the order waits
			}
			float seconds = tickTime / 1000f;
			if (order.currentStatus == OrderStatus.ORDER_CHEST_ARRIVAL_SOON) {
				order.tickCount += seconds;
				if (order.tickCount > 5) {
					order.currentStatus = OrderStatus.ORDER_CHEST_ARRIVED;
					order.tickCount = 0;
				}
			} else if (order.currentStatus == OrderStatus.PAYMENT_CHEST_ARRIVAL_SOON) {
				order.tickCount += seconds;
				if (order.tickCount > 5) {
					order.currentStatus = OrderStatus.PAYMENT_CHEST_ARRIVED;
					order.tickCount = 0;
				}
			} else if (order.currentStatus == OrderStatus.ORDER_CHEST_RECEIVED) {
				order.tickCount += seconds;
				if (order.tickCount > 0.25) {
					sync(p, order);
					it.remove();
					continue;
				}
			} else if (order.currentStatus == OrderStatus.ORDER_CHEST_ARRIVED || order.currentStatus == OrderStatus.PAYMENT_CHEST_ARRIVED) {
				if (!order.entitySpawned) {
					p.getWorld().spawnEntity(new EntityDeliveryChest(p.getWorld(), new Vec3d(p.getX(), p.getY(), p.getZ()), p.getUuid()));
					order.entitySpawned = true;
				}
			}
			sync(p, order);
		}
	}

	private static void sync(ServerPlayerEntity p, TabletOrder order) {
		PacketByteBuf pb = PacketByteBufs.create();
		pb.writeInt(order.items.size());
		for (ItemStack s : order.items) {
			pb.writeItemStack(s);
		}
		pb.writeInt(order.price);
		pb.writeInt(order.currentStatus.ordinal());
		ServerPlayNetworking.send(p, PacketList.S2C_SYNC_ORDER, pb);
	}
}
