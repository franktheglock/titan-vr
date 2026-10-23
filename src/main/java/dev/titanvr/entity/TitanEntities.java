package dev.titanvr.entity;

import dev.titanvr.TitanMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class TitanEntities {
	public static final ResourceKey<EntityType<?>> PHYSICS_BODY_KEY = ResourceKey.create(Registries.ENTITY_TYPE, TitanMod.id("physics_body"));

	// Positions are streamed by Titan's own packets, so vanilla movement sync is effectively disabled
	// (huge update interval) to avoid fighting it.
	public static final EntityType<PhysicsBodyEntity> PHYSICS_BODY = EntityType.Builder.<PhysicsBodyEntity>of(PhysicsBodyEntity::new, MobCategory.MISC)
		.sized(1.0F, 1.0F)
		.clientTrackingRange(12)
		.updateInterval(Integer.MAX_VALUE / 2)
		.noSave()
		.noSummon()
		.build(PHYSICS_BODY_KEY);

	private TitanEntities() {
	}

	public static void register() {
		Registry.register(BuiltInRegistries.ENTITY_TYPE, PHYSICS_BODY_KEY, PHYSICS_BODY);
	}
}
