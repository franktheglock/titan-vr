package dev.titanvr.client;

import dev.titanvr.RagdollHooks;
import dev.titanvr.client.render.ClientShape;
import dev.titanvr.client.render.PhysicsBodyRenderer;
import dev.titanvr.entity.PhysicsBodyEntity;
import dev.titanvr.entity.TitanEntities;
import dev.titanvr.net.TitanNet;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class TitanClient implements ClientModInitializer {
	/** Shapes that arrived before their entity did. */
	private static final Int2ObjectOpenHashMap<ClientShape> PENDING_SHAPES = new Int2ObjectOpenHashMap<>();
	private static final Int2ObjectOpenHashMap<double[]> PENDING_STATES = new Int2ObjectOpenHashMap<>();

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(TitanEntities.PHYSICS_BODY, PhysicsBodyRenderer::new);
		RagdollHooks.clientCheck = ClientRagdolls::isRagdolled;
		RagdollHooks.clientGiant = GiantClient::isGiantId;
		GiantClient.registerKeys();

		ClientPlayNetworking.registerGlobalReceiver(TitanNet.GiantStateS2C.TYPE, (p, ctx) -> GiantClient.handleGiantState(p));
		ClientPlayNetworking.registerGlobalReceiver(TitanNet.HoldS2C.TYPE, (p, ctx) -> GiantClient.handleHold(p));
		ClientPlayNetworking.registerGlobalReceiver(TitanNet.RagdollStatesS2C.TYPE, (p, ctx) -> ClientRagdolls.handle(p));
		ClientPlayNetworking.registerGlobalReceiver(TitanNet.BodyShapeS2C.TYPE, (p, ctx) -> handleShape(p));
		ClientPlayNetworking.registerGlobalReceiver(TitanNet.BodyStatesS2C.TYPE, (p, ctx) -> handleStates(p));

		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			GiantProjectileRender.onLoad(entity);
			if (entity instanceof PhysicsBodyEntity pbe) {
				ClientShape shape = PENDING_SHAPES.remove(entity.getId());
				if (shape != null) {
					applyShape(pbe, shape);
				}
				double[] s = PENDING_STATES.remove(entity.getId());
				if (s != null) {
					applyState(pbe, s, true);
				}
			}
		});
		ClientTickEvents.START_CLIENT_TICK.register(GiantClient::startTick);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			ClientRagdolls.tick();
			GiantClient.endTick(mc);
			ClientAutoTest.tick(mc);
			if (mc.level == null) {
				PENDING_SHAPES.clear();
				PENDING_STATES.clear();
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			GiantClient.clear();
			ClientRagdolls.clear();
			PENDING_SHAPES.clear();
			PENDING_STATES.clear();
		});
	}

	private static void handleShape(TitanNet.BodyShapeS2C p) {
		BlockState[] states = new BlockState[p.states().length];
		for (int i = 0; i < states.length; i++) {
			states[i] = p.states()[i] < 0 ? null : Block.stateById(p.states()[i]);
		}
		ClientShape shape = new ClientShape(p.boxes(), states);
		Minecraft mc = Minecraft.getInstance();
		Entity e = mc.level != null ? mc.level.getEntity(p.entityId()) : null;
		if (e instanceof PhysicsBodyEntity pbe) {
			applyShape(pbe, shape);
		} else {
			PENDING_SHAPES.put(p.entityId(), shape);
		}
	}

	private static void applyShape(PhysicsBodyEntity entity, ClientShape shape) {
		entity.clientShape = shape;
		entity.radius = shape.radius();
		entity.setPos(entity.getX(), entity.getY(), entity.getZ());
	}

	private static void handleStates(TitanNet.BodyStatesS2C p) {
		Minecraft mc = Minecraft.getInstance();
		for (int i = 0; i < p.ids().length; i++) {
			double[] s = {p.pos()[i * 3], p.pos()[i * 3 + 1], p.pos()[i * 3 + 2], p.rot()[i * 4], p.rot()[i * 4 + 1], p.rot()[i * 4 + 2], p.rot()[i * 4 + 3]};
			Entity e = mc.level != null ? mc.level.getEntity(p.ids()[i]) : null;
			if (e instanceof PhysicsBodyEntity pbe) {
				applyState(pbe, s, false);
			} else {
				PENDING_STATES.put(p.ids()[i], s);
			}
		}
	}

	private static void applyState(PhysicsBodyEntity e, double[] s, boolean snap) {
		e.tx = s[0];
		e.ty = s[1];
		e.tz = s[2];
		e.rotTarget.set((float) s[3], (float) s[4], (float) s[5], (float) s[6]);
		if (!e.hasTarget || snap) {
			// First state: snap instead of sliding in from the spawn position.
			e.setPos(s[0], s[1], s[2]);
			e.setOldPosAndRot();
			e.rot.set(e.rotTarget);
			e.rotO.set(e.rotTarget);
		}
		e.hasTarget = true;
	}
}
