package mcvmcomputers.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.SystemUtils;
import org.lwjgl.glfw.GLFW;

import mcvmcomputers.MainMod;
import mcvmcomputers.client.entities.render.CRTScreenRender;
import mcvmcomputers.client.entities.render.DeliveryChestRender;
import mcvmcomputers.client.entities.render.FlatScreenRender;
import mcvmcomputers.client.entities.render.ItemPreviewRender;
import mcvmcomputers.client.entities.render.KeyboardRender;
import mcvmcomputers.client.entities.render.MouseRender;
import mcvmcomputers.client.entities.render.PCRender;
import mcvmcomputers.client.entities.render.WallTVRender;
import mcvmcomputers.client.gui.GuiCreateHarddrive;
import mcvmcomputers.client.gui.GuiFocus;
import mcvmcomputers.client.gui.GuiPCEditing;
import mcvmcomputers.client.screen.ScreenStream;
import mcvmcomputers.client.screen.ScreenTexture;
import mcvmcomputers.client.tablet.TabletOS;
import mcvmcomputers.client.utils.VMSettings;
import mcvmcomputers.emu.Catalog;
import mcvmcomputers.emu.ConfigWriter;
import mcvmcomputers.emu.HardwareSpec;
import mcvmcomputers.emu.Installer;
import mcvmcomputers.emu.VmSession;
import mcvmcomputers.entities.EntityDeliveryChest;
import mcvmcomputers.entities.EntityItemPreview;
import mcvmcomputers.entities.EntityList;
import mcvmcomputers.entities.EntityPC;
import mcvmcomputers.networking.PacketList;
import mcvmcomputers.utils.TabletOrder;
import mcvmcomputers.utils.TabletOrder.OrderStatus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendereregistry.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

public class ClientMod implements ClientModInitializer {
	/** Screen textures by PC owner; the monitor renderers draw these. */
	public static Map<UUID, Identifier> vmScreenTextures = new HashMap<>();
	private static final Map<UUID, ScreenTexture> screens = new HashMap<>();

	public static EntityItemPreview thePreviewEntity;
	public static File vhdDirectory;
	public static File isoDirectory;
	public static File floppyDirectory;
	public static File machinesDirectory;
	public static File emulatorDirectory;
	public static TabletOS tabletOS;
	public static TabletOrder myOrder;
	public static Thread tabletThread;

	public static float deltaTime;
	public static long lastDeltaTimeTime;

	public static int glfwUnfocusKey1;
	public static int glfwUnfocusKey2;
	public static int glfwUnfocusKey3;
	public static int glfwUnfocusKey4;

	public static EntityDeliveryChest currentDeliveryChest;
	public static EntityPC currentPC;

	public static VMSettings settings;
	public static Installer installer;

	/** The VM this player is running (one at a time). */
	public static volatile VmSession vm;
	/** Entity id and PC id of the PC that VM belongs to. */
	public static volatile int vmEntityId = -1;
	public static volatile String vmPcId = "";
	/** Set while 86Box is being downloaded or started. */
	public static volatile boolean vmPreparing;
	public static volatile String vmStatus = "";
	private static long lastLocalSerial = -1;
	private static boolean reportedFailure;

	static {
		if (SystemUtils.IS_OS_MAC) {
			glfwUnfocusKey1 = GLFW.GLFW_KEY_LEFT_ALT;
			glfwUnfocusKey2 = GLFW.GLFW_KEY_RIGHT_ALT;
			glfwUnfocusKey3 = GLFW.GLFW_KEY_BACKSPACE;
			glfwUnfocusKey4 = -1;
		} else {
			glfwUnfocusKey1 = GLFW.GLFW_KEY_LEFT_CONTROL;
			glfwUnfocusKey2 = GLFW.GLFW_KEY_RIGHT_CONTROL;
			glfwUnfocusKey3 = GLFW.GLFW_KEY_BACKSPACE;
			glfwUnfocusKey4 = -1;
		}
	}

	public static String getKeyName(int key) {
		if (key < 0) {
			return "None";
		}
		switch (key) {
		case GLFW.GLFW_KEY_LEFT_CONTROL:
			return "L Control";
		case GLFW.GLFW_KEY_RIGHT_CONTROL:
			return "R Control";
		case GLFW.GLFW_KEY_RIGHT_ALT:
			return "R Alt";
		case GLFW.GLFW_KEY_LEFT_ALT:
			return "L Alt";
		case GLFW.GLFW_KEY_LEFT_SHIFT:
			return "L Shift";
		case GLFW.GLFW_KEY_RIGHT_SHIFT:
			return "R Shift";
		case GLFW.GLFW_KEY_ENTER:
			return "Enter";
		case GLFW.GLFW_KEY_BACKSPACE:
			return "Backspace";
		case GLFW.GLFW_KEY_CAPS_LOCK:
			return "Caps Lock";
		case GLFW.GLFW_KEY_TAB:
			return "Tab";
		default:
			return GLFW.glfwGetKeyName(key, 0);
		}
	}

	// ---- Settings and downloads ---------------------------------------------------------

	/** Applies settings (from the setup screens or setup.json) and sets up folders. */
	public static void applySettings(VMSettings s) {
		settings = s;
		File base = new File(s.vmComputersDirectory);
		vhdDirectory = new File(base, "vhds");
		isoDirectory = new File(base, "isos");
		floppyDirectory = new File(base, "floppies");
		machinesDirectory = new File(base, "machines");
		emulatorDirectory = new File(base, "86box");
		for (File f : new File[] { vhdDirectory, isoDirectory, floppyDirectory, machinesDirectory, emulatorDirectory }) {
			f.mkdirs();
		}
		glfwUnfocusKey1 = s.unfocusKey1;
		glfwUnfocusKey2 = s.unfocusKey2;
		glfwUnfocusKey3 = s.unfocusKey3;
		glfwUnfocusKey4 = s.unfocusKey4;
		installer = new Installer(emulatorDirectory, s.manifestUrl, Math.max(1, s.romUpdateDays));
		if (!s.custom86BoxExe.isEmpty() && !s.customRomDirectory.isEmpty()) {
			installer.setOverride(new File(s.custom86BoxExe), new File(s.customRomDirectory));
		}
		MainMod.catalog = installer.loadInstalledCatalog();
	}

	/**
	 * Refreshes the parts catalog, and, on computers that have run a VM before,
	 * fetches the newest 86Box so it's ready by the time a PC is switched on.
	 */
	public static void startBackgroundUpdate() {
		if (installer == null) {
			return;
		}
		Thread t = new Thread(() -> {
			installer.refreshCatalog();
			MainMod.catalog = installer.catalog();
			if (installer.installed() != null) {
				try {
					installer.ensureEmulator(false);
				} catch (IOException e) {
					System.err.println("[VM Computers] " + e.getMessage());
				}
			}
		}, "VM Computers updater");
		t.setDaemon(true);
		t.start();
	}

	public static Catalog catalog() {
		return installer == null ? null : installer.catalog();
	}

	// ---- Power ------------------------------------------------------------------------

	public static boolean isRunning(EntityPC pc) {
		VmSession v = vm;
		return pc != null && ((v != null && v.isActive()) || vmPreparing) && pc.getPcId().equals(vmPcId);
	}

	public static boolean anyRunning() {
		VmSession v = vm;
		return (v != null && v.isActive()) || vmPreparing;
	}

	private static void chat(Text t) {
		MinecraftClient mc = MinecraftClient.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.sendMessage(t, false);
			}
		});
	}

	/** Switches on the given PC: downloads 86Box if needed, writes its config and starts it. */
	public static void powerOn(EntityPC pc) {
		if (anyRunning()) {
			chat(new TranslatableText("mcvmcomputers.one_vm_at_a_time").formatted(Formatting.RED));
			return;
		}
		HardwareSpec spec = pc.copyHardware();
		Catalog cat = catalog();
		List<HardwareSpec.Problem> problems = spec.check(cat);
		if (HardwareSpec.anyBlocking(problems)) {
			for (HardwareSpec.Problem p : problems) {
				if (p.blocking) {
					chat(new LiteralText(p.message).formatted(Formatting.RED));
				}
			}
			return;
		}
		for (HardwareSpec.DiskPart d : spec.hardDisks) {
			if (!new File(vhdDirectory, d.file).isFile()) {
				chat(new TranslatableText("mcvmcomputers.hdd_doesnt_exist", d.file).formatted(Formatting.RED));
				return;
			}
		}
		// Media that went missing is simply not inserted.
		for (int i = 0; i < spec.cdImages.size(); i++) {
			if (!spec.cdImages.get(i).isEmpty() && !new File(isoDirectory, spec.cdImages.get(i)).isFile()) {
				chat(new TranslatableText("mcvmcomputers.iso_doesnt_exist", spec.cdImages.get(i)).formatted(Formatting.YELLOW));
				spec.setCdImage(i, "");
			}
		}
		for (int i = 0; i < spec.floppyImages.size(); i++) {
			if (!spec.floppyImages.get(i).isEmpty() && !new File(floppyDirectory, spec.floppyImages.get(i)).isFile()) {
				chat(new TranslatableText("mcvmcomputers.iso_doesnt_exist", spec.floppyImages.get(i)).formatted(Formatting.YELLOW));
				spec.setFloppyImage(i, "");
			}
		}

		final String pcId = pc.getPcId();
		final int entityId = pc.getEntityId();
		vmPreparing = true;
		vmPcId = pcId;
		vmEntityId = entityId;
		reportedFailure = false;
		vmStatus = "Starting...";
		sendInt(PacketList.C2S_TURN_ON_PC, entityId);

		Thread t = new Thread(() -> {
			try {
				boolean forceRoms = false;
				for (int attempt = 0; attempt < 2; attempt++) {
					vmStatus = "Getting 86Box ready...";
					StatusMirror mirror = new StatusMirror();
					mirror.start();
					Installer.Installed inst;
					try {
						inst = installer.ensureEmulator(forceRoms);
					} finally {
						mirror.stop();
					}
					File dir = new File(machinesDirectory, safe(pcId));
					dir.mkdirs();
					ConfigWriter.write(new File(dir, "86box.cfg"), spec, cat,
							new ConfigWriter.Folders(vhdDirectory, isoDirectory, floppyDirectory));
					vmStatus = "Starting 86Box " + inst.version + "...";
					VmSession session = new VmSession(inst.exe, inst.roms, dir, "VMC86-" + safe(pcId).substring(0, Math.min(8, safe(pcId).length())),
							settings.frameIntervalMs);
					vm = session;
					session.start();
					long deadline = System.currentTimeMillis() + 45_000;
					while (session.state() == VmSession.State.STARTING && System.currentTimeMillis() < deadline) {
						Thread.sleep(100);
					}
					if (session.state() == VmSession.State.RUNNING) {
						vmStatus = "";
						return;
					}
					String err = String.valueOf(session.error());
					if (attempt == 0 && missingRom(dir, err)) {
						// The catalog is newer than our ROM set: update it and try once more.
						chat(new TranslatableText("mcvmcomputers.updating_roms").formatted(Formatting.YELLOW));
						forceRoms = true;
						continue;
					}
					throw new IOException(err);
				}
			} catch (Exception e) {
				reportedFailure = true;
				vmStatus = "";
				chat(new TranslatableText("mcvmcomputers.failed_to_start", e.getMessage()).formatted(Formatting.RED));
				MinecraftClient.getInstance().execute(ClientMod::powerOffNow);
			} finally {
				vmPreparing = false;
			}
		}, "VM Computers power on");
		t.setDaemon(true);
		t.start();
	}

	private static boolean missingRom(File vmDir, String error) {
		String text = error.toLowerCase(Locale.ROOT);
		try {
			File log = new File(vmDir, "86box.log");
			if (log.isFile()) {
				text += new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
			}
		} catch (IOException e) {
			// ignore
		}
		return text.contains("rom") && (text.contains("not found") || text.contains("missing") || text.contains("unable to load"));
	}

	/** Copies the installer's progress into vmStatus while downloading. */
	private static final class StatusMirror implements Runnable {
		private volatile boolean running = true;

		void start() {
			Thread t = new Thread(this, "VM Computers status");
			t.setDaemon(true);
			t.start();
		}

		void stop() {
			running = false;
		}

		@Override
		public void run() {
			while (running) {
				String s = installer.status();
				double p = installer.progress();
				if (installer.busy() && !s.isEmpty()) {
					vmStatus = p >= 0 ? String.format(Locale.ROOT, "%s %d%%", s, (int) (p * 100)) : s;
				}
				try {
					Thread.sleep(200);
				} catch (InterruptedException e) {
					return;
				}
			}
		}
	}

	private static String safe(String id) {
		return id.replaceAll("[^A-Za-z0-9-]", "_");
	}

	/** Switches off this player's VM (game thread). */
	public static void powerOffNow() {
		VmSession v = vm;
		if (v != null) {
			v.stop();
		}
		vm = null;
		vmEntityId = -1;
		vmPcId = "";
		vmStatus = "";
		lastLocalSerial = -1;
		ScreenStream.ownerStopped();
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			removeScreen(mc.player.getUuid());
			if (mc.getNetworkHandler() != null) {
				ClientPlayNetworking.send(PacketList.C2S_TURN_OFF_PC, PacketByteBufs.create());
			}
		}
	}

	/** Game shutdown: wait for 86Box to exit. */
	public static void shutdown() {
		VmSession v = vm;
		vm = null;
		if (v != null) {
			v.stopAndWait(5000);
		}
	}

	private static void sendInt(Identifier channel, int value) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.getNetworkHandler() != null) {
			PacketByteBuf b = PacketByteBufs.create();
			b.writeInt(value);
			ClientPlayNetworking.send(channel, b);
		}
	}

	// ---- Every frame ---------------------------------------------------------------------

	/** Called at the start of every rendered frame. */
	public static void renderTick() {
		MinecraftClient mc = MinecraftClient.getInstance();
		VmSession v = vm;
		if (v != null) {
			if (mc.player == null) {
				powerOffNow(); // left the world
			} else if (v.state() == VmSession.State.FAILED && !vmPreparing) {
				if (!reportedFailure) {
					reportedFailure = true;
					chat(new TranslatableText("mcvmcomputers.vm_crashed", String.valueOf(v.error())).formatted(Formatting.RED));
				}
				powerOffNow();
			} else if (v.state() == VmSession.State.STOPPED && !vmPreparing) {
				powerOffNow(); // the guest powered itself off
			} else {
				VmSession.Frame f = v.latestFrame();
				if (f != null && f.serial != lastLocalSerial) {
					lastLocalSerial = f.serial;
					ScreenStream.decodeLocal(mc.player.getUuid(), f.png);
				}
				ScreenStream.ownerTick(v, settings == null ? 5 : settings.streamFps);
			}
		}
		// Upload whatever frames finished decoding (ours and other players').
		for (UUID owner : ScreenStream.readyOwners()) {
			NativeImage img = ScreenStream.takeReady(owner);
			if (img != null) {
				ScreenTexture t = screens.computeIfAbsent(owner, k -> new ScreenTexture());
				t.update(img);
				vmScreenTextures.put(owner, t.id());
			}
		}
	}

	public static void removeScreen(UUID owner) {
		ScreenStream.forget(owner);
		ScreenTexture t = screens.remove(owner);
		if (t != null) {
			t.destroy();
		}
		vmScreenTextures.remove(owner);
	}

	public static void removeAllScreens() {
		for (UUID u : new ArrayList<>(screens.keySet())) {
			removeScreen(u);
		}
	}

	// ---- Packets -------------------------------------------------------------------------

	public static void registerClientPackets() {
		ClientPlayNetworking.registerGlobalReceiver(PacketList.S2C_SCREEN, (client, handler, buf, sender) -> {
			UUID owner = buf.readUuid();
			byte[] chunk = new byte[buf.readableBytes()];
			buf.readBytes(chunk);
			if (client.player != null && owner.equals(client.player.getUuid())) {
				return; // we draw our own screen locally
			}
			ScreenStream.onChunk(owner, chunk);
		});

		ClientPlayNetworking.registerGlobalReceiver(PacketList.S2C_STOP_SCREEN, (client, handler, buf, sender) -> {
			UUID owner = buf.readUuid();
			client.execute(() -> {
				if (client.player == null || !owner.equals(client.player.getUuid())) {
					removeScreen(owner);
				}
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(PacketList.S2C_SYNC_ORDER, (client, handler, buf, sender) -> {
			int n = buf.readInt();
			List<ItemStack> items = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				items.add(buf.readItemStack());
			}
			int price = buf.readInt();
			int status = buf.readInt();
			client.execute(() -> {
				if (myOrder == null) {
					myOrder = new TabletOrder();
				}
				myOrder.price = price;
				myOrder.items = items;
				if (client.player != null) {
					myOrder.orderUUID = client.player.getUuid().toString();
				}
				if (status >= 0 && status < OrderStatus.values().length) {
					myOrder.currentStatus = OrderStatus.values()[status];
				}
			});
		});
	}

	@Override
	public void onInitializeClient() {
		MainMod.pcOpenGui = () -> MinecraftClient.getInstance().openScreen(new GuiPCEditing(currentPC));
		MainMod.hardDriveClick = () -> MinecraftClient.getInstance().openScreen(new GuiCreateHarddrive());
		MainMod.focus = () -> MinecraftClient.getInstance().openScreen(new GuiFocus());
		MainMod.deliveryChestSound = () -> {
			if (currentDeliveryChest != null && MinecraftClient.getInstance().getSoundManager().isPlaying(currentDeliveryChest.rocketSound)) {
				MinecraftClient.getInstance().getSoundManager().stop(currentDeliveryChest.rocketSound);
			}
		};

		registerClientPackets();

		EntityRendererRegistry.INSTANCE.register(EntityList.ITEM_PREVIEW, (d, context) -> new ItemPreviewRender(d));
		EntityRendererRegistry.INSTANCE.register(EntityList.KEYBOARD, (d, context) -> new KeyboardRender(d));
		EntityRendererRegistry.INSTANCE.register(EntityList.MOUSE, (d, context) -> new MouseRender(d));
		EntityRendererRegistry.INSTANCE.register(EntityList.CRT_SCREEN, (d, context) -> new CRTScreenRender(d));
		EntityRendererRegistry.INSTANCE.register(EntityList.FLATSCREEN, (d, context) -> new FlatScreenRender(d));
		EntityRendererRegistry.INSTANCE.register(EntityList.WALLTV, (d, context) -> new WallTVRender(d));
		EntityRendererRegistry.INSTANCE.register(EntityList.PC, (d, context) -> new PCRender(d));
		EntityRendererRegistry.INSTANCE.register(EntityList.DELIVERY_CHEST, (d, context) -> new DeliveryChestRender(d));
	}
}
