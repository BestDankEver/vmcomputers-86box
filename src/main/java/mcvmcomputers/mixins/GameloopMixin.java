package mcvmcomputers.mixins;

import static mcvmcomputers.client.ClientMod.*;

import java.io.File;
import java.util.ConcurrentModificationException;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mcvmcomputers.client.ClientMod;
import mcvmcomputers.client.gui.setup.GuiSetup;
import mcvmcomputers.client.tablet.TabletOS;
import mcvmcomputers.entities.EntityItemPreview;
import mcvmcomputers.item.ItemList;
import mcvmcomputers.item.ItemOrderingTablet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

@Mixin(MinecraftClient.class)
public class GameloopMixin {
	@Shadow
	public ClientPlayerEntity player;

	@Shadow
	public HitResult crosshairTarget;

	@Shadow
	public ClientWorld world;

	@Inject(at = @At("HEAD"), method = "run")
	private void run(CallbackInfo info) {
		MinecraftClient mcc = MinecraftClient.getInstance();
		// Defaults until setup has run.
		vhdDirectory = new File(mcc.runDirectory, "vm_computers/vhds");
		vhdDirectory.mkdirs();
		isoDirectory = new File(mcc.runDirectory, "vm_computers/isos");
		isoDirectory.mkdirs();
		mcc.setScreen(new GuiSetup());
	}

	@Inject(at = @At("HEAD"), method = "render")
	private void render(CallbackInfo info) {
		if (lastDeltaTimeTime == 0) {
			lastDeltaTimeTime = System.currentTimeMillis();
		} else {
			long now = System.currentTimeMillis();
			deltaTime = (now - lastDeltaTimeTime) / 1000f;
			lastDeltaTimeTime = now;
		}

		ClientMod.renderTick();

		if (tabletOS != null) {
			tabletOS.generateTexture();
		} else {
			try {
				tabletOS = new TabletOS();
				tabletThread = new Thread(() -> {
					while (!Thread.currentThread().isInterrupted()) {
						try {
							if (tabletOS.tabletOn) {
								tabletOS.render();
							}
							Thread.sleep(tabletOS.tabletOn ? 16 : 100); // the original spun a core flat out
						} catch (ConcurrentModificationException e) {
							// redraw next time
						} catch (InterruptedException e) {
							return;
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}, "Tablet Renderer");
				tabletThread.setDaemon(true);
				tabletThread.start();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if (player == null || tabletOS == null) {
			return;
		}
		boolean tabletOut = false;
		for (ItemStack is : player.getHandItems()) {
			if (is.getItem() instanceof ItemOrderingTablet) {
				tabletOut = true;
				break;
			}
		}
		if (tabletOut != tabletOS.tabletOn) {
			tabletOS.tabletOn = tabletOut;
			if (tabletOut) {
				tabletOS.tabletTakenOut();
			} else {
				tabletOS.tabletUnequipped();
			}
		}

		// Placement preview for placeable items in the main hand.
		ItemStack main = player.getMainHandStack();
		if (!main.isEmpty() && ItemList.PLACABLE_ITEMS.contains(main.getItem()) && crosshairTarget != null) {
			Vec3d hit = crosshairTarget.getPos();
			if (thePreviewEntity != null) {
				thePreviewEntity.setItem(main);
				thePreviewEntity.setPosition(hit.x, hit.y, hit.z);
			} else {
				thePreviewEntity = new EntityItemPreview(world, hit.x, hit.y, hit.z, main);
				this.world.addEntity(Integer.MAX_VALUE - 20, thePreviewEntity);
			}
		} else if (thePreviewEntity != null) {
			thePreviewEntity.kill();
			thePreviewEntity = null;
		}
	}

	@Inject(at = @At("HEAD"), method = "cleanUpAfterCrash")
	private void cleanUpAfterCrash(CallbackInfo info) {
		ClientMod.shutdown();
	}

	@Inject(at = @At("HEAD"), method = "close")
	private void close(CallbackInfo info) {
		System.out.println("Stopping VM Computers...");
		if (tabletThread != null) {
			tabletThread.interrupt();
		}
		ClientMod.shutdown();
		ClientMod.removeAllScreens();
		System.out.println("Stopped VM Computers.");
	}
}
