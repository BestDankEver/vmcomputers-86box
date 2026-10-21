package mcvmcomputers.client.gui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import mcvmcomputers.client.ClientMod;
import mcvmcomputers.emu.Catalog;
import mcvmcomputers.emu.HardwareSpec;
import mcvmcomputers.emu.HardwareSpec.CardPart;
import mcvmcomputers.emu.HardwareSpec.DiskPart;
import mcvmcomputers.emu.HardwareSpec.Part;
import mcvmcomputers.emu.VmSession;
import mcvmcomputers.entities.EntityPC;
import mcvmcomputers.item.ItemList;
import mcvmcomputers.item.ItemPart;
import mcvmcomputers.networking.PacketList;
import mcvmcomputers.utils.MVCUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformation.Mode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Language;

/**
 * The PC screen. Case closed: power and drive media. Case open: install and
 * remove parts. The left side shows the case with what's inside.
 */
public class GuiPCEditing extends Screen {
	private static final int ROW = 14;

	private final EntityPC pc;
	private final MinecraftClient minecraft;
	private final Language lang = Language.getInstance();

	private float introScale;
	private float panelX;
	private boolean openCase;
	private int scroll;
	private int contentHeight;
	private String builtFor = "";
	/** Text lines drawn next to the widgets: x, y, color, text. */
	private final List<Object[]> labels = new ArrayList<>();

	private static final ItemStack CASE_NO_PANEL = new ItemStack(ItemList.PC_CASE_NO_PANEL);
	private static final ItemStack CASE_ONLY_PANEL = new ItemStack(ItemList.PC_CASE_ONLY_PANEL);
	private static final ItemStack CASE_ONLY_GLASS_PANEL = new ItemStack(ItemList.PC_CASE_GLASS_PANEL);
	private static final ItemStack MOBO = new ItemStack(ItemList.ITEM_MOTHERBOARD);
	private static final ItemStack CPU = new ItemStack(ItemList.ITEM_CPU);
	private static final ItemStack CARD = new ItemStack(ItemList.ITEM_CARD);
	private static final ItemStack RAM = new ItemStack(ItemList.ITEM_RAM);
	private static final ItemStack HARD_DRIVE = new ItemStack(ItemList.ITEM_HARDDRIVE);

	public GuiPCEditing(EntityPC pc) {
		super(new TranslatableText("mcvmcomputers.pc_editing.title"));
		this.pc = pc;
		this.minecraft = MinecraftClient.getInstance();
	}

	private String t(String key, Object... args) {
		String s = lang.get(key);
		for (Object a : args) {
			s = s.replaceFirst("%s", java.util.regex.Matcher.quoteReplacement(String.valueOf(a)));
		}
		return s;
	}

	// ---- Packets ---------------------------------------------------------------------

	private void removePart(int slot, int index) {
		PacketByteBuf b = PacketByteBufs.create();
		b.writeInt(pc.getEntityId());
		b.writeInt(slot);
		b.writeInt(index);
		ClientPlayNetworking.send(PacketList.C2S_REMOVE_PART, b);
	}

	/** Sends new settings for an installed part (the PC must be off). */
	private void configurePart(int slot, int index, String name, java.util.Map<String, String> options) {
		PacketByteBuf b = PacketByteBufs.create();
		b.writeInt(pc.getEntityId());
		b.writeInt(slot);
		b.writeInt(index);
		b.writeString(name == null ? "" : name);
		b.writeInt(options.size());
		for (java.util.Map.Entry<String, String> e : options.entrySet()) {
			b.writeString(e.getKey());
			b.writeString(e.getValue());
		}
		ClientPlayNetworking.send(PacketList.C2S_CONFIGURE_PART, b);
	}

	private void openSettings(String title, List<GuiOptions.Section> sections, java.util.Map<String, String> current,
			java.util.function.Consumer<java.util.Map<String, String>> save) {
		if (running()) {
			return;
		}
		minecraft.openScreen(new GuiOptions(this, sections, current, v -> title, v -> lang.get("mcvmcomputers.card_options.save"), save));
	}

	/** The settings button for a part, or null when it has none (or no catalog). */
	private Runnable motherboardSettings(Catalog.Machine m, Part board) {
		if (m == null || m.settings.isEmpty()) {
			return null;
		}
		return () -> openSettings(board.name, GuiOptions.groups(m.settings), board.options,
				v -> configurePart(PacketList.SLOT_MOTHERBOARD, 0, board.name, v));
	}

	private Runnable cpuSettings(Catalog cat, HardwareSpec.CpuPart cpu) {
		Catalog.CpuModel cm = cat == null ? null : cat.cpuModel(cpu.family, cpu.hz, cpu.multi);
		if (cm == null || cm.fpu.isEmpty()) {
			return null;
		}
		java.util.Map<String, String> cur = new java.util.HashMap<>();
		if (!cpu.fpu.isEmpty()) {
			cur.put(Catalog.CpuModel.FPU_KEY, cpu.fpu);
		}
		return () -> openSettings(cpu.name, GuiOptions.single(cm.fpu), cur, v -> configurePart(PacketList.SLOT_CPU, 0, cpu.name, v));
	}

	private Runnable cardSettings(Catalog cat, CardPart c, int idx) {
		Catalog.Card card = cat == null ? null : cat.card(c.category, c.id);
		if (card == null || card.settings.isEmpty()) {
			return null;
		}
		return () -> openSettings(c.name, GuiOptions.single(card.settings), c.options,
				v -> configurePart(PacketList.SLOT_CARD, idx, card.displayName(v), v));
	}

	private Runnable diskSettings(Catalog cat, DiskPart d, int idx) {
		if (cat == null || cat.hddModels.isEmpty()) {
			return null;
		}
		return () -> {
			if (running()) {
				return;
			}
			List<String[]> models = new ArrayList<>();
			models.add(new String[] { "", lang.get("mcvmcomputers.hdd.model_default") });
			models.addAll(cat.hddModels);
			minecraft.openScreen(new GuiChoicePicker(this, lang.get("mcvmcomputers.hdd.model"), models, d.model, picked -> {
				java.util.Map<String, String> v = new java.util.HashMap<>();
				v.put("model", picked);
				configurePart(PacketList.SLOT_HDD, idx, "", v);
			}));
		};
	}

	/** "Keyboard: PS/2 Keyboard  [Change] [Setup]" (or the board's built-in one). */
	private int inputRow(int x, int y, int slot, Part chosen, Catalog.Machine m, Catalog cat, int top, int bottom) {
		boolean keyboard = slot == PacketList.SLOT_KEYBOARD;
		List<Catalog.Card> all = cat == null ? new ArrayList<>() : (keyboard ? cat.keyboards : cat.mice);
		boolean builtIn = m != null && m.hasFlag(keyboard ? Catalog.MACHINE_KEYBOARD : Catalog.MACHINE_MOUSE) && chosen == null;
		Catalog.Card dev = chosen == null ? null : Catalog.find(all, chosen.id);
		String what = builtIn ? t("mcvmcomputers.pc_editing.input_builtin")
				: dev != null ? dev.name : chosen != null ? chosen.id : t("mcvmcomputers.pc_editing.input_auto");
		String label = t(keyboard ? "mcvmcomputers.pc_editing.keyboard" : "mcvmcomputers.pc_editing.mouse") + ": " + what;
		if (visible(y, top, bottom)) {
			String change = t("mcvmcomputers.pc_editing.change");
			String setup = t("mcvmcomputers.pc_editing.settings");
			int cw = this.textRenderer.getWidth(change) + 8;
			int sw = dev != null && !dev.settings.isEmpty() ? this.textRenderer.getWidth(setup) + 8 : 0;
			label(x + 6, y + 2, 0xffffff, this.textRenderer.trimToWidth(label, this.width - x - 20 - cw - sw - 4));
			int bx = this.width - 6 - cw;
			boolean fixed = keyboard && m != null && m.hasFlag(Catalog.MACHINE_KEYBOARD);
			if (!fixed && cat != null) {
				ButtonWidget cb = this.addButton(WideButton.of(bx, y, cw, 12, new LiteralText(change), b -> {
					List<String[]> choices = new ArrayList<>();
					choices.add(new String[] { "", t("mcvmcomputers.pc_editing.input_auto") });
					for (Catalog.Card c : all) {
						if (m == null || c.fits(m)) {
							choices.add(new String[] { c.id, c.name });
						}
					}
					minecraft.openScreen(new GuiChoicePicker(this, label, choices, chosen == null ? "" : chosen.id, id -> {
						Catalog.Card picked = Catalog.find(all, id);
						configurePart(slot, 0, id, picked == null ? new java.util.HashMap<>() : picked.resolve(null));
					}));
				}));
				cb.active = !running();
			}
			if (sw > 0) {
				ButtonWidget sb = this.addButton(WideButton.of(bx - sw - 2, y, sw, 12, new LiteralText(setup),
						b -> openSettings(dev.name, GuiOptions.single(dev.settings), chosen.options, v -> configurePart(slot, 0, chosen.id, v))));
				sb.active = !running();
			}
		}
		return y + ROW;
	}

	private void pick(GuiPartPicker.Filter filter) {
		minecraft.openScreen(new GuiPartPicker(this, pc, filter));
	}

	// ---- Layout ----------------------------------------------------------------------

	private boolean running() {
		return ClientMod.isRunning(pc);
	}

	/** Changes whenever the widgets need rebuilding. */
	private String signature() {
		VmSession v = ClientMod.vm;
		return openCase + "|" + scroll + "|" + width + "x" + height + "|" + System.identityHashCode(pc.getHardwareTag()) + "|"
				+ running() + "|" + (v == null ? "-" : v.state()) + "|" + ClientMod.vmPreparing + "|" + ClientMod.anyRunning() + "|"
				+ ClientMod.vmStatus;
	}

	private void rebuild() {
		this.buttons.clear();
		this.children.clear();
		labels.clear();
		builtFor = signature();
		if (openCase) {
			buildOpen();
		} else {
			buildClosed();
		}
	}

	private int panelLeft() {
		return Math.max(this.width / 2 - 20, 150);
	}

	private void label(int x, int y, int color, String s) {
		labels.add(new Object[] { x, y, color, s });
	}

	private ButtonWidget button(int x, int y, String text, ButtonWidget.PressAction action) {
		int w = this.textRenderer.getWidth(text) + 8;
		return this.addButton(WideButton.of(x, y, w, 12, new LiteralText(text), action));
	}

	private void buildClosed() {
		HardwareSpec s = pc.getHardware();
		Catalog cat = ClientMod.catalog();
		int x = panelLeft();
		int y = 24;
		boolean on = running();
		boolean busyElsewhere = ClientMod.anyRunning() && !on;

		label(x, y, 0xffffff, s.motherboard == null ? t("mcvmcomputers.pc_editing.no_motherboard") : s.motherboard.name);
		y += 11;
		if (s.cpu != null) {
			label(x, y, 0xaaaaaa, s.cpu.name + "  |  " + HardwareSpec.ramLabel(s.totalRamKb()) + " RAM");
			y += 11;
		}
		String state;
		VmSession v = ClientMod.vm;
		if (on && ClientMod.vmPreparing) {
			state = t("mcvmcomputers.pc_editing.state_starting");
		} else if (on && v != null && v.state() == VmSession.State.RUNNING) {
			state = t("mcvmcomputers.pc_editing.state_on");
		} else {
			state = t("mcvmcomputers.pc_editing.state_off");
		}
		label(x, y, on ? 0x55ff55 : 0xaaaaaa, state);
		y += 14;

		if (on) {
			button(x, y, t("mcvmcomputers.pc_editing.turn_off"), b -> ClientMod.powerOffNow());
			if (v != null && v.state() == VmSession.State.RUNNING) {
				int bx = x + this.textRenderer.getWidth(t("mcvmcomputers.pc_editing.turn_off")) + 12;
				ButtonWidget use = button(bx, y, t("mcvmcomputers.pc_editing.use"), b -> minecraft.openScreen(new GuiFocus()));
				bx += use.getWidth() + 4;
				ButtonWidget cad = button(bx, y, "Ctrl+Alt+Del", b -> v.ctrlAltDel());
				bx += cad.getWidth() + 4;
				button(bx, y, t("mcvmcomputers.pc_editing.reset"), b -> v.hardReset());
			}
		} else {
			ButtonWidget onBtn = button(x, y, t("mcvmcomputers.pc_editing.turn_on"), b -> {
				ClientMod.powerOn(pc);
				rebuild();
			});
			List<HardwareSpec.Problem> problems = s.check(cat);
			onBtn.active = !busyElsewhere && !HardwareSpec.anyBlocking(problems);
		}
		y += 18;

		// Drives and their media.
		int drive = 0;
		for (Part f : s.floppies) {
			final int d = drive++;
			String media = s.floppyImage(d);
			label(x, y + 2, 0xffffff, (char) ('A' + d) + ": " + f.name);
			y += 11;
			label(x + 8, y + 2, media.isEmpty() ? 0x777777 : 0xaaaaaa, media.isEmpty() ? t("mcvmcomputers.pc_editing.empty") : media);
			int bx = Math.max(x + 8 + this.textRenderer.getWidth(media.isEmpty() ? t("mcvmcomputers.pc_editing.empty") : media) + 6, x + 120);
			ButtonWidget ins = button(bx, y, t("mcvmcomputers.pc_editing.insert"),
					b -> minecraft.openScreen(new GuiMediaPicker(this, pc, PacketList.MEDIA_FLOPPY, d)));
			if (!media.isEmpty()) {
				button(bx + ins.getWidth() + 4, y, t("mcvmcomputers.pc_editing.eject"), b -> GuiMediaPicker.setMedia(pc, PacketList.MEDIA_FLOPPY, d, ""));
			}
			y += ROW;
		}
		drive = 0;
		for (Part c : s.cdroms) {
			final int d = drive++;
			String media = s.cdImage(d);
			label(x, y + 2, 0xffffff, "CD " + (d + 1) + ": " + c.name);
			y += 11;
			label(x + 8, y + 2, media.isEmpty() ? 0x777777 : 0xaaaaaa, media.isEmpty() ? t("mcvmcomputers.pc_editing.empty") : media);
			int bx = Math.max(x + 8 + this.textRenderer.getWidth(media.isEmpty() ? t("mcvmcomputers.pc_editing.empty") : media) + 6, x + 120);
			ButtonWidget ins = button(bx, y, t("mcvmcomputers.pc_editing.insert"),
					b -> minecraft.openScreen(new GuiMediaPicker(this, pc, PacketList.MEDIA_CD, d)));
			if (!media.isEmpty()) {
				button(bx + ins.getWidth() + 4, y, t("mcvmcomputers.pc_editing.eject"), b -> GuiMediaPicker.setMedia(pc, PacketList.MEDIA_CD, d, ""));
			}
			y += ROW;
		}
		for (DiskPart d : s.hardDisks) {
			label(x, y + 2, 0xaaaaaa, "HDD: " + d.file + " (" + HardwareSpec.ramLabel(d.sizeMb * 1024) + ")");
			y += 11;
		}
		y += 6;

		if (on && ClientMod.vmPreparing && !ClientMod.vmStatus.isEmpty()) {
			label(x, y, 0xffff55, ClientMod.vmStatus);
			y += 11;
		} else if (busyElsewhere) {
			label(x, y, 0xff5555, t("mcvmcomputers.one_vm_at_a_time"));
			y += 11;
		}
		if (!on) {
			for (HardwareSpec.Problem p : s.check(cat)) {
				label(x, y, p.blocking ? 0xff5555 : 0xffff55, p.message);
				y += 11;
			}
			if (cat == null) {
				label(x, y, 0xffff55, t("mcvmcomputers.pc_editing.no_catalog"));
				y += 11;
			}
		}

		ButtonWidget open = button(x, this.height - 24, t("mcvmcomputers.pc_editing.open_case"), b -> {
			openCase = true;
			scroll = 0;
			rebuild();
		});
		open.active = !on;
	}

	private void buildOpen() {
		HardwareSpec s = pc.getHardware();
		Catalog cat = ClientMod.catalog();
		Catalog.Machine m = (cat == null || s.motherboard == null) ? null : cat.machine(s.motherboard.id);
		int x = panelLeft();
		int y = 24 - scroll;
		int top = 20;
		int bottom = this.height - 30;

		// Motherboard.
		y = heading(x, y, t("mcvmcomputers.part.kind.motherboard"), top, bottom);
		if (s.motherboard != null) {
			y = partRow(x, y, s.motherboard.name, () -> removePart(PacketList.SLOT_MOTHERBOARD, 0), motherboardSettings(m, s.motherboard), top, bottom);
			if (m != null && visible(y, top, bottom)) {
				label(x + 6, y + 2, 0x777777, cat.machineTypeName(m.type) + ", RAM " + HardwareSpec.ramLabel(m.ramMinKb) + "-"
						+ HardwareSpec.ramLabel(m.ramMaxKb));
			}
			y += 11;
		} else {
			y = addRow(x, y, t("mcvmcomputers.pc_editing.install"), () -> pick(GuiPartPicker.Filter.MOTHERBOARD), top, bottom);
		}

		if (s.motherboard != null) {
			// CPU.
			y = heading(x, y, t("mcvmcomputers.part.kind.cpu"), top, bottom);
			if (s.cpu != null) {
				y = partRow(x, y, s.cpu.name + (s.cpu.fpu.isEmpty() ? "" : " + " + s.cpu.fpu.toUpperCase(java.util.Locale.ROOT)),
						() -> removePart(PacketList.SLOT_CPU, 0), cpuSettings(cat, s.cpu), top, bottom);
			} else {
				y = addRow(x, y, t("mcvmcomputers.pc_editing.install"), () -> pick(GuiPartPicker.Filter.CPU), top, bottom);
			}

			// RAM.
			y = heading(x, y, t("mcvmcomputers.part.kind.ram") + " (" + HardwareSpec.ramLabel(s.totalRamKb()) + ")", top, bottom);
			for (int i = 0; i < s.ramKb.size(); i++) {
				final int idx = i;
				y = partRow(x, y, HardwareSpec.ramLabel(s.ramKb.get(i)), () -> removePart(PacketList.SLOT_RAM, idx), top, bottom);
			}
			if (s.ramKb.size() < HardwareSpec.MAX_RAM_STICKS) {
				y = addRow(x, y, t("mcvmcomputers.pc_editing.add"), () -> pick(GuiPartPicker.Filter.RAM), top, bottom);
			}

			// Cards.
			y = heading(x, y, t("mcvmcomputers.pc_editing.cards", s.cards.size(), HardwareSpec.MAX_CARDS), top, bottom);
			for (int i = 0; i < s.cards.size(); i++) {
				final int idx = i;
				CardPart c = s.cards.get(i);
				y = partRow(x, y, c.name, () -> removePart(PacketList.SLOT_CARD, idx), cardSettings(cat, c, idx), top, bottom);
			}
			if (m != null && m.onboardVideo != null && visible(y, top, bottom)) {
				label(x + 6, y + 2, 0x777777, t("mcvmcomputers.part.onboard", m.onboardVideo));
				y += 11;
			}
			if (m != null && m.onboardSound != null && visible(y, top, bottom)) {
				label(x + 6, y + 2, 0x777777, t("mcvmcomputers.part.onboard", m.onboardSound));
				y += 11;
			}
			if (s.cards.size() < HardwareSpec.MAX_CARDS) {
				y = addRow(x, y, t("mcvmcomputers.pc_editing.add"), () -> pick(GuiPartPicker.Filter.CARD), top, bottom);
			}

			// Keyboard and mouse.
			y = heading(x, y, t("mcvmcomputers.pc_editing.input"), top, bottom);
			y = inputRow(x, y, PacketList.SLOT_KEYBOARD, s.keyboard, m, cat, top, bottom);
			y = inputRow(x, y, PacketList.SLOT_MOUSE, s.mouse, m, cat, top, bottom);
		}

		// Drives (they sit in the case, not on the board).
		y = heading(x, y, t("mcvmcomputers.part.kind.floppy_drive"), top, bottom);
		for (int i = 0; i < s.floppies.size(); i++) {
			final int idx = i;
			y = partRow(x, y, (char) ('A' + i) + ": " + s.floppies.get(i).name, () -> removePart(PacketList.SLOT_FLOPPY, idx), top, bottom);
		}
		if (s.floppies.size() < HardwareSpec.MAX_FLOPPIES) {
			y = addRow(x, y, t("mcvmcomputers.pc_editing.add"), () -> pick(GuiPartPicker.Filter.FLOPPY_DRIVE), top, bottom);
		}
		int ide = s.hardDisks.size() + s.cdroms.size();
		y = heading(x, y, t("mcvmcomputers.pc_editing.drives", ide, HardwareSpec.MAX_IDE_DEVICES), top, bottom);
		for (int i = 0; i < s.hardDisks.size(); i++) {
			final int idx = i;
			DiskPart d = s.hardDisks.get(i);
			String geo = d.hasGeometry() ? ", " + d.cylinders + "/" + d.heads + "/" + d.sectors : "";
			y = partRow(x, y, "HDD " + d.file + " (" + HardwareSpec.ramLabel(d.sizeMb * 1024) + geo + ")",
					() -> removePart(PacketList.SLOT_HDD, idx), diskSettings(cat, d, idx), top, bottom);
		}
		for (int i = 0; i < s.cdroms.size(); i++) {
			final int idx = i;
			y = partRow(x, y, "CD " + s.cdroms.get(i).name, () -> removePart(PacketList.SLOT_CDROM, idx), top, bottom);
		}
		if (ide < HardwareSpec.MAX_IDE_DEVICES) {
			y = addRow(x, y, t("mcvmcomputers.pc_editing.add_hdd"), () -> pick(GuiPartPicker.Filter.HARD_DISK), top, bottom);
			y = addRow(x, y, t("mcvmcomputers.pc_editing.add_cdrom"), () -> pick(GuiPartPicker.Filter.CDROM_DRIVE), top, bottom);
		}

		// Problems.
		y += 4;
		for (HardwareSpec.Problem p : s.check(cat)) {
			if (visible(y, top, bottom)) {
				label(x, y, p.blocking ? 0xff5555 : 0xffff55, p.message);
			}
			y += 11;
		}
		contentHeight = y + scroll - 24;

		button(x, this.height - 24, t("mcvmcomputers.pc_editing.close_case"), b -> {
			openCase = false;
			panelX = -panelX;
			rebuild();
		});
	}

	private static boolean visible(int y, int top, int bottom) {
		return y >= top && y <= bottom - 10;
	}

	private int heading(int x, int y, String text, int top, int bottom) {
		y += 4;
		if (visible(y, top, bottom)) {
			label(x, y + 2, 0xffff55, text);
		}
		return y + 12;
	}

	private int partRow(int x, int y, String name, Runnable remove, int top, int bottom) {
		return partRow(x, y, name, remove, null, top, bottom);
	}

	/** A part with a remove button and, if it has settings, a Setup button (only while the PC is off). */
	private int partRow(int x, int y, String name, Runnable remove, Runnable settings, int top, int bottom) {
		if (visible(y, top, bottom)) {
			int setupW = settings == null ? 0 : this.textRenderer.getWidth(t("mcvmcomputers.pc_editing.settings")) + 8;
			String shown = this.textRenderer.trimToWidth(name, this.width - x - 26 - (setupW > 0 ? setupW + 2 : 0));
			label(x + 6, y + 2, 0xffffff, shown);
			if (settings != null) {
				ButtonWidget sb = this.addButton(WideButton.of(this.width - 20 - setupW, y, setupW, 12,
						new LiteralText(t("mcvmcomputers.pc_editing.settings")), b -> settings.run()));
				sb.active = !running();
			}
			this.addButton(WideButton.of(this.width - 18, y, 12, 12, new LiteralText("x"), b -> remove.run()));
		}
		return y + ROW;
	}

	private int addRow(int x, int y, String text, Runnable action, int top, int bottom) {
		if (visible(y, top, bottom)) {
			button(x + 6, y, text, b -> action.run());
		}
		return y + ROW;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (openCase) {
			int max = Math.max(0, contentHeight - (this.height - 60));
			scroll = (int) Math.max(0, Math.min(max, scroll - amount * 20));
			rebuild();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	protected void init() {
		rebuild();
	}

	@Override
	public void tick() {
		if (pc.removed) {
			this.onClose();
		}
	}

	// ---- Drawing ---------------------------------------------------------------------

	@Override
	public void render(MatrixStack ms, int mouseX, int mouseY, float delta) {
		if (!signature().equals(builtFor)) {
			rebuild();
		}
		if (introScale > 0.92f && openCase) {
			panelX = MVCUtils.lerp(panelX, 8, delta / 20f);
		}
		if (!openCase) {
			panelX = MVCUtils.lerp(panelX, 0, delta / 1.5f);
		}
		introScale = MVCUtils.lerp(introScale, 1f, Math.max(0f, Math.min(1f, delta / 4f)));
		if (Float.isNaN(introScale)) {
			introScale = 1f;
		}
		renderCase();

		RenderSystem.disableDepthTest();
		RenderSystem.pushMatrix();
		RenderSystem.translatef(0, 0, 200);
		fill(ms, panelLeft() - 6, 18, this.width - 2, this.height - 4, 0x90000000);
		for (Object[] l : labels) {
			this.textRenderer.draw(ms, (String) l[3], (int) l[0], (int) l[1], (int) l[2]);
		}
		super.render(ms, mouseX, mouseY, delta);
		this.textRenderer.draw(ms, lang.get("mcvmcomputers.pc_editing.close"), 4, 4, -1);
		if (openCase) {
			this.textRenderer.draw(ms, lang.get("mcvmcomputers.pc_editing.scroll_hint"), 4, 14, 0x777777);
		}
		RenderSystem.popMatrix();
		RenderSystem.enableDepthTest();
	}

	private void renderCase() {
		HardwareSpec s = pc.getHardware();
		// The original built java.awt.Color from this and crashed when the value left 0..1 (issue #198).
		float shade = Float.isNaN(introScale) ? 1f : Math.max(0f, Math.min(1f, introScale));
		int dim = ((int) (shade * 0.5f * 255f)) << 24;
		this.fillGradient(new MatrixStack(), 0, 0, this.width, this.height, dim, dim);
		RenderSystem.pushMatrix();
		RenderSystem.enableRescaleNormal();
		RenderSystem.enableAlphaTest();
		RenderSystem.defaultAlphaFunc();
		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
		RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
		RenderSystem.translatef(panelLeft() / 2f, (this.height / 2f) - 40, 100.0F);
		RenderSystem.rotatef(90f * introScale, 0f, -1f, 0f);
		RenderSystem.rotatef(6f * introScale, 0, 0.1f, 0.1f);
		RenderSystem.scalef(1.0F, -1.0F, 1.0F);
		RenderSystem.scalef(introScale, introScale, introScale);
		float size = Math.min(230f, panelLeft() * 0.9f);
		RenderSystem.scalef(size, size, size);
		renderItem(CASE_NO_PANEL);
		if (s.motherboard != null) {
			RenderSystem.pushMatrix();
			RenderSystem.rotatef(90f, 0f, 0f, -1f);
			RenderSystem.rotatef(90f, 0f, -1f, 0f);
			RenderSystem.translatef(0.04f, 0.2f, -0.16f);
			RenderSystem.scalef(0.55f, 0.55f, 0.55f);
			renderItem(MOBO);
			RenderSystem.popMatrix();
		}
		if (s.cpu != null) {
			RenderSystem.pushMatrix();
			RenderSystem.rotatef(90f, 0f, 0f, -1f);
			RenderSystem.scalef(0.55f, 0.55f, 0.55f);
			RenderSystem.translatef(0.23f, 0.48f, 0.13f);
			renderItem(CPU);
			RenderSystem.popMatrix();
		}
		if (!s.cards.isEmpty()) {
			RenderSystem.pushMatrix();
			RenderSystem.rotatef(90f, 0f, -1f, 0f);
			RenderSystem.scalef(0.55f, 0.55f, 0.55f);
			RenderSystem.rotatef(-90f, 1f, 0f, 0f);
			RenderSystem.translatef(0.33f, 0.5f, -0.565f);
			renderItem(CARD);
			RenderSystem.popMatrix();
		}
		for (int i = 0; i < Math.min(2, s.ramKb.size()); i++) {
			RenderSystem.pushMatrix();
			RenderSystem.scalef(0.55f, 0.55f, 0.55f);
			RenderSystem.rotatef(90f, 0f, 0f, -1f);
			RenderSystem.translatef(0.07f, 0.5f, i == 0 ? -0.203f : -0.33f);
			renderItem(RAM);
			RenderSystem.popMatrix();
		}
		if (!s.hardDisks.isEmpty()) {
			RenderSystem.pushMatrix();
			RenderSystem.scalef(0.55f, 0.55f, 0.55f);
			RenderSystem.translatef(0.1f, -0.3f, -0.5f);
			renderItem(HARD_DRIVE);
			RenderSystem.popMatrix();
		}
		RenderSystem.pushMatrix();
		RenderSystem.translatef(0, -panelX, 0);
		renderItem(pc.getGlassSidepanel() ? CASE_ONLY_GLASS_PANEL : CASE_ONLY_PANEL);
		RenderSystem.popMatrix();
		RenderSystem.disableAlphaTest();
		RenderSystem.disableRescaleNormal();
		RenderSystem.popMatrix();
	}

	private void renderItem(ItemStack stack) {
		BakedModel model = minecraft.getItemRenderer().getHeldItemModel(stack, null, null);
		VertexConsumerProvider.Immediate immediate = minecraft.getBufferBuilders().getEntityVertexConsumers();
		boolean flat = !model.isSideLit();
		if (flat) {
			DiffuseLighting.disableGuiDepthLighting();
		}
		minecraft.getItemRenderer().renderItem(stack, Mode.NONE, false, new MatrixStack(), immediate, 15728640, OverlayTexture.DEFAULT_UV, model);
		immediate.draw();
		RenderSystem.enableDepthTest();
		if (flat) {
			DiffuseLighting.enableGuiDepthLighting();
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (openCase && keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL) {
			openCase = false;
			panelX = -panelX;
			rebuild();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
