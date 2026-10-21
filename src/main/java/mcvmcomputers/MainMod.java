package mcvmcomputers;

import static mcvmcomputers.networking.PacketList.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import mcvmcomputers.emu.Catalog;
import mcvmcomputers.emu.HardwareSpec;
import mcvmcomputers.emu.HardwareSpec.CardPart;
import mcvmcomputers.emu.HardwareSpec.DiskPart;
import mcvmcomputers.entities.EntityList;
import mcvmcomputers.entities.EntityPC;
import mcvmcomputers.item.ItemHarddrive;
import mcvmcomputers.item.ItemPart;
import mcvmcomputers.item.OrderableItem;
import mcvmcomputers.sound.SoundList;
import mcvmcomputers.utils.TabletOrder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;

public class MainMod implements ModInitializer {
	/** Pending tablet orders, by player. Server thread only. */
	public static Map<UUID, TabletOrder> orders;
	/** Running computers, by owner. Server thread only. */
	public static Map<UUID, EntityPC> computers;

	public static Runnable hardDriveClick = () -> {};
	public static Runnable deliveryChestSound = () -> {};
	public static Runnable focus = () -> {};
	public static Runnable pcOpenGui = () -> {};

	/**
	 * The parts catalog, when this game has one (set by the client). On a
	 * dedicated server it stays null and compatibility is checked by clients.
	 */
	public static volatile Catalog catalog;

	public static Catalog catalogForTooltips() {
		return catalog;
	}

	@Override
	public void onInitialize() {
		orders = new HashMap<>();
		computers = new HashMap<>();
		mcvmcomputers.item.ItemList.init();
		EntityList.init();
		SoundList.init();
		registerServerPackets();
	}

	public static boolean isRunning(EntityPC pc) {
		for (EntityPC running : computers.values()) {
			if (running == pc || running.getPcId().equals(pc.getPcId())) {
				return true;
			}
		}
		return false;
	}

	/** The PC with this entity id, if it belongs to the player. */
	private static EntityPC ownedPc(ServerPlayerEntity player, int entityId) {
		Entity e = player.world.getEntityById(entityId);
		if (e instanceof EntityPC && ((EntityPC) e).isOwnedBy(player) && e.squaredDistanceTo(player) < 64 * 64) {
			return (EntityPC) e;
		}
		return null;
	}

	private static void give(ServerPlayerEntity player, ItemStack stack) {
		if (!stack.isEmpty()) {
			player.inventory.offerOrDrop(player.world, stack);
		}
	}

	private static void deny(ServerPlayerEntity player, String key, Object... args) {
		player.sendMessage(new TranslatableText(key, args).formatted(Formatting.RED), false);
	}

	/** File names the client may refer to: no folders, no traversal. */
	static boolean safeFileName(String s) {
		return s.length() <= 200 && !s.contains("/") && !s.contains("\\") && !s.contains("..") && !s.contains(":")
				&& !s.startsWith(".");
	}

	public static void registerServerPackets() {
		ServerPlayNetworking.registerGlobalReceiver(C2S_ORDER, (server, player, handler, buf, sender) -> {
			int n = Math.min(64, buf.readInt());
			List<ItemStack> stacks = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				stacks.add(buf.readItemStack());
			}
			server.execute(() -> {
				TabletOrder to = new TabletOrder();
				to.items = new ArrayList<>();
				int price = 0;
				for (ItemStack s : stacks) {
					if (s.getItem() instanceof OrderableItem) {
						ItemStack one = s.copy();
						one.setCount(1);
						to.items.add(one);
						price += ((OrderableItem) s.getItem()).getPrice(one);
					}
				}
				if (to.items.isEmpty()) {
					return;
				}
				to.price = price;
				to.orderUUID = player.getUuid().toString();
				orders.put(player.getUuid(), to);
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(C2S_SCREEN, (server, player, handler, buf, sender) -> {
			// Relay as-is to everyone near the owner's running PC.
			PacketByteBuf copy = PacketByteBufs.create();
			copy.writeUuid(player.getUuid());
			copy.writeBytes(buf, Math.min(buf.readableBytes(), MAX_SCREEN_CHUNK + 64));
			server.execute(() -> {
				EntityPC pc = computers.get(player.getUuid());
				if (pc == null) {
					return;
				}
				for (ServerPlayerEntity p : PlayerLookup.tracking(pc)) {
					if (p != player) {
						ServerPlayNetworking.send(p, S2C_SCREEN, PacketByteBufs.copy(copy));
					}
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(C2S_AUDIO, (server, player, handler, buf, sender) -> {
			int stream = buf.readInt();
			byte[] data = buf.readByteArray(MAX_AUDIO_PACKET);
			server.execute(() -> {
				EntityPC pc = computers.get(player.getUuid());
				if (pc == null) {
					return;
				}
				for (ServerPlayerEntity p : PlayerLookup.tracking(pc)) {
					if (p != player) {
						PacketByteBuf b = PacketByteBufs.create();
						b.writeUuid(player.getUuid());
						b.writeInt(pc.getEntityId());
						b.writeInt(stream);
						b.writeByteArray(data);
						ServerPlayNetworking.send(p, S2C_AUDIO, b);
					}
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(C2S_TURN_ON_PC, (server, player, handler, buf, sender) -> {
			int id = buf.readInt();
			server.execute(() -> {
				EntityPC pc = ownedPc(player, id);
				if (pc != null) {
					computers.put(player.getUuid(), pc);
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(C2S_TURN_OFF_PC, (server, player, handler, buf, sender) -> {
			server.execute(() -> stopScreen(player.getUuid()));
		});

		ServerPlayNetworking.registerGlobalReceiver(C2S_CHANGE_HDD, (server, player, handler, buf, sender) -> {
			String file = buf.readString(256);
			int sizeMb = buf.readInt();
			int cyl = buf.readInt();
			int heads = buf.readInt();
			int spt = buf.readInt();
			String model = buf.readString(64);
			String format = buf.readString(16);
			String parent = buf.readString(256);
			server.execute(() -> {
				if (!safeFileName(file) || (!parent.isEmpty() && !safeFileName(parent))) {
					return;
				}
				ItemStack held = player.getMainHandStack();
				if (held.getItem() instanceof ItemHarddrive) {
					ItemHarddrive.setDisk(held, new DiskPart(file, Math.max(1, sizeMb), "", Math.min(cyl, 266305), Math.min(heads, 255),
							Math.min(spt, 255), model, format, parent));
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(C2S_INSTALL_PART, (server, player, handler, buf, sender) -> {
			int id = buf.readInt();
			int slot = buf.readInt();
			server.execute(() -> installPart(player, id, slot));
		});

		ServerPlayNetworking.registerGlobalReceiver(C2S_REMOVE_PART, (server, player, handler, buf, sender) -> {
			int id = buf.readInt();
			int kind = buf.readInt();
			int index = buf.readInt();
			server.execute(() -> removePart(player, id, kind, index));
		});

		ServerPlayNetworking.registerGlobalReceiver(C2S_CONFIGURE_PART, (server, player, handler, buf, sender) -> {
			int id = buf.readInt();
			int slot = buf.readInt();
			int index = buf.readInt();
			String name = buf.readString(128);
			int n = buf.readInt();
			if (n < 0 || n > 128) {
				return;
			}
			java.util.Map<String, String> options = new java.util.LinkedHashMap<>();
			for (int i = 0; i < n; i++) {
				options.put(buf.readString(64), buf.readString(64));
			}
			server.execute(() -> configurePart(player, id, slot, index, name, options));
		});

		ServerPlayNetworking.registerGlobalReceiver(C2S_SET_MEDIA, (server, player, handler, buf, sender) -> {
			int id = buf.readInt();
			int kind = buf.readInt();
			int drive = buf.readInt();
			String file = buf.readString(256);
			server.execute(() -> {
				EntityPC pc = ownedPc(player, id);
				if (pc == null || (!file.isEmpty() && !safeFileName(file)) || drive < 0 || drive > 3) {
					return;
				}
				HardwareSpec s = pc.copyHardware();
				if (kind == MEDIA_FLOPPY) {
					s.setFloppyImage(drive, file);
				} else {
					s.setCdImage(drive, file);
				}
				pc.setHardware(s);
			});
		});
	}

	public static void stopScreen(UUID owner) {
		EntityPC pc = computers.remove(owner);
		if (pc != null) {
			for (ServerPlayerEntity p : PlayerLookup.tracking(pc)) {
				PacketByteBuf b = PacketByteBufs.create();
				b.writeUuid(owner);
				ServerPlayNetworking.send(p, S2C_STOP_SCREEN, b);
			}
		}
	}

	private static void installPart(ServerPlayerEntity player, int entityId, int invSlot) {
		EntityPC pc = ownedPc(player, entityId);
		if (pc == null || invSlot < 0 || invSlot >= player.inventory.size()) {
			return;
		}
		if (isRunning(pc)) {
			deny(player, "mcvmcomputers.turn_off_first");
			return;
		}
		ItemStack stack = player.inventory.getStack(invSlot);
		if (stack.isEmpty()) {
			return;
		}
		HardwareSpec s = pc.copyHardware();
		Catalog cat = catalog; // only set when this server is also a client with a catalog
		boolean ok = false;
		if (stack.getItem() instanceof ItemPart) {
			ItemPart part = (ItemPart) stack.getItem();
			switch (part.kind) {
			case MOTHERBOARD:
				if (s.motherboard == null && ItemPart.toPart(stack) != null) {
					s.motherboard = ItemPart.toPart(stack);
					ok = true;
				}
				break;
			case CPU:
				if (s.motherboard == null) {
					deny(player, "mcvmcomputers.need_motherboard");
				} else if (s.cpu == null && ItemPart.toCpu(stack) != null) {
					HardwareSpec.CpuPart c = ItemPart.toCpu(stack);
					if (cat != null && !cat.cpuFits(cat.machine(s.motherboard.id), c.family, c.hz, c.multi)) {
						deny(player, "mcvmcomputers.part_doesnt_fit", c.name);
					} else {
						s.cpu = c;
						ok = true;
					}
				}
				break;
			case RAM:
				if (s.motherboard == null) {
					deny(player, "mcvmcomputers.need_motherboard");
				} else if (s.ramKb.size() < HardwareSpec.MAX_RAM_STICKS && ItemPart.ramKb(stack) > 0) {
					s.ramKb.add(ItemPart.ramKb(stack));
					ok = true;
				}
				break;
			case CARD:
				if (s.motherboard == null) {
					deny(player, "mcvmcomputers.need_motherboard");
				} else if (s.cards.size() < HardwareSpec.MAX_CARDS && ItemPart.toCard(stack) != null) {
					CardPart c = ItemPart.toCard(stack);
					Catalog.Card card = cat == null ? null : cat.card(c.category, c.id);
					if (cat != null && (card == null || !card.fits(cat.machine(s.motherboard.id)))) {
						deny(player, "mcvmcomputers.part_doesnt_fit", c.name);
					} else {
						s.cards.add(c);
						ok = true;
					}
				}
				break;
			case FLOPPY_DRIVE:
				if (s.floppies.size() < HardwareSpec.MAX_FLOPPIES && ItemPart.toPart(stack) != null) {
					s.floppies.add(ItemPart.toPart(stack));
					ok = true;
				}
				break;
			case CDROM_DRIVE:
				if (s.cdroms.size() + s.hardDisks.size() < HardwareSpec.MAX_IDE_DEVICES && ItemPart.toPart(stack) != null) {
					s.cdroms.add(ItemPart.toPart(stack));
					ok = true;
				}
				break;
			default:
				break;
			}
		} else if (stack.getItem() instanceof ItemHarddrive) {
			String file = ItemHarddrive.fileOf(stack);
			if (file.isEmpty()) {
				deny(player, "mcvmcomputers.hdd_not_set_up");
			} else if (s.cdroms.size() + s.hardDisks.size() < HardwareSpec.MAX_IDE_DEVICES) {
				boolean dup = false;
				for (DiskPart d : s.hardDisks) {
					dup |= d.file.equals(file);
				}
				if (!dup) {
					s.hardDisks.add(ItemHarddrive.toDisk(stack));
					ok = true;
				}
			}
		}
		if (ok) {
			stack.decrement(1);
			pc.setHardware(s);
		}
	}

	/**
	 * New settings for an installed part. The values are only stored here; the
	 * PC owner's game checks them against its catalog when it writes the 86Box
	 * config, so nothing invalid reaches 86Box.
	 */
	private static void configurePart(ServerPlayerEntity player, int entityId, int slot, int index, String name,
			java.util.Map<String, String> options) {
		EntityPC pc = ownedPc(player, entityId);
		if (pc == null) {
			return;
		}
		if (isRunning(pc)) {
			deny(player, "mcvmcomputers.turn_off_first");
			return;
		}
		HardwareSpec s = pc.copyHardware();
		switch (slot) {
		case SLOT_MOTHERBOARD:
			if (s.motherboard == null) {
				return;
			}
			s.motherboard = new HardwareSpec.Part(s.motherboard.id, s.motherboard.name, options);
			break;
		case SLOT_CPU:
			if (s.cpu == null) {
				return;
			}
			String fpu = options.getOrDefault("fpu_type", "");
			s.cpu = new HardwareSpec.CpuPart(s.cpu.family, s.cpu.hz, s.cpu.multi, s.cpu.name, "none".equals(fpu) ? "" : fpu);
			break;
		case SLOT_CARD:
			if (index < 0 || index >= s.cards.size()) {
				return;
			}
			CardPart c = s.cards.get(index);
			s.cards.set(index, new CardPart(c.category, c.id, name.isEmpty() ? c.name : name, options));
			break;
		case SLOT_HDD:
			if (index < 0 || index >= s.hardDisks.size()) {
				return;
			}
			s.hardDisks.set(index, s.hardDisks.get(index).withModel(options.getOrDefault("model", "")));
			break;
		case SLOT_KEYBOARD:
		case SLOT_MOUSE: {
			HardwareSpec.Part dev = name.isEmpty() ? null : new HardwareSpec.Part(name, name, options);
			if (slot == SLOT_KEYBOARD) {
				s.keyboard = dev;
			} else {
				s.mouse = dev;
			}
			break;
		}
		default:
			return;
		}
		pc.setHardware(s);
	}

	private static void removePart(ServerPlayerEntity player, int entityId, int kind, int index) {
		EntityPC pc = ownedPc(player, entityId);
		if (pc == null) {
			return;
		}
		if (isRunning(pc)) {
			deny(player, "mcvmcomputers.turn_off_first");
			return;
		}
		HardwareSpec s = pc.copyHardware();
		switch (kind) {
		case SLOT_MOTHERBOARD:
			if (s.motherboard != null) {
				// The CPU, RAM and cards sit on the board, so they come out with it.
				give(player, ItemPart.fromPart(ItemPart.Kind.MOTHERBOARD, s.motherboard));
				s.motherboard = null;
				if (s.cpu != null) {
					give(player, ItemPart.fromCpu(s.cpu));
					s.cpu = null;
				}
				for (int kb : s.ramKb) {
					give(player, ItemPart.ram(kb));
				}
				s.ramKb.clear();
				for (CardPart c : s.cards) {
					give(player, ItemPart.fromCard(c));
				}
				s.cards.clear();
			}
			break;
		case SLOT_CPU:
			if (s.cpu != null) {
				give(player, ItemPart.fromCpu(s.cpu));
				s.cpu = null;
			}
			break;
		case SLOT_RAM:
			if (index >= 0 && index < s.ramKb.size()) {
				give(player, ItemPart.ram(s.ramKb.remove(index)));
			}
			break;
		case SLOT_CARD:
			if (index >= 0 && index < s.cards.size()) {
				give(player, ItemPart.fromCard(s.cards.remove(index)));
			}
			break;
		case SLOT_FLOPPY:
			if (index >= 0 && index < s.floppies.size()) {
				give(player, ItemPart.fromPart(ItemPart.Kind.FLOPPY_DRIVE, s.floppies.remove(index)));
				if (index < s.floppyImages.size()) {
					s.floppyImages.remove(index);
				}
			}
			break;
		case SLOT_CDROM:
			if (index >= 0 && index < s.cdroms.size()) {
				give(player, ItemPart.fromPart(ItemPart.Kind.CDROM_DRIVE, s.cdroms.remove(index)));
				if (index < s.cdImages.size()) {
					s.cdImages.remove(index);
				}
			}
			break;
		case SLOT_HDD:
			if (index >= 0 && index < s.hardDisks.size()) {
				DiskPart d = s.hardDisks.remove(index);
				give(player, ItemHarddrive.fromDisk(d));
			}
			break;
		default:
			return;
		}
		pc.setHardware(s);
	}
}
