package mcvmcomputers.sound;

import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registry;
import net.minecraft.registry.Registries;

public class SoundList {
	public static SoundEvent RADAR_SOUND = SoundEvent.of(new Identifier("mcvmcomputers", "radar"));
	public static SoundEvent ROCKET_SOUND = SoundEvent.of(new Identifier("mcvmcomputers", "rocket"));
	public static SoundEvent SHOPINTRO_SOUND = SoundEvent.of(new Identifier("mcvmcomputers", "shopintro"));
	public static SoundEvent SHOPOUTRO_SOUND = SoundEvent.of(new Identifier("mcvmcomputers", "shopoutro"));
	public static SoundEvent SHOPMUSIC_SOUND = SoundEvent.of(new Identifier("mcvmcomputers", "shopmusic"));
	
	public static void init() {
		Registry.register(Registries.SOUND_EVENT, new Identifier("mcvmcomputers", "radar"), RADAR_SOUND);
		Registry.register(Registries.SOUND_EVENT, new Identifier("mcvmcomputers", "rocket"), ROCKET_SOUND);
		Registry.register(Registries.SOUND_EVENT, new Identifier("mcvmcomputers", "shopintro"), SHOPINTRO_SOUND);
		Registry.register(Registries.SOUND_EVENT, new Identifier("mcvmcomputers", "shopoutro"), SHOPOUTRO_SOUND);
		Registry.register(Registries.SOUND_EVENT, new Identifier("mcvmcomputers", "shopmusic"), SHOPMUSIC_SOUND);
	}
}
