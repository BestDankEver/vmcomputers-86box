package mcvmcomputers.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Supplier;

import mcvmcomputers.client.ClientMod;
import mcvmcomputers.emu.Catalog;
import mcvmcomputers.emu.Catalog.Card;
import mcvmcomputers.emu.Catalog.CardCategory;
import mcvmcomputers.emu.Catalog.CpuChoice;
import mcvmcomputers.emu.Catalog.CpuFamily;
import mcvmcomputers.emu.Catalog.CpuModel;
import mcvmcomputers.emu.Catalog.DriveType;
import mcvmcomputers.emu.Catalog.Machine;
import mcvmcomputers.emu.HardwareSpec;
import mcvmcomputers.item.ItemList;
import mcvmcomputers.item.ItemPart;
import mcvmcomputers.item.OrderableItem;
import mcvmcomputers.item.PartPricing;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Language;

/**
 * The parts shop: everything in the 86Box catalog, searchable, optionally
 * narrowed to what fits a chosen motherboard. Adds to the tablet's cart.
 */
public class GuiPartsCatalog extends Screen {
	private enum Tab {
		BOARDS("mcvmcomputers.catalog.tab.boards"), CPUS("mcvmcomputers.catalog.tab.cpus"), RAM("mcvmcomputers.catalog.tab.ram"),
		VIDEO("mcvmcomputers.catalog.tab.video"), SOUND("mcvmcomputers.catalog.tab.sound"), NETWORK("mcvmcomputers.catalog.tab.network"),
		STORAGE("mcvmcomputers.catalog.tab.storage"), DRIVES("mcvmcomputers.catalog.tab.drives");

		final String key;

		Tab(String key) {
			this.key = key;
		}
	}

	private static final class Entry {
		final String name;
		final String detail;
		final Supplier<ItemStack> make;
		/** A card with settings to pick before it goes in the cart, and the ones this entry starts with. */
		final Card configurable;
		final Map<String, String> preset;
		int price = -1;

		Entry(String name, String detail, Supplier<ItemStack> make, Card configurable, Map<String, String> preset) {
			this.name = name;
			this.detail = detail;
			this.make = make;
			this.configurable = configurable;
			this.preset = preset;
		}

		int price() {
			if (price < 0) {
				price = PartPricing.price(make.get());
			}
			return price;
		}
	}

	private static final int[] RAM_SIZES_KB = { 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288, 1048576 };
	private static final int ROW = 14;

	private static Tab tab = Tab.BOARDS;
	private static boolean onlyFitting = true;
	private static String lastSearch = "";

	private final Language lang = Language.getInstance();
	private TextFieldWidget search;
	private List<Entry> entries = new ArrayList<>();
	private int scroll;
	private Machine target;
	private String targetSource = "";
	private final List<Object[]> labels = new ArrayList<>();
	private String flash = "";
	private long flashUntil;

	public GuiPartsCatalog() {
		super(new TranslatableText("mcvmcomputers.catalog.title"));
	}

	/** Which motherboard "fits" means: last board put in the cart, else the PC last opened, else one in the inventory. */
	private void findTarget(Catalog cat) {
		target = null;
		targetSource = "";
		List<ItemStack> cart = ClientMod.tabletOS == null ? new ArrayList<>() : ClientMod.tabletOS.cart();
		for (int i = cart.size() - 1; i >= 0 && target == null; i--) {
			if (cart.get(i).getItem() == ItemList.ITEM_MOTHERBOARD) {
				HardwareSpec.Part p = ItemPart.toPart(cart.get(i));
				target = p == null ? null : cat.machine(p.id);
				targetSource = lang.get("mcvmcomputers.catalog.target_cart");
			}
		}
		if (target == null && ClientMod.currentPC != null && !ClientMod.currentPC.removed && ClientMod.currentPC.getHardware().motherboard != null) {
			target = cat.machine(ClientMod.currentPC.getHardware().motherboard.id);
			targetSource = lang.get("mcvmcomputers.catalog.target_pc");
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		if (target == null && mc.player != null) {
			for (int i = 0; i < mc.player.inventory.size() && target == null; i++) {
				ItemStack s = mc.player.inventory.getStack(i);
				if (s.getItem() == ItemList.ITEM_MOTHERBOARD) {
					HardwareSpec.Part p = ItemPart.toPart(s);
					target = p == null ? null : cat.machine(p.id);
					targetSource = lang.get("mcvmcomputers.catalog.target_inventory");
				}
			}
		}
	}

	private boolean filterOn() {
		return onlyFitting && target != null && tab != Tab.BOARDS && tab != Tab.DRIVES;
	}

	private void buildEntries() {
		Catalog cat = ClientMod.catalog();
		entries = new ArrayList<>();
		if (cat == null) {
			return;
		}
		findTarget(cat);
		String q = lastSearch.toLowerCase(Locale.ROOT).trim();
		boolean fit = filterOn();
		switch (tab) {
		case BOARDS:
			for (Machine m : cat.machines) {
				add(m.name, cat.machineTypeName(m.type) + "  RAM " + HardwareSpec.ramLabel(m.ramMinKb) + "-" + HardwareSpec.ramLabel(m.ramMaxKb),
						() -> ItemPart.motherboard(m), q);
			}
			break;
		case CPUS:
			if (fit) {
				for (CpuChoice c : cat.cpusFor(target)) {
					add(c.displayName(), lang.get("mcvmcomputers.catalog.multiplier") + " " + c.model.multi + "x", () -> ItemPart.cpu(c.family, c.model), q);
				}
			} else {
				for (CpuFamily f : cat.cpuFamilies) {
					for (CpuModel c : f.cpus) {
						add(f.displayName() + " " + c.speedLabel(), lang.get("mcvmcomputers.catalog.multiplier") + " " + c.multi + "x",
								() -> ItemPart.cpu(f, c), q);
					}
				}
			}
			break;
		case RAM:
			for (int kb : RAM_SIZES_KB) {
				if (fit && kb > target.ramMaxKb) {
					continue;
				}
				add(HardwareSpec.ramLabel(kb) + " RAM", "", () -> ItemPart.ram(kb), q);
			}
			break;
		case VIDEO:
			// 3D accelerators and other add-ons first: there are only a few, below 150-odd main cards.
			cards(cat, CardCategory.ACCEL, fit, q);
			cards(cat, CardCategory.VIDEO, fit, q);
			break;
		case SOUND:
			cards(cat, CardCategory.SOUND, fit, q);
			break;
		case NETWORK:
			cards(cat, CardCategory.NETWORK, fit, q);
			break;
		case STORAGE:
			cards(cat, CardCategory.HDC, fit, q);
			cards(cat, CardCategory.SCSI, fit, q);
			cards(cat, CardCategory.FDC, fit, q);
			break;
		case DRIVES:
			for (OrderableItem i : ItemList.STORE_CASES) {
				add(new ItemStack(i).getName().getString(), "", () -> new ItemStack(i), q);
			}
			for (DriveType d : cat.floppyDrives) {
				add(lang.get("mcvmcomputers.part.kind.floppy_drive") + ": " + d.name, "", () -> ItemPart.floppyDrive(d), q);
			}
			for (DriveType d : cat.cdromDrives) {
				add("CD-ROM: " + d.name, d.speed > 0 ? d.speed + "x" : "", () -> ItemPart.cdromDrive(d), q);
			}
			break;
		default:
			break;
		}
	}

	private void cards(Catalog cat, CardCategory c, boolean fit, String q) {
		for (Card card : cat.cards.get(c)) {
			if (fit && !card.fits(target)) {
				continue;
			}
			String detail = lang.get("mcvmcomputers.part.card." + c.key);
			Catalog.CardOption models = card.variantOption();
			if (models == null) {
				add(card.name, detail, () -> ItemPart.card(card), q, card.options.isEmpty() ? null : card, null);
				continue;
			}
			// One entry per model, e.g. Voodoo Graphics and Voodoo 2 from the one Voodoo device.
			for (Catalog.OptionChoice v : models.choices) {
				Map<String, String> preset = Collections.singletonMap(models.name, v.value);
				add(card.displayName(preset), detail, () -> ItemPart.card(card, preset), q, card, preset);
			}
		}
	}

	private void add(String name, String detail, Supplier<ItemStack> make, String q) {
		add(name, detail, make, q, null, null);
	}

	private void add(String name, String detail, Supplier<ItemStack> make, String q, Card configurable, Map<String, String> preset) {
		if (q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q) || detail.toLowerCase(Locale.ROOT).contains(q)) {
			entries.add(new Entry(name, detail, make, configurable, preset));
		}
	}

	private void addToCart(ItemStack s, String name) {
		if (ClientMod.tabletOS == null) {
			return;
		}
		ClientMod.tabletOS.addToCart(s);
		flash = lang.get("mcvmcomputers.catalog.added").replace("%s", name);
		flashUntil = System.currentTimeMillis() + 2000;
	}

	private int listTop() {
		return 64;
	}

	private int rowsVisible() {
		return Math.max(1, (this.height - listTop() - 34) / ROW);
	}

	@Override
	protected void init() {
		this.buttons.clear();
		this.children.clear();
		labels.clear();

		// Tabs.
		int x = 6;
		for (Tab tb : Tab.values()) {
			String n = lang.get(tb.key);
			int w = this.textRenderer.getWidth(n) + 10;
			ButtonWidget b = this.addButton(new ButtonWidget(x, 4, w, 16, new LiteralText(n), btn -> {
				tab = tb;
				scroll = 0;
				buildEntries();
				init();
			}));
			b.active = tb != tab;
			x += w + 2;
		}

		String old = search == null ? lastSearch : search.getText();
		search = new TextFieldWidget(this.textRenderer, 6, 24, 180, 16, new LiteralText(""));
		search.setMaxLength(64);
		search.setText(old);
		search.setChangedListener(s -> {
			lastSearch = s;
			scroll = 0;
			buildEntries();
			rebuildRows();
		});
		this.children.add(search);
		this.setInitialFocus(search);

		buildEntries();
		rebuildRows();
	}

	/** Rebuilds the row buttons and filter toggle, keeping the search box. */
	private void rebuildRows() {
		List<Object> keep = new ArrayList<>();
		for (Object o : this.buttons) {
			ButtonWidget b = (ButtonWidget) o;
			if (b.y == 4) {
				keep.add(b);
			}
		}
		this.buttons.clear();
		this.children.clear();
		for (Object o : keep) {
			this.addButton((ButtonWidget) o);
		}
		this.children.add(search);
		labels.clear();

		if (target != null && tab != Tab.BOARDS && tab != Tab.DRIVES) {
			String label = lang.get(onlyFitting ? "mcvmcomputers.catalog.only_fitting_on" : "mcvmcomputers.catalog.only_fitting_off");
			this.addButton(new ButtonWidget(192, 24, this.textRenderer.getWidth(label) + 10, 16, new LiteralText(label), b -> {
				onlyFitting = !onlyFitting;
				scroll = 0;
				buildEntries();
				rebuildRows();
			}));
			labels.add(new Object[] { 6, 46, 0xaaaaaa, this.textRenderer.trimToWidth(
					lang.get("mcvmcomputers.catalog.target").replace("%s", target.name + " (" + targetSource + ")"), this.width - 12) });
		} else if (ClientMod.catalog() == null) {
			labels.add(new Object[] { 6, 46, 0xff5555, lang.get("mcvmcomputers.catalog.missing") });
		}

		int max = Math.max(0, entries.size() - rowsVisible());
		scroll = Math.max(0, Math.min(scroll, max));
		int y = listTop();
		for (int i = scroll; i < Math.min(entries.size(), scroll + rowsVisible()); i++) {
			Entry e = entries.get(i);
			int priceX = this.width - 120;
			labels.add(new Object[] { 10, y + 3, 0xffffff, this.textRenderer.trimToWidth(e.name, Math.max(60, priceX - 220)) });
			if (!e.detail.isEmpty()) {
				labels.add(new Object[] { Math.max(70, priceX - 200), y + 3, 0x888888, this.textRenderer.trimToWidth(e.detail, 190) });
			}
			String price = lang.get("mcvmcomputers.catalog.price").replace("%s", "" + e.price());
			labels.add(new Object[] { priceX, y + 3, 0xdddddd, e.configurable == null ? price : price + "+" });
			String addLabel = lang.get(e.configurable == null ? "mcvmcomputers.catalog.add" : "mcvmcomputers.catalog.configure");
			this.addButton(new ButtonWidget(this.width - 52, y, 46, 13, new LiteralText(addLabel), b -> {
				if (ClientMod.tabletOS == null) {
					return;
				}
				if (e.configurable != null) {
					MinecraftClient.getInstance().openScreen(new GuiCardOptions(this, e.configurable, e.preset,
							s -> addToCart(s, s.getName().getString())));
					return;
				}
				ItemStack s = e.make.get();
				addToCart(s, e.name);
				if (s.getItem() == ItemList.ITEM_MOTHERBOARD) {
					buildEntries(); // the new board becomes what "fits" means
					rebuildRows();
				}
			}));
			y += ROW;
		}

		this.addButton(new ButtonWidget(this.width - 106, this.height - 26, 100, 20, new LiteralText(lang.get("mcvmcomputers.catalog.done")),
				b -> this.onClose()));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		scroll = Math.max(0, scroll - (int) Math.signum(amount) * 3);
		rebuildRows();
		return true;
	}

	@Override
	public void tick() {
		search.tick();
	}

	@Override
	public void render(MatrixStack ms, int mouseX, int mouseY, float delta) {
		this.renderBackground(ms);
		search.render(ms, mouseX, mouseY, delta);
		for (Object[] l : labels) {
			this.textRenderer.draw(ms, (String) l[3], (int) l[0], (int) l[1], (int) l[2]);
		}
		int cartCount = ClientMod.tabletOS == null ? 0 : ClientMod.tabletOS.cart().size();
		int cartPrice = ClientMod.tabletOS == null ? 0 : ClientMod.tabletOS.cartPrice();
		String footer = lang.get("mcvmcomputers.catalog.footer").replaceFirst("%s", "" + entries.size()).replaceFirst("%s", "" + cartCount)
				.replaceFirst("%s", "" + cartPrice);
		this.textRenderer.draw(ms, footer, 6, this.height - 20, 0xaaaaaa);
		if (System.currentTimeMillis() < flashUntil) {
			this.textRenderer.draw(ms, this.textRenderer.trimToWidth(flash, this.width - 130), 6, this.height - 32, 0x55ff55);
		}
		super.render(ms, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
