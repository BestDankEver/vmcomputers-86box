package mcvmcomputers.client.gui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import mcvmcomputers.client.ClientMod;
import mcvmcomputers.emu.Catalog;
import mcvmcomputers.emu.DiskImages;
import mcvmcomputers.emu.DiskImages.Interface;
import mcvmcomputers.emu.HardwareSpec;
import mcvmcomputers.emu.HardwareSpec.DiskPart;
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

/**
 * Right-clicking a hard drive: make a new disk image or pick one you already
 * have. New disks get the same choices as 86Box's New Hard Disk dialog: image
 * format, size or exact geometry (or a standard drive type), and drive model.
 */
public class GuiCreateHarddrive extends Screen {
	/** Disk image types 86Box can open. */
	private static final List<String> EXT = Arrays.asList("img", "vhd", "hdi", "hdx", "hdd", "dsk", "ima");
	private static final int ROW = 24;

	private enum State {
		MENU, CREATE_NEW, SELECT_OLD
	}

	private enum Format {
		RAW(DiskPart.RAW, "Raw image (.img)", "img"), VHD_FIXED(DiskPart.VHD_FIXED, "Fixed-size VHD (.vhd)", "vhd"),
		VHD_DYNAMIC(DiskPart.VHD_DYNAMIC, "Dynamic-size VHD (.vhd)", "vhd"), VHD_DIFF(DiskPart.VHD_DIFF, "Differencing VHD (.vhd)", "vhd");

		final String id, label, ext;

		Format(String id, String label, String ext) {
			this.id = id;
			this.label = label;
			this.ext = ext;
		}
	}

	private enum SizeMode {
		SIZE("By size"), TYPE("Standard drive type"), CUSTOM("Custom geometry");

		final String label;

		SizeMode(String label) {
			this.label = label;
		}
	}

	// Choices for a new disk; kept between openings so making several alike is quick.
	private static Format format = Format.VHD_DYNAMIC;
	private static Interface iface = Interface.IDE;
	private static SizeMode sizeMode = SizeMode.SIZE;
	private static String sizeText = "504";
	private static String cylText = "1024", headText = "16", sptText = "63";
	private static int typeIndex = 0;
	private static String model = "";
	private static String parent = "";

	private TextFieldWidget nameField, sizeField, cylField, headField, sptField;
	private final List<TextFieldWidget> fields = new ArrayList<>();
	private final List<Object[]> labels = new ArrayList<>();
	private String status = "";
	private int[] geometry; // {cyl, heads, spt} of the disk to create, or null if invalid
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
		return this.addButton(WideButton.of(this.width / 2 - w / 2, y, w, 20, new LiteralText(text), a));
	}

	@Override
	public void init() {
		this.buttons.clear();
		this.children.clear();
		fields.clear();
		labels.clear();
		if (state == State.CREATE_NEW) {
			initCreate();
		} else if (state == State.MENU) {
			centered(this.height / 2 - 12, translation("mcvmcomputers.vhd_setup.newvhd"), b -> switchState(State.CREATE_NEW));
			centered(this.height / 2 + 12, translation("mcvmcomputers.vhd_setup.oldvhd"), b -> switchState(State.SELECT_OLD));
			centered(this.height / 2 + 36, translation("mcvmcomputers.media.open_folder"), b -> Util.getOperatingSystem().open(ClientMod.vhdDirectory));
		} else {
			int y = 60 - scroll;
			for (File f : images()) {
				if (y >= 50 && y < this.height - 40) {
					String name = f.getName();
					int[] vhd = DiskImages.readVhd(f);
					String label = name + "  |  " + (vhd != null ? vhd[3] + " MB, " + vhd[0] + "/" + vhd[1] + "/" + vhd[2] : (f.length() / 1024 / 1024) + " MB");
					this.addButton(WideButton.of(this.width / 2 - 140, y, 260, 14, new LiteralText(this.textRenderer.trimToWidth(label, 254)),
							b -> select(existing(f))));
					this.addButton(WideButton.of(this.width / 2 + 122, y, 14, 14, new LiteralText("x"), b -> {
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

	// ---- New disk -------------------------------------------------------------------

	private TextFieldWidget field(int x, int y, int w, String text, int maxLen) {
		TextFieldWidget f = new TextFieldWidget(this.textRenderer, x, y, w, 18, new LiteralText(""));
		f.setMaxLength(maxLen);
		f.setText(text);
		f.setChangedListener(s -> validate());
		this.children.add(f);
		fields.add(f);
		return f;
	}

	private void label(int x, int y, String text) {
		labels.add(new Object[] { x, y, text });
	}

	private void initCreate() {
		int lx = Math.max(6, this.width / 2 - 190);
		int cx = lx + 130;
		int cw = Math.min(250, this.width - cx - 6);
		int y = 26;
		Catalog cat = ClientMod.catalog();

		String oldName = nameField == null ? freeName() : nameField.getText();
		label(lx, y + 5, "File name");
		nameField = field(cx, y, cw - 30, oldName, 48);
		label(cx + cw - 26, y + 5, "." + format.ext);
		y += ROW;

		label(lx, y + 6, "Image format");
		this.addButton(WideButton.of(cx, y, cw, 20, new LiteralText(format.label), b -> {
			format = Format.values()[Math.floorMod(format.ordinal() + (hasShiftDown() ? -1 : 1), Format.values().length)];
			init();
		}));
		y += ROW;

		if (format == Format.VHD_DIFF) {
			label(lx, y + 6, "Based on");
			this.addButton(WideButton.of(cx, y, cw, 20, new LiteralText(parent.isEmpty() ? "Pick a VHD..." : parent), b -> pickParent()));
			y += ROW;
			label(lx, y + 4, "Stores only the changes; the base disk must stay unchanged.");
			y += 14;
		} else {
			label(lx, y + 6, "Made for");
			this.addButton(WideButton.of(cx, y, cw, 20, new LiteralText(iface.label), b -> {
				iface = Interface.values()[Math.floorMod(iface.ordinal() + (hasShiftDown() ? -1 : 1), Interface.values().length)];
				init();
			}));
			y += ROW;

			label(lx, y + 6, "Size");
			this.addButton(WideButton.of(cx, y, cw, 20, new LiteralText(sizeMode.label), b -> {
				sizeMode = SizeMode.values()[Math.floorMod(sizeMode.ordinal() + (hasShiftDown() ? -1 : 1), SizeMode.values().length)];
				init();
			}));
			y += ROW;

			if (sizeMode == SizeMode.SIZE) {
				label(lx, y + 5, "Size in MB");
				sizeField = field(cx, y, 100, sizeText, 9);
			} else if (sizeMode == SizeMode.TYPE) {
				List<int[]> types = cat == null ? new ArrayList<>() : cat.hddTypes;
				typeIndex = Math.max(0, Math.min(typeIndex, types.size() - 1));
				String t = types.isEmpty() ? "(needs the parts catalog)" : typeLabel(typeIndex, types.get(typeIndex));
				label(lx, y + 6, "Drive type");
				this.addButton(WideButton.of(cx, y, cw, 20, new LiteralText(this.textRenderer.trimToWidth(t + "  ...", cw - 8)), b -> pickType(types)));
			} else {
				label(lx, y + 5, "Cyl / heads / sectors");
				cylField = field(cx, y, 60, cylText, 6);
				headField = field(cx + 66, y, 40, headText, 3);
				sptField = field(cx + 112, y, 40, sptText, 3);
			}
			y += ROW;
		}

		label(lx, y + 6, "Drive model");
		String modelLabel = model.isEmpty() ? lang.get("mcvmcomputers.hdd.model_default") : (cat == null ? model : cat.hddModelName(model));
		this.addButton(WideButton.of(cx, y, cw, 20, new LiteralText(this.textRenderer.trimToWidth(modelLabel + "  ...", cw - 8)), b -> pickModel(cat)));
		y += ROW + 4;

		statusY = y;
		centered(this.height - 54, translation("mcvmcomputers.vhd_setup.newvhd"), b -> createNew());
		menuButton();
		validate();
	}

	private int statusY;

	private static String typeLabel(int i, int[] t) {
		return "Type " + (i + 1) + ": " + t[0] + "/" + t[1] + "/" + t[2] + " (" + HardwareSpec.ramLabel((int) (DiskImages.bytes(t[0], t[1], t[2]) / 1024)) + ")";
	}

	private void pickType(List<int[]> types) {
		List<String[]> choices = new ArrayList<>();
		int[] lim = iface.limits();
		for (int i = 0; i < types.size(); i++) {
			int[] t = types.get(i);
			if (t[2] <= lim[0] && t[1] <= lim[1] && t[0] <= lim[2]) {
				choices.add(new String[] { "" + i, typeLabel(i, t) });
			}
		}
		minecraft.openScreen(new GuiChoicePicker(this, "Standard drive types (" + iface.label + ")", choices, "" + typeIndex,
				v -> typeIndex = Integer.parseInt(v)));
	}

	private void pickModel(Catalog cat) {
		List<String[]> choices = new ArrayList<>();
		choices.add(new String[] { "", lang.get("mcvmcomputers.hdd.model_default") });
		if (cat != null) {
			choices.addAll(cat.hddModels);
		}
		minecraft.openScreen(new GuiChoicePicker(this, lang.get("mcvmcomputers.hdd.model"), choices, model, v -> model = v));
	}

	private void pickParent() {
		List<String[]> choices = new ArrayList<>();
		for (File f : images()) {
			int[] g = DiskImages.readVhd(f);
			if (g != null) {
				choices.add(new String[] { f.getName(), f.getName() + "  (" + g[3] + " MB)" });
			}
		}
		minecraft.openScreen(new GuiChoicePicker(this, "Base disk", choices, parent, v -> parent = v));
	}

	/** Works out the geometry from the fields and says whether the disk can be made. */
	private void validate() {
		geometry = null;
		if (nameField != null) {
			String n = nameField.getText().trim();
			if (n.isEmpty() || !n.matches("[A-Za-z0-9 _.()+-]+") || n.startsWith(".")) {
				status = "§cUse letters, numbers, spaces, - and _ in the file name.";
				return;
			}
			if (target().exists()) {
				status = "§cThere's already a disk called " + target().getName() + ".";
				return;
			}
		}
		if (format == Format.VHD_DIFF) {
			int[] g = parent.isEmpty() ? null : DiskImages.readVhd(new File(ClientMod.vhdDirectory, parent));
			if (g == null) {
				status = "§cPick the VHD this disk is based on.";
				return;
			}
			geometry = new int[] { g[0], g[1], g[2] };
			status = "§a" + summary(geometry) + ", same as " + parent + ".";
			return;
		}
		int[] g;
		try {
			if (sizeMode == SizeMode.SIZE) {
				sizeText = sizeField.getText().trim();
				g = iface.geometryFor(Long.parseLong(sizeText));
			} else if (sizeMode == SizeMode.TYPE) {
				Catalog cat = ClientMod.catalog();
				if (cat == null || cat.hddTypes.isEmpty()) {
					status = "§cThe parts catalog hasn't downloaded yet.";
					return;
				}
				int[] t = cat.hddTypes.get(typeIndex);
				g = new int[] { t[0], t[1], t[2] };
			} else {
				cylText = cylField.getText().trim();
				headText = headField.getText().trim();
				sptText = sptField.getText().trim();
				g = new int[] { Integer.parseInt(cylText), Integer.parseInt(headText), Integer.parseInt(sptText) };
			}
		} catch (NumberFormatException e) {
			status = translation("mcvmcomputers.input_nan");
			return;
		}
		int[] lim = iface.limits();
		if (g[0] < 1 || g[1] < 1 || g[2] < 1) {
			status = "§cEvery number must be at least 1.";
			return;
		}
		if (g[2] > lim[0] || g[1] > lim[1] || g[0] > lim[2]) {
			status = "§c" + iface.label + " allows at most " + lim[2] + " cylinders, " + lim[1] + " heads, " + lim[0] + " sectors.";
			return;
		}
		if (format != Format.RAW && g[0] > 65535) {
			status = "§cVHD files hold at most 65535 cylinders.";
			return;
		}
		long bytes = DiskImages.bytes(g[0], g[1], g[2]);
		if (bytes > 0x1FFFFFFE00L) {
			status = "§cDisk images can't be larger than 127 GB.";
			return;
		}
		if (format != Format.VHD_DYNAMIC && bytes >= ClientMod.vhdDirectory.getFreeSpace()) {
			status = translation("mcvmcomputers.vhd_setup.space");
			return;
		}
		geometry = g;
		status = "§a" + summary(g) + (bytes > 528482304L ? "  §7(older BIOSes see only 504 MB)" : "");
	}

	private static String summary(int[] g) {
		return HardwareSpec.ramLabel((int) (DiskImages.bytes(g[0], g[1], g[2]) / 1024)) + ": " + g[0] + " cylinders, " + g[1] + " heads, "
				+ g[2] + " sectors";
	}

	private File target() {
		return new File(ClientMod.vhdDirectory, nameField.getText().trim() + "." + format.ext);
	}

	private String freeName() {
		for (int n = 0;; n++) {
			String base = "disk" + n;
			if (!new File(ClientMod.vhdDirectory, base + ".img").exists() && !new File(ClientMod.vhdDirectory, base + ".vhd").exists()) {
				return base;
			}
		}
	}

	private void createNew() {
		validate();
		if (geometry == null) {
			return;
		}
		File f = target();
		DiskPart d = new DiskPart(f.getName(), 0, "", geometry[0], geometry[1], geometry[2], model, format.id,
				format == Format.VHD_DIFF ? parent : "");
		if (format != Format.VHD_DIFF) {
			// 86Box makes differencing disks itself on first start (it needs the parent's details).
			try {
				DiskImages.create(f, d);
			} catch (IOException e) {
				status = translation("mcvmcomputers.vhd_setup.failed") + " " + e.getMessage();
				return;
			}
		}
		select(d);
	}

	// ---- Existing disks -------------------------------------------------------------

	private DiskPart existing(File f) {
		int[] vhd = DiskImages.readVhd(f);
		if (vhd != null) {
			return new DiskPart(f.getName(), vhd[3], "", vhd[0], vhd[1], vhd[2], "", DiskPart.VHD_FIXED, "");
		}
		return new DiskPart(f.getName(), (int) Math.max(1, f.length() / 1024 / 1024));
	}

	private void menuButton() {
		int w = textRenderer.getWidth(translation("mcvmcomputers.vhd_setup.menu")) + 40;
		this.addButton(WideButton.of(this.width - (w + 10), this.height - 30, w, 20, new LiteralText(translation("mcvmcomputers.vhd_setup.menu")),
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

	private void select(DiskPart d) {
		PacketByteBuf pb = PacketByteBufs.create();
		pb.writeString(d.file);
		pb.writeInt(d.sizeMb);
		pb.writeInt(d.cylinders);
		pb.writeInt(d.heads);
		pb.writeInt(d.sectors);
		pb.writeString(d.model);
		pb.writeString(d.format);
		pb.writeString(d.parent);
		ClientPlayNetworking.send(PacketList.C2S_CHANGE_HDD, pb);
		minecraft.openScreen(null);
	}

	@Override
	public void tick() {
		for (TextFieldWidget f : fields) {
			f.tick();
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
			for (Object[] l : labels) {
				this.textRenderer.draw(ms, (String) l[2], (int) l[0], (int) l[1], 0xdddddd);
			}
			for (TextFieldWidget f : fields) {
				f.render(ms, mouseX, mouseY, delta);
			}
			int lx = Math.max(6, this.width / 2 - 190);
			this.textRenderer.draw(ms, this.textRenderer.trimToWidth(status, this.width - lx - 6), lx, statusY, -1);
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
