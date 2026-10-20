package mcvmcomputers.client.gui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import mcvmcomputers.client.ClientMod;
import mcvmcomputers.emu.ConfigWriter;
import mcvmcomputers.networking.PacketList;
import mcvmcomputers.utils.MVCUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Language;
import net.minecraft.util.Util;

/** Right-clicking a hard drive: make a new disk image or pick one you already have. */
public class GuiCreateHarddrive extends Screen {
	/** Disk image types 86Box can open. New disks are raw .img files. */
	private static final List<String> EXT = Arrays.asList("img", "vhd", "hdi", "hdx", "hdd", "dsk", "ima");
	private static final int MAX_MB = 32000; // IDE CHS limit with 63 sectors and 16 heads is about 31.5 GB

	private enum State {
		MENU, CREATE_NEW, SELECT_OLD
	}

	private TextFieldWidget hddSize;
	private String status = "";
	private boolean valid;
	private State state = State.MENU;
	private int scroll;
	private final Language lang = Language.getInstance();
	private final MinecraftClient minecraft = MinecraftClient.getInstance();

	public GuiCreateHarddrive() {
		super(new TranslatableText("mcvmcomputers.vhd_setup.title"));
	}

	private String translation(String in) {
		return lang.get(in).replace("%c", "" + MVCUtils.COLOR_CHAR);
	}

	private ButtonWidget centered(int y, String text, ButtonWidget.PressAction a) {
		int w = textRenderer.getWidth(text) + 40;
		return this.addButton(new ButtonWidget(this.width / 2 - w / 2, y, w, 20, new LiteralText(text), a));
	}

	@Override
	public void init() {
		this.buttons.clear();
		this.children.clear();
		if (state == State.CREATE_NEW) {
			String s = hddSize == null ? "504" : hddSize.getText();
			hddSize = new TextFieldWidget(this.textRenderer, this.width / 2 - 150, this.height / 2 - 10, 300, 20, new LiteralText(""));
			hddSize.setText(s);
			hddSize.setChangedListener(this::sizeChanged);
			this.children.add(hddSize);
			sizeChanged(hddSize.getText());
			centered(this.height / 2 + 50, translation("mcvmcomputers.vhd_setup.newvhd"), b -> createNew());
			menuButton();
		} else if (state == State.MENU) {
			centered(this.height / 2 - 12, translation("mcvmcomputers.vhd_setup.newvhd"), b -> switchState(State.CREATE_NEW));
			centered(this.height / 2 + 12, translation("mcvmcomputers.vhd_setup.oldvhd"), b -> switchState(State.SELECT_OLD));
			centered(this.height / 2 + 36, translation("mcvmcomputers.media.open_folder"), b -> Util.getOperatingSystem().open(ClientMod.vhdDirectory));
		} else {
			int y = 60 - scroll;
			for (File f : images()) {
				if (y >= 50 && y < this.height - 40) {
					String name = f.getName();
					String label = name + "  |  " + (f.length() / 1024 / 1024) + " MB";
					this.addButton(new ButtonWidget(this.width / 2 - 120, y, 220, 14, new LiteralText(this.textRenderer.trimToWidth(label, 214)),
							b -> select(name, (int) Math.max(1, f.length() / 1024 / 1024))));
					this.addButton(new ButtonWidget(this.width / 2 + 102, y, 14, 14, new LiteralText("x"), b -> {
						if (hasShiftDown()) {
							f.delete();
							init();
						}
					}));
				}
				y += 16;
			}
			menuButton();
		}
	}

	private void menuButton() {
		int w = textRenderer.getWidth(translation("mcvmcomputers.vhd_setup.menu")) + 40;
		this.addButton(new ButtonWidget(this.width - (w + 10), this.height - 30, w, 20, new LiteralText(translation("mcvmcomputers.vhd_setup.menu")),
				b -> switchState(State.MENU)));
	}

	private List<File> images() {
		List<File> out = new ArrayList<>();
		File[] all = ClientMod.vhdDirectory.listFiles();
		if (all != null) {
			for (File f : all) {
				String n = f.getName();
				int dot = n.lastIndexOf('.');
				if (f.isFile() && dot > 0 && EXT.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT))) {
					out.add(f);
				}
			}
		}
		out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
		return out;
	}

	private void switchState(State s) {
		state = s;
		scroll = 0;
		init();
	}

	private void select(String fileName, int sizeMb) {
		PacketByteBuf pb = PacketByteBufs.create();
		pb.writeString(fileName);
		pb.writeInt(sizeMb);
		ClientPlayNetworking.send(PacketList.C2S_CHANGE_HDD, pb);
		minecraft.openScreen(null);
	}

	private void createNew() {
		if (!valid) {
			return;
		}
		int mb = Integer.parseInt(hddSize.getText().trim());
		int n = 0;
		File f;
		do {
			f = new File(ClientMod.vhdDirectory, "disk" + n + ".img");
			n++;
		} while (f.exists());
		try {
			ConfigWriter.createDiskImage(f, mb, "ide");
		} catch (IOException e) {
			status = translation("mcvmcomputers.vhd_setup.failed") + " " + e.getMessage();
			return;
		}
		select(f.getName(), mb);
	}

	private void sizeChanged(String in) {
		valid = false;
		String t = in.trim();
		if (t.isEmpty() || !t.chars().allMatch(Character::isDigit) || t.length() > 9) {
			status = translation("mcvmcomputers.input_nan");
			return;
		}
		int mb = Integer.parseInt(t);
		if (mb < 1) {
			status = translation("mcvmcomputers.input_too_little").replace("%s", "1");
		} else if (mb > MAX_MB) {
			status = translation("mcvmcomputers.input_too_much").replace("%s", "" + MAX_MB);
		} else if ((long) mb * 1024 * 1024 >= ClientMod.vhdDirectory.getFreeSpace()) {
			status = translation("mcvmcomputers.vhd_setup.space");
		} else {
			status = translation("mcvmcomputers.vhd_setup.validspace");
			valid = true;
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (state == State.SELECT_OLD) {
			scroll = Math.max(0, scroll - (int) (amount * 16));
			init();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public void render(MatrixStack ms, int mouseX, int mouseY, float delta) {
		this.renderBackground(ms);
		if (state == State.CREATE_NEW) {
			this.textRenderer.draw(ms, translation("mcvmcomputers.vhd_setup.vhdsize"), this.width / 2 - 150, this.height / 2 - 20, -1);
			this.textRenderer.draw(ms, status, this.width / 2 - 150, this.height / 2 + 13, -1);
			this.textRenderer.draw(ms, translation("mcvmcomputers.vhd_setup.bios_limits"), this.width / 2 - 150, this.height / 2 + 26, 0xaaaaaa);
			this.hddSize.render(ms, mouseX, mouseY, delta);
		} else if (state == State.MENU) {
			String s = translation("mcvmcomputers.vhd_setup.setupnewvhd");
			this.textRenderer.draw(ms, s, this.width / 2 - this.textRenderer.getWidth(s) / 2, this.height / 2 - 30, -1);
		} else {
			String s = translation("mcvmcomputers.vhd_setup.delete_hint");
			this.textRenderer.draw(ms, s, this.width / 2 - this.textRenderer.getWidth(s) / 2, 40, 0xaaaaaa);
		}
		super.render(ms, mouseX, mouseY, delta);
	}
}
