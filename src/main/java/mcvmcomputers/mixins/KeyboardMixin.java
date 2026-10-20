package mcvmcomputers.mixins;

import static mcvmcomputers.client.ClientMod.*;

import org.apache.commons.lang3.SystemUtils;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mcvmcomputers.client.ClientMod;
import mcvmcomputers.client.gui.GuiFocus;
import mcvmcomputers.client.gui.setup.pages.SetupPageUnfocusBinding;
import mcvmcomputers.emu.KeyMap;
import mcvmcomputers.emu.VmSession;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;

@Mixin(Keyboard.class)
public class KeyboardMixin {
	@Inject(at = @At("HEAD"), method = "onKey")
	public void onKey(long window, int key, int scancode, int action, int mods, CallbackInfo ci) {
		MinecraftClient mcc = MinecraftClient.getInstance();
		if (window != mcc.getWindow().getHandle()) {
			return;
		}
		VmSession vm = ClientMod.vm;
		if (vm != null && vm.state() == VmSession.State.RUNNING && mcc.currentScreen instanceof GuiFocus) {
			// Key repeat is left to the emulated keyboard.
			if (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_RELEASE) {
				vm.key(KeyMap.scancode(key, scancode, SystemUtils.IS_OS_WINDOWS), action == GLFW.GLFW_PRESS);
			}
		} else if (SetupPageUnfocusBinding.changeBinding) {
			if (action == GLFW.GLFW_PRESS && getKeyName(key) != null) {
				switch (SetupPageUnfocusBinding.bindingToBeChangedNum) {
				case 1:
					glfwUnfocusKey1 = key;
					break;
				case 2:
					glfwUnfocusKey2 = key;
					break;
				case 3:
					glfwUnfocusKey3 = key;
					break;
				case 4:
					glfwUnfocusKey4 = key;
					break;
				default:
					break;
				}
				SetupPageUnfocusBinding.bindingJustChanged = true;
				SetupPageUnfocusBinding.changeBinding = false;
			}
		}
	}
}
