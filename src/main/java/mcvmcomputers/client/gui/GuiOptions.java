package mcvmcomputers.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import mcvmcomputers.emu.Catalog;
import mcvmcomputers.emu.Catalog.CardOption;
import mcvmcomputers.emu.Catalog.OptionChoice;
import mcvmcomputers.emu.Catalog.OptionSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Language;

/**
 * A part's 86Box Configure dialog(s): BIOS version, memory, IRQs, SLI and the
 * rest, straight from the catalog. A motherboard shows one section per chip.
 * Choices a model doesn't allow (such as 2 MB of framebuffer on a Voodoo 2)
 * are skipped, as in 86Box. Long lists open a searchable picker.
 */
public class GuiOptions extends Screen {
	private static final int ROW = 22;
	/** Lists longer than this open the picker instead of cycling. */
	private static final int CYCLE_MAX = 8;

	public static final class Section {
		final String heading;
		final String prefix;
		final OptionSet set;

		public Section(String heading, String prefix, OptionSet set) {
			this.heading = heading;
			this.prefix = prefix;
			this.set = set;
		}
	}

	private final Screen parent;
	private final List<Section> sections;
	private final Function<Map<String, String>, String> titleOf;
	private final Function<Map<String, String>, String> confirmLabel;
	private final Consumer<Map<String, String>> onConfirm;
	private final Language lang = Language.getInstance();
	/** All values, with each section's prefix. */
	private Map<String, String> values;
	private final List<Object[]> labels = new ArrayList<>();
	private int scroll;

	/**
	 * @param titleOf      the screen title for the current values
	 * @param confirmLabel the confirm button's text for the current values (e.g. with the price)
	 * @param onConfirm    gets the resolved values, with prefixes
	 */
	public GuiOptions(Screen parent, List<Section> sections, Map<String, String> initial, Function<Map<String, String>, String> titleOf,
			Function<Map<String, String>, String> confirmLabel, Consumer<Map<String, String>> onConfirm) {
		super(Text.literal(""));
		this.parent = parent;
		this.sections = sections;
		this.titleOf = titleOf;
		this.confirmLabel = confirmLabel;
		this.onConfirm = onConfirm;
		this.values = resolve(initial);
	}

	/** One part with one settings set, no prefixes (cards, CPUs). */
	public static List<Section> single(OptionSet set) {
		List<Section> l = new ArrayList<>();
		l.add(new Section(null, "", set));
		return l;
	}

	/** A motherboard: the board and each built-in chip. */
	public static List<Section> groups(List<Catalog.SettingsGroup> groups) {
		List<Section> l = new ArrayList<>();
		for (Catalog.SettingsGroup g : groups) {
			l.add(new Section(groups.size() > 1 ? g.label : null, g.prefix, g.set));
		}
		return l;
	}

	private Map<String, String> sub(Section s, Map<String, String> all) {
		Map<String, String> out = new LinkedHashMap<>();
		if (all != null) {
			for (Map.Entry<String, String> e : all.entrySet()) {
				String k = e.getKey();
				if (k.startsWith(s.prefix) && (!s.prefix.isEmpty() || k.indexOf('/') < 0)) {
					out.put(k.substring(s.prefix.length()), e.getValue());
				}
			}
		}
		return out;
	}

	private Map<String, String> resolve(Map<String, String> all) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Section s : sections) {
			for (Map.Entry<String, String> e : s.set.resolve(sub(s, all)).entrySet()) {
				out.put(s.prefix + e.getKey(), e.getValue());
			}
		}
		return out;
	}

	/** Rows: a String is a heading, an Object[]{Section, CardOption} an option. */
	private List<Object> rows() {
		List<Object> r = new ArrayList<>();
		for (Section s : sections) {
			if (s.heading != null) {
				r.add(s.heading);
			}
			for (CardOption o : s.set.options) {
				r.add(new Object[] { s, o });
			}
		}
		return r;
	}

	private int rowsVisible() {
		return Math.max(1, (this.height - 40 - 34) / ROW);
	}

	@Override
	protected void init() {
		this.clearChildren();
		labels.clear();

		int labelX = Math.max(6, this.width / 2 - 180);
		int buttonX = labelX + 175;
		int buttonW = Math.min(185, this.width - buttonX - 6);

		List<Object> rows = rows();
		scroll = Math.max(0, Math.min(scroll, rows.size() - rowsVisible()));
		int y = 40;
		for (int i = scroll; i < Math.min(rows.size(), scroll + rowsVisible()); i++) {
			Object row = rows.get(i);
			if (row instanceof String) {
				labels.add(new Object[] { labelX, y + 8, 0xffff55, (String) row });
				y += ROW;
				continue;
			}
			Section s = (Section) ((Object[]) row)[0];
			CardOption o = (CardOption) ((Object[]) row)[1];
			String key = s.prefix + o.name;
			String v = values.get(key);
			OptionChoice variant = s.set.variant(sub(s, values));
			labels.add(new Object[] { labelX, y + 6, 0xffffff, this.textRenderer.trimToWidth(o.label, 170) });

			if (CardOption.SPINNER.equals(o.kind)) {
				int n = parse(v, o.min);
				ButtonWidget minus = this.addDrawableChild(WideButton.of(buttonX, y, 20, 20, Text.literal("-"),
						b -> set(key, "" + Math.max(o.min, n - o.step * (hasShiftDown() ? 10 : 1)))));
				minus.active = n > o.min;
				labels.add(new Object[] { buttonX + 26, y + 6, 0xffffff, v });
				ButtonWidget plus = this.addDrawableChild(WideButton.of(buttonX + buttonW - 20, y, 20, 20, Text.literal("+"),
						b -> set(key, "" + Math.min(o.max, n + o.step * (hasShiftDown() ? 10 : 1)))));
				plus.active = n < o.max;
			} else {
				List<String[]> allowed = allowedValues(o, variant);
				String shown = display(o, v);
				ButtonWidget b = this.addDrawableChild(WideButton.of(buttonX, y, buttonW, 20,
						Text.literal(this.textRenderer.trimToWidth(shown + (allowed.size() > CYCLE_MAX ? "  ..." : ""), buttonW - 8)), btn -> {
							if (allowed.size() > CYCLE_MAX) {
								MinecraftClient.getInstance().setScreen(new GuiChoicePicker(this, o.label, allowed, values.get(key), picked -> set(key, picked)));
								return;
							}
							int at = indexOf(allowed, values.get(key));
							int step = hasShiftDown() ? -1 : 1;
							set(key, allowed.get(Math.floorMod(at + step, allowed.size()))[0]);
						}));
				b.active = allowed.size() > 1;
			}
			y += ROW;
		}

		this.addDrawableChild(WideButton.of(this.width / 2 - 154, this.height - 28, 150, 20, Text.literal(confirmLabel.apply(values)), b -> {
			MinecraftClient.getInstance().setScreen(parent);
			onConfirm.accept(values);
		}));
		this.addDrawableChild(WideButton.of(this.width / 2 + 4, this.height - 28, 150, 20, Text.translatable("gui.cancel"),
				b -> MinecraftClient.getInstance().setScreen(parent)));
	}

	private void set(String key, String value) {
		Map<String, String> m = new LinkedHashMap<>(values);
		m.put(key, value);
		values = resolve(m);
		init();
	}

	private static int indexOf(List<String[]> l, String v) {
		for (int i = 0; i < l.size(); i++) {
			if (l.get(i)[0].equals(v)) {
				return i;
			}
		}
		return -1;
	}

	private List<String[]> allowedValues(CardOption o, OptionChoice variant) {
		List<String[]> out = new ArrayList<>();
		if (CardOption.TOGGLE.equals(o.kind)) {
			out.add(new String[] { "0", lang.get("options.off") });
			out.add(new String[] { "1", lang.get("options.on") });
			return out;
		}
		for (OptionChoice c : o.choices) {
			if (CardOption.VARIANT.equals(o.kind) || o.allowed(c.value, variant)) {
				out.add(new String[] { c.value, c.label });
			}
		}
		return out;
	}

	private String display(CardOption o, String v) {
		if (CardOption.TOGGLE.equals(o.kind)) {
			return lang.get("1".equals(v) ? "options.on" : "options.off");
		}
		return OptionSet.valueLabel(o, v);
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
	public void close() {
		MinecraftClient.getInstance().setScreen(parent);
	}

	@Override
	public void render(DrawContext ms, int mouseX, int mouseY, float delta) {
		this.renderBackground(ms);
		String title = titleOf.apply(values);
		ms.drawTextWithShadow(this.textRenderer, title, (int) ((this.width - this.textRenderer.getWidth(title)) / 2f), 10, 0xffffff);
		String hint = lang.get("mcvmcomputers.card_options.hint");
		ms.drawText(this.textRenderer, hint, (int) ((this.width - this.textRenderer.getWidth(hint)) / 2f), 24, 0x888888, false);
		for (Object[] l : labels) {
			ms.drawText(this.textRenderer, (String) l[3], (int) ((int) l[0]), (int) ((int) l[1]), (int) l[2], false);
		}
		super.render(ms, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
