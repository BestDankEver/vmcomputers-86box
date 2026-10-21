package mcvmcomputers.client.gui;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import mcvmcomputers.client.ClientMod;
import mcvmcomputers.emu.VmSession;
import mcvmcomputers.entities.EntityPC;
import mcvmcomputers.networking.PacketList;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Language;
import net.minecraft.util.Util;

/** Picks a floppy or CD image for a drive. Works while the PC is running. */
public class GuiMediaPicker extends Screen {
	/** Image types 86Box opens. */
	private static final List<String> FLOPPY_EXT = Arrays.asList("img", "ima", "vfd", "flp", "dsk", "86f", "imd", "td0", "fdi",
			"hfe", "mfm", "xdf", "json", "d88", "dmf");
	private static final List<String> CD_EXT = Arrays.asList("iso", "cue", "chd", "mds", "mdf", "ccd", "nrg", "toast");

	private final Screen parent;
	private final EntityPC pc;
	private final int kind;
	private final int drive;
	private final Language lang = Language.getInstance();
	private int scroll;

	public GuiMediaPicker(Screen parent, EntityPC pc, int kind, int drive) {
		super(new TranslatableText("mcvmcomputers.media.title"));
		this.parent = parent;
		this.pc = pc;
		this.kind = kind;
		this.drive = drive;
	}

	private File folder() {
		return kind == PacketList.MEDIA_FLOPPY ? ClientMod.floppyDirectory : ClientMod.isoDirectory;
	}

	/** Puts media in a drive (empty name = eject), both in the running VM and on the PC. */
	public static void setMedia(EntityPC pc, int kind, int drive, String file) {
		VmSession v = ClientMod.vm;
		if (v != null && ClientMod.isRunning(pc) && v.state() == VmSession.State.RUNNING) {
			File dir = kind == PacketList.MEDIA_FLOPPY ? ClientMod.floppyDirectory : ClientMod.isoDirectory;
			if (file.isEmpty()) {
				if (kind == PacketList.MEDIA_FLOPPY) {
					v.ejectFloppy(drive);
				} else {
					v.ejectCd(drive);
				}
			} else if (kind == PacketList.MEDIA_FLOPPY) {
				v.insertFloppy(drive, new File(dir, file));
			} else {
				v.insertCd(drive, new File(dir, file));
			}
		}
		PacketByteBuf b = PacketByteBufs.create();
		b.writeInt(pc.getEntityId());
		b.writeInt(kind);
		b.writeInt(drive);
		b.writeString(file);
		ClientPlayNetworking.send(PacketList.C2S_SET_MEDIA, b);
	}

	private List<File> files() {
		List<File> out = new ArrayList<>();
		File[] all = folder().listFiles();
		if (all == null) {
			return out;
		}
		List<String> ext = kind == PacketList.MEDIA_FLOPPY ? FLOPPY_EXT : CD_EXT;
		for (File f : all) {
			String n = f.getName();
			int dot = n.lastIndexOf('.');
			if (f.isFile() && dot > 0 && ext.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT))) {
				out.add(f);
			} else if (kind == PacketList.MEDIA_CD && f.isDirectory() && !n.startsWith(".")) {
				// 86Box turns a folder into a CD on the fly (its "virtual ISO").
				out.add(f);
			}
		}
		// Folders first, then images.
		out.sort((a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
		return out;
	}

	@Override
	protected void init() {
		this.buttons.clear();
		this.children.clear();
		int x = this.width / 2 - 150;
		int y = 40 - scroll;
		for (File f : files()) {
			if (y >= 30 && y < this.height - 50) {
				String name = f.getName();
				String label = f.isDirectory() ? lang.get("mcvmcomputers.media.folder").replace("%s", name) : name;
				this.addButton(WideButton.of(x, y, 300, 14, new LiteralText(this.textRenderer.trimToWidth(label, 290)), b -> {
					setMedia(pc, kind, drive, name);
					MinecraftClient.getInstance().openScreen(parent);
				}));
			}
			y += 16;
		}
		int by = this.height - 28;
		this.addButton(WideButton.of(this.width / 2 - 154, by, 100, 20, new LiteralText(lang.get("mcvmcomputers.media.open_folder")),
				b -> Util.getOperatingSystem().open(folder())));
		this.addButton(WideButton.of(this.width / 2 - 50, by, 100, 20, new LiteralText(lang.get("mcvmcomputers.pc_editing.eject")), b -> {
			setMedia(pc, kind, drive, "");
			MinecraftClient.getInstance().openScreen(parent);
		}));
		this.addButton(WideButton.of(this.width / 2 + 54, by, 100, 20, new LiteralText(lang.get("gui.back")),
				b -> MinecraftClient.getInstance().openScreen(parent)));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		scroll = Math.max(0, scroll - (int) (amount * 16));
		init();
		return true;
	}

	@Override
	public void render(MatrixStack ms, int mouseX, int mouseY, float delta) {
		this.renderBackground(ms);
		String title = lang.get(kind == PacketList.MEDIA_FLOPPY ? "mcvmcomputers.media.floppy_title" : "mcvmcomputers.media.cd_title");
		this.textRenderer.draw(ms, title, this.width / 2 - this.textRenderer.getWidth(title) / 2, 16, 0xffffff);
		if (files().isEmpty()) {
			String s = lang.get("mcvmcomputers.media.none").replace("%s", folder().getAbsolutePath());
			this.textRenderer.draw(ms, this.textRenderer.trimToWidth(s, 380), this.width / 2 - 190, 44, 0xaaaaaa);
		}
		super.render(ms, mouseX, mouseY, delta);
	}

	@Override
	public void onClose() {
		MinecraftClient.getInstance().openScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
