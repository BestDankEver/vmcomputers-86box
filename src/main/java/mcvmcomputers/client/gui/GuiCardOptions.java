package mcvmcomputers.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import mcvmcomputers.emu.Catalog.Card;
import mcvmcomputers.emu.Catalog.CardOption;
import mcvmcomputers.emu.Catalog.OptionChoice;
import mcvmcomputers.item.ItemPart;
import mcvmcomputers.item.PartPricing;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Language;

/**
 * A card's 86Box Configure dialog, before buying it: model, memory, SLI and
 * the rest, straight from the catalog. Choices a model doesn't allow (such as
 * 2 MB of framebuffer on a Voodoo 2) are skipped, as in 86Box.
 */
public class GuiCardOptions extends Screen {
	private static final int ROW = 22;

	private final Screen parent;
	private final Card card;
	private final Consumer<ItemStack> onConfirm;
	private final Language lang = Language.getInstance();
	private Map<String, String> values;
	private final List<Object[]> labels = new ArrayList<>();
	private int scroll;

	public GuiCardOptions(Screen parent, Card card, Map<String, String> preset, Consumer<ItemStack> onConfirm) {
		super(new LiteralText(card.name));
		this.parent = parent;
		this.card = card;
		this.onConfirm = onConfirm;
		this.values = card.resolve(preset);
	}

	private int rowsVisible() {
		return Math.max(1, (this.height - 40 - 34) / ROW);
	}

	@Override
	protected void init() {
		this.buttons.clear();
		this.children.clear();
		labels.clear();

		int labelX = Math.max(6, this.width / 2 - 170);
		int buttonX = labelX + 170;
		int buttonW = Math.min(170, this.width - buttonX - 6);
		OptionChoice variant = card.variant(values);

		List<CardOption> opts = card.options;
		scroll = Math.max(0, Math.min(scroll, opts.size() - rowsVisible()));
		int y = 40;
		for (int i = scroll; i < Math.min(opts.size(), scroll + rowsVisible()); i++) {
			CardOption o = opts.get(i);
			String v = values.get(o.name);
			labels.add(new Object[] { labelX, y + 6, 0xffffff, this.textRenderer.trimToWidth(o.label, 164) });

			if (CardOption.SPINNER.equals(o.kind)) {
				int n = parse(v, o.min);
				ButtonWidget minus = this.addButton(new ButtonWidget(buttonX, y, 20, 20, new LiteralText("-"), b -> set(o, "" + Math.max(o.min, n - o.step))));
				minus.active = n > o.min;
				labels.add(new Object[] { buttonX + 26, y + 6, 0xffffff, v });
				ButtonWidget plus = this.addButton(new ButtonWidget(buttonX + buttonW - 20, y, 20, 20, new LiteralText("+"), b -> set(o, "" + Math.min(o.max, n + o.step))));
				plus.active = n < o.max;
			} else {
				List<String> allowed = allowedValues(o, variant);
				String shown = display(o, v);
				ButtonWidget b = this.addButton(new ButtonWidget(buttonX, y, buttonW, 20,
						new LiteralText(this.textRenderer.trimToWidth(shown, buttonW - 8)), btn -> {
							int at = allowed.indexOf(values.get(o.name));
							int step = Screen.hasShiftDown() ? -1 : 1;
							set(o, allowed.get(Math.floorMod(at + step, allowed.size())));
						}));
				b.active = allowed.size() > 1;
			}
			y += ROW;
		}

		ItemStack preview = ItemPart.card(card, values);
		String buy = lang.get("mcvmcomputers.card_options.add").replace("%s", "" + PartPricing.price(preview));
		this.addButton(new ButtonWidget(this.width / 2 - 154, this.height - 28, 150, 20, new LiteralText(buy), b -> {
			onConfirm.accept(ItemPart.card(card, values));
			MinecraftClient.getInstance().openScreen(parent);
		}));
		this.addButton(new ButtonWidget(this.width / 2 + 4, this.height - 28, 150, 20, new TranslatableText("gui.cancel"),
				b -> MinecraftClient.getInstance().openScreen(parent)));
	}

	private void set(CardOption o, String value) {
		Map<String, String> m = new LinkedHashMap<>(values);
		m.put(o.name, value);
		values = card.resolve(m);
		init();
	}

	private static List<String> allowedValues(CardOption o, OptionChoice variant) {
		List<String> out = new ArrayList<>();
		if (CardOption.TOGGLE.equals(o.kind)) {
			out.add("0");
			out.add("1");
			return out;
		}
		for (OptionChoice c : o.choices) {
			if (CardOption.VARIANT.equals(o.kind) || o.allowed(c.value, variant)) {
				out.add(c.value);
			}
		}
		return out;
	}

	private String display(CardOption o, String v) {
		if (CardOption.TOGGLE.equals(o.kind)) {
			return lang.get("1".equals(v) ? "options.on" : "options.off");
		}
		OptionChoice c = o.choice(v);
		return c == null ? v : c.label;
	}

	private static int parse(String s, int def) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return def;
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		scroll = Math.max(0, scroll - (int) Math.signum(amount));
		init();
		return true;
	}

	@Override
	public void onClose() {
		MinecraftClient.getInstance().openScreen(parent);
	}

	@Override
	public void render(MatrixStack ms, int mouseX, int mouseY, float delta) {
		this.renderBackground(ms);
		String title = card.displayName(values);
		this.textRenderer.drawWithShadow(ms, title, (this.width - this.textRenderer.getWidth(title)) / 2f, 12, 0xffffff);
		String hint = lang.get("mcvmcomputers.card_options.hint");
		this.textRenderer.draw(ms, hint, (this.width - this.textRenderer.getWidth(hint)) / 2f, 24, 0x888888);
		for (Object[] l : labels) {
			this.textRenderer.draw(ms, (String) l[3], (int) l[0], (int) l[1], (int) l[2]);
		}
		super.render(ms, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
