package mcvmcomputers.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;

/** A searchable list to pick one value from, for settings with too many choices to click through. */
public class GuiChoicePicker extends Screen {
	private static final int ROW = 16;

	private final Screen parent;
	private final String heading;
	/** {value, label} pairs. */
	private final List<String[]> choices;
	private final String current;
	private final Consumer<String> onPick;
	private TextFieldWidget search;
	private String query = "";
	private List<String[]> shown = new ArrayList<>();
	private int scroll;

	public GuiChoicePicker(Screen parent, String heading, List<String[]> choices, String current, Consumer<String> onPick) {
		super(Text.literal(heading));
		this.parent = parent;
		this.heading = heading;
		this.choices = choices;
		this.current = current;
		this.onPick = onPick;
		// Start scrolled to the current choice.
		for (int i = 0; i < choices.size(); i++) {
			if (choices.get(i)[0].equals(current)) {
				scroll = Math.max(0, i - 3);
			}
		}
	}

	private int rowsVisible() {
		return Math.max(1, (this.height - 50 - 34) / ROW);
	}

	private void filter() {
		shown = new ArrayList<>();
		String q = query.toLowerCase(Locale.ROOT).trim();
		for (String[] c : choices) {
			if (q.isEmpty() || c[1].toLowerCase(Locale.ROOT).contains(q) || c[0].toLowerCase(Locale.ROOT).contains(q)) {
				shown.add(c);
			}
		}
	}

	@Override
	protected void init() {
		String old = search == null ? query : search.getText();
		search = new TextFieldWidget(this.textRenderer, this.width / 2 - 150, 24, 300, 16, Text.literal(""));
		search.setMaxLength(64);
		search.setText(old);
		search.setChangedListener(s -> {
			query = s;
			scroll = 0;
			rows();
		});
		this.setInitialFocus(search);
		rows();
	}

	private void rows() {
		this.clearChildren();
		this.addSelectableChild(search);
		filter();
		scroll = Math.max(0, Math.min(scroll, shown.size() - rowsVisible()));
		int y = 48;
		int w = Math.min(400, this.width - 20);
		for (int i = scroll; i < Math.min(shown.size(), scroll + rowsVisible()); i++) {
			String[] c = shown.get(i);
			String label = (c[0].equals(current) ? "> " : "") + c[1];
			this.addDrawableChild(WideButton.of(this.width / 2 - w / 2, y, w, ROW - 2,
					Text.literal(this.textRenderer.trimToWidth(label, w - 8)), b -> {
						onPick.accept(c[0]);
						MinecraftClient.getInstance().setScreen(parent);
					}));
			y += ROW;
		}
		this.addDrawableChild(WideButton.of(this.width / 2 - 75, this.height - 28, 150, 20, Text.translatable("gui.cancel"),
				b -> MinecraftClient.getInstance().setScreen(parent)));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		scroll = Math.max(0, scroll - (int) Math.signum(amount) * 3);
		rows();
		return true;
	}

	@Override
	public void tick() {
		search.tick();
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(parent);
	}

	@Override
	public void render(DrawContext ms, int mouseX, int mouseY, float delta) {
		this.renderBackground(ms);
		ms.drawTextWithShadow(this.textRenderer, heading, (int) ((this.width - this.textRenderer.getWidth(heading)) / 2f), 8, 0xffffff);
		search.render(ms, mouseX, mouseY, delta);
		String count = shown.size() + " / " + choices.size();
		ms.drawText(this.textRenderer, count, (int) (this.width / 2f + 156), 28, 0x888888, false);
		super.render(ms, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
