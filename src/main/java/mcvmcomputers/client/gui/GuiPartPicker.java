package mcvmcomputers.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

import mcvmcomputers.client.ClientMod;
import mcvmcomputers.emu.Catalog;
import mcvmcomputers.emu.HardwareSpec;
import mcvmcomputers.emu.HardwareSpec.CardPart;
import mcvmcomputers.emu.HardwareSpec.CpuPart;
import mcvmcomputers.entities.EntityPC;
import mcvmcomputers.item.ItemHarddrive;
import mcvmcomputers.item.ItemPart;
import mcvmcomputers.networking.PacketList;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Language;

/** Lists the parts of one kind in the player's inventory, marking which fit this PC. */
public class GuiPartPicker extends Screen {
	public enum Filter {
		MOTHERBOARD, CPU, RAM, CARD, FLOPPY_DRIVE, CDROM_DRIVE, HARD_DISK
	}

	private final Screen parent;
	private final EntityPC pc;
	private final Filter filter;
	private final Language lang = Language.getInstance();
	private int scroll;
	private final List<Object[]> labels = new ArrayList<>();

	public GuiPartPicker(Screen parent, EntityPC pc, Filter filter) {
		super(Text.translatable("mcvmcomputers.picker.title"));
		this.parent = parent;
		this.pc = pc;
		this.filter = filter;
	}

	private boolean matches(ItemStack s) {
		if (s.isEmpty()) {
			return false;
		}
		if (filter == Filter.HARD_DISK) {
			return s.getItem() instanceof ItemHarddrive;
		}
		if (!(s.getItem() instanceof ItemPart)) {
			return false;
		}
		ItemPart.Kind k = ((ItemPart) s.getItem()).kind;
		switch (filter) {
		case MOTHERBOARD:
			return k == ItemPart.Kind.MOTHERBOARD;
		case CPU:
			return k == ItemPart.Kind.CPU;
		case RAM:
			return k == ItemPart.Kind.RAM;
		case CARD:
			return k == ItemPart.Kind.CARD;
		case FLOPPY_DRIVE:
			return k == ItemPart.Kind.FLOPPY_DRIVE;
		case CDROM_DRIVE:
			return k == ItemPart.Kind.CDROM_DRIVE;
		default:
			return false;
		}
	}

	/** Why a part can't go in, or null if it fits (or we can't tell without a catalog). */
	private String problem(ItemStack s) {
		HardwareSpec spec = pc.getHardware();
		if (filter == Filter.HARD_DISK) {
			String f = ItemHarddrive.fileOf(s);
			if (f.isEmpty()) {
				return lang.get("mcvmcomputers.hdd_not_set_up");
			}
			for (HardwareSpec.DiskPart d : spec.hardDisks) {
				if (d.file.equals(f)) {
					return lang.get("mcvmcomputers.picker.already_in");
				}
			}
			return null;
		}
		Catalog cat = ClientMod.catalog();
		if (cat == null || spec.motherboard == null) {
			return null;
		}
		Catalog.Machine m = cat.machine(spec.motherboard.id);
		if (m == null) {
			return null;
		}
		if (filter == Filter.CPU) {
			CpuPart c = ItemPart.toCpu(s);
			return (c != null && cat.cpuFits(m, c.family, c.hz, c.multi)) ? null : lang.get("mcvmcomputers.picker.wrong_socket");
		}
		if (filter == Filter.CARD) {
			CardPart c = ItemPart.toCard(s);
			Catalog.Card card = c == null ? null : cat.card(c.category, c.id);
			if (card == null) {
				return lang.get("mcvmcomputers.picker.unknown_part");
			}
			return card.fits(m) ? null : lang.get("mcvmcomputers.picker.wrong_slot");
		}
		if (filter == Filter.RAM) {
			int after = spec.totalRamKb() + ItemPart.ramKb(s);
			if (after > m.ramMaxKb) {
				return lang.get("mcvmcomputers.picker.too_much_ram").replace("%s", HardwareSpec.ramLabel(m.ramMaxKb));
			}
		}
		return null;
	}

	private void install(int slot) {
		PacketByteBuf b = PacketByteBufs.create();
		b.writeInt(pc.getId());
		b.writeInt(slot);
		ClientPlayNetworking.send(PacketList.C2S_INSTALL_PART, b);
		MinecraftClient.getInstance().setScreen(parent);
	}

	@Override
	protected void init() {
		this.clearChildren();
		labels.clear();
		PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
		int x = this.width / 2 - 150;
		int y = 40 - scroll;
		int count = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!matches(s)) {
				continue;
			}
			count++;
			if (y >= 30 && y < this.height - 40) {
				final int slot = i;
				String why = problem(s);
				labels.add(new Object[] { x, y + 2, why == null ? 0xffffff : 0x999999,
						this.textRenderer.trimToWidth(s.getName().getString(), 230) });
				if (why != null) {
					labels.add(new Object[] { x + 8, y + 12, 0xff5555, why });
				}
				ButtonWidget b = this.addDrawableChild(WideButton.of(x + 240, y, 60, 12, Text.literal(lang.get("mcvmcomputers.pc_editing.install")),
						w -> install(slot)));
				b.active = why == null;
			}
			y += problem(s) == null ? 16 : 26;
		}
		if (count == 0) {
			labels.add(new Object[] { x, 44, 0xaaaaaa, lang.get(filter == Filter.HARD_DISK ? "mcvmcomputers.picker.none_hdd" : "mcvmcomputers.picker.none") });
		}
		this.addDrawableChild(WideButton.of(this.width / 2 - 40, this.height - 28, 80, 20, Text.literal(lang.get("gui.back")),
				w -> MinecraftClient.getInstance().setScreen(parent)));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		scroll = Math.max(0, scroll - (int) (amount * 16));
		init();
		return true;
	}

	@Override
	public void render(DrawContext ms, int mouseX, int mouseY, float delta) {
		this.renderBackground(ms);
		String title = lang.get("mcvmcomputers.picker." + filter.name().toLowerCase(java.util.Locale.ROOT));
		ms.drawText(this.textRenderer, title, (int) (this.width / 2 - this.textRenderer.getWidth(title) / 2), 16, 0xffffff, false);
		for (Object[] l : labels) {
			ms.drawText(this.textRenderer, (String) l[3], (int) ((int) l[0]), (int) ((int) l[1]), (int) l[2], false);
		}
		super.render(ms, mouseX, mouseY, delta);
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
