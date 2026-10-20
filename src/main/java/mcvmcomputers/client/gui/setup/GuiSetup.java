package mcvmcomputers.client.gui.setup;

import java.util.ArrayList;
import java.util.List;

import mcvmcomputers.client.ClientMod;
import mcvmcomputers.client.gui.setup.pages.SetupPage;
import mcvmcomputers.client.gui.setup.pages.SetupPageFinish;
import mcvmcomputers.client.gui.setup.pages.SetupPageIntroMessage;
import mcvmcomputers.client.gui.setup.pages.SetupPageUnfocusBinding;
import mcvmcomputers.client.gui.setup.pages.SetupPageVMComputersDirectory;
import mcvmcomputers.client.utils.VMSettings;
import mcvmcomputers.utils.MVCUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Language;

/** First screen after launch: folder, unfocus keys, then on to the title screen. */
public class GuiSetup extends Screen {
	private List<SetupPage> setupPages;
	private int setupIndex;
	private SetupPage currentSetupPage;
	private boolean initialized = false;
	public boolean loadedConfiguration = false;
	/** True when "use saved configuration" was chosen: the last page finishes by itself. */
	public boolean startVb = false;
	public VMSettings settings;
	private Language language = Language.getInstance();
	private final MinecraftClient minecraft = MinecraftClient.getInstance();

	public GuiSetup() {
		super(new LiteralText("Setup"));
	}

	public void addElement(Element e) {
		this.children.add(e);
	}

	public void clearElements() {
		this.children.clear();
	}

	public void clearButtons() {
		this.buttons.clear();
	}

	public void addButton(ButtonWidget bw) {
		super.addButton(bw);
	}

	public void nextPage() {
		if (setupIndex < setupPages.size() - 1) {
			setupIndex++;
			currentSetupPage = setupPages.get(setupIndex);
			this.init();
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	public String translation(String in) {
		return language.get(in).replace("%c", "" + MVCUtils.COLOR_CHAR);
	}

	public void lastPage() {
		startVb = true;
		setupIndex = setupPages.size() - 1;
		currentSetupPage = setupPages.get(setupIndex);
		this.init();
	}

	public void firstPage() {
		this.clearButtons();
		this.clearElements();
		setupIndex = 0;
		currentSetupPage = setupPages.get(0);
		this.init();
	}

	@Override
	public void init() {
		language = Language.getInstance();
		if (settings == null) {
			VMSettings saved = VMSettings.load();
			loadedConfiguration = saved != null;
			settings = saved != null ? saved : new VMSettings();
			ClientMod.glfwUnfocusKey1 = settings.unfocusKey1;
			ClientMod.glfwUnfocusKey2 = settings.unfocusKey2;
			ClientMod.glfwUnfocusKey3 = settings.unfocusKey3;
			ClientMod.glfwUnfocusKey4 = settings.unfocusKey4;
			ClientMod.vhdDirectory = new java.io.File(settings.vmComputersDirectory, "vhds");
			ClientMod.isoDirectory = new java.io.File(settings.vmComputersDirectory, "isos");
		}
		if (!initialized) {
			setupPages = new ArrayList<>();
			setupPages.add(new SetupPageIntroMessage(this, this.textRenderer));
			setupPages.add(new SetupPageVMComputersDirectory(this, this.textRenderer));
			setupPages.add(new SetupPageUnfocusBinding(this, this.textRenderer));
			setupPages.add(new SetupPageFinish(this, this.textRenderer));
			currentSetupPage = setupPages.get(0);
			initialized = true;
		}
		this.clearButtons();
		this.clearElements();
		currentSetupPage.init();
	}

	@Override
	public void render(MatrixStack ms, int mouseX, int mouseY, float delta) {
		this.renderBackground(ms);
		String title = translation("mcvmcomputers.setup.title");
		this.textRenderer.draw(ms, title, this.width / 2 - this.textRenderer.getWidth(title) / 2, 20, -1);
		String s = translation("mcvmcomputers.setup.page").replaceFirst("%s", "" + (setupIndex + 1)).replaceFirst("%s", "" + setupPages.size());
		this.textRenderer.draw(ms, s, this.width / 2 - this.textRenderer.getWidth(s) / 2, 30, -1);
		currentSetupPage.render(ms, mouseX, mouseY, delta);
		super.render(ms, mouseX, mouseY, delta);
	}
}
