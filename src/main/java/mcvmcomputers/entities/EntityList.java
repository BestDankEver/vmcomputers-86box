package mcvmcomputers.entities;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registry;
import net.minecraft.registry.Registries;

public class EntityList {
	public static EntityType<EntityItemPreview> ITEM_PREVIEW;
	public static EntityType<EntityKeyboard> KEYBOARD;
	public static EntityType<EntityMouse> MOUSE;
	public static EntityType<EntityCRTScreen> CRT_SCREEN;
	public static EntityType<EntityFlatScreen> FLATSCREEN;
	public static EntityType<EntityWallTV> WALLTV;
	public static EntityType<EntityPC> PC;
	public static EntityType<EntityDeliveryChest> DELIVERY_CHEST;

	public static void init() {
		ITEM_PREVIEW = Registry.register(Registries.ENTITY_TYPE,
						new Identifier("mcvmcomputers", "item_preview"),
						FabricEntityTypeBuilder.<EntityItemPreview>create(SpawnGroup.MISC, EntityItemPreview::new)
						.dimensions(new EntityDimensions(1,1, true)).trackable(60, 2,true).build());
		KEYBOARD = Registry.register(Registries.ENTITY_TYPE, 
					new Identifier("mcvmcomputers", "keyboard"),
					FabricEntityTypeBuilder.<EntityKeyboard>create(SpawnGroup.MISC, EntityKeyboard::new)
					.dimensions(new EntityDimensions(0.5f, 0.0625f, true)).trackable(60, 2,true).build());
		MOUSE = Registry.register(Registries.ENTITY_TYPE, 
				new Identifier("mcvmcomputers", "mouse"),
				FabricEntityTypeBuilder.<EntityMouse>create(SpawnGroup.MISC, EntityMouse::new)
				.dimensions(new EntityDimensions(0.25f, 0.0625f, true)).trackable(60, 2,true).build());
		CRT_SCREEN = Registry.register(Registries.ENTITY_TYPE, 
						new Identifier("mcvmcomputers", "crt_screen"),
						FabricEntityTypeBuilder.<EntityCRTScreen>create(SpawnGroup.MISC, EntityCRTScreen::new)
						.dimensions(new EntityDimensions(0.8f, 0.8f, true)).trackable(60, 2,true).build());
		FLATSCREEN = Registry.register(Registries.ENTITY_TYPE, 
						new Identifier("mcvmcomputers", "flat_screen"),
						FabricEntityTypeBuilder.<EntityFlatScreen>create(SpawnGroup.MISC, EntityFlatScreen::new)
						.dimensions(new EntityDimensions(0.8f, 0.8f, true)).trackable(60, 2,true).build());
		WALLTV = Registry.register(Registries.ENTITY_TYPE, 
								new Identifier("mcvmcomputers", "walltv"),
								FabricEntityTypeBuilder.<EntityWallTV>create(SpawnGroup.MISC, EntityWallTV::new)
								.dimensions(new EntityDimensions(1f, 1.2f, true)).trackable(60, 2,true).build());
		PC = Registry.register(Registries.ENTITY_TYPE, 
				new Identifier("mcvmcomputers", "pc"),
				FabricEntityTypeBuilder.<EntityPC>create(SpawnGroup.MISC, EntityPC::new)
				.dimensions(new EntityDimensions(0.375f, 0.6875f, true)).trackable(60, 2,true).build());
		DELIVERY_CHEST = Registry.register(Registries.ENTITY_TYPE, 
							new Identifier("mcvmcomputers", "delivery_chest"),
							FabricEntityTypeBuilder.<EntityDeliveryChest>create(SpawnGroup.MISC, EntityDeliveryChest::new)
							.dimensions(new EntityDimensions(1f, 2f, true)).trackable(600, 40,true).build());
	}
}
