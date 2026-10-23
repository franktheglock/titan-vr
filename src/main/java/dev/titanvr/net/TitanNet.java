package dev.titanvr.net;

import dev.titanvr.TitanMod;
import dev.titanvr.physics.Rig;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** All Titan VR packets. Handlers live in TitanMod (server) and TitanClient (client). */
public final class TitanNet {
	private TitanNet() {
	}

	// ------------------------------------------------------------------ C2S

	/**
	 * A giant pressed or released grab on a hand (0 = main, 1 = off). On release, motion = the hand's measured
	 * linear velocity (xyz, blocks/s) and angular velocity (xyz, rad/s) when hasMotion is set.
	 */
	public record GrabC2S(byte hand, boolean pressed, boolean hasMotion, float[] motion) implements CustomPacketPayload {
		public static final Type<GrabC2S> TYPE = new Type<>(TitanMod.id("grab"));
		public static final StreamCodec<RegistryFriendlyByteBuf, GrabC2S> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeByte(p.hand);
				buf.writeBoolean(p.pressed);
				buf.writeBoolean(p.hasMotion);
				if (p.hasMotion) {
					for (int i = 0; i < 6; i++) {
						buf.writeFloat(p.motion[i]);
					}
				}
			},
			buf -> {
				byte hand = buf.readByte();
				boolean pressed = buf.readBoolean();
				boolean hasMotion = buf.readBoolean();
				float[] motion = new float[6];
				if (hasMotion) {
					for (int i = 0; i < 6; i++) {
						motion[i] = buf.readFloat();
					}
				}
				return new GrabC2S(hand, pressed, hasMotion, motion);
			}
		);

		public static GrabC2S press(int hand) {
			return new GrabC2S((byte) hand, true, false, new float[6]);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** A giant stretched/squeezed the world with both hands and wants a new size. */
	public record ResizeC2S(float scale) implements CustomPacketPayload {
		public static final Type<ResizeC2S> TYPE = new Type<>(TitanMod.id("resize"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ResizeC2S> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeFloat(p.scale),
			buf -> new ResizeC2S(buf.readFloat())
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// ------------------------------------------------------------------ S2C

	/** Tells clients whether a player is a giant. */
	public record GiantStateS2C(int entityId, boolean giant, float scale) implements CustomPacketPayload {
		public static final Type<GiantStateS2C> TYPE = new Type<>(TitanMod.id("giant_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, GiantStateS2C> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.entityId);
				buf.writeBoolean(p.giant);
				buf.writeFloat(p.scale);
			},
			buf -> new GiantStateS2C(buf.readVarInt(), buf.readBoolean(), buf.readFloat())
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Shape of a physics body: a list of boxes in the body's local (center-of-mass) frame.
	 * boxes = [cx, cy, cz, hx, hy, hz] per box, states = block state ids (-1 = none).
	 */
	public record BodyShapeS2C(int entityId, float[] boxes, int[] states) implements CustomPacketPayload {
		public static final Type<BodyShapeS2C> TYPE = new Type<>(TitanMod.id("body_shape"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BodyShapeS2C> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.entityId);
				buf.writeVarInt(p.states.length);
				for (int i = 0; i < p.states.length; i++) {
					for (int k = 0; k < 6; k++) {
						buf.writeFloat(p.boxes[i * 6 + k]);
					}
					buf.writeVarInt(p.states[i] + 1);
				}
			},
			buf -> {
				int id = buf.readVarInt();
				int n = buf.readVarInt();
				float[] boxes = new float[n * 6];
				int[] states = new int[n];
				for (int i = 0; i < n; i++) {
					for (int k = 0; k < 6; k++) {
						boxes[i * 6 + k] = buf.readFloat();
					}
					states[i] = buf.readVarInt() - 1;
				}
				return new BodyShapeS2C(id, boxes, states);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Batched transforms of physics bodies. pos = xyz per body, rot = xyzw quaternion per body. */
	public record BodyStatesS2C(int[] ids, double[] pos, float[] rot) implements CustomPacketPayload {
		public static final Type<BodyStatesS2C> TYPE = new Type<>(TitanMod.id("body_states"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BodyStatesS2C> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.ids.length);
				for (int i = 0; i < p.ids.length; i++) {
					buf.writeVarInt(p.ids[i]);
					buf.writeDouble(p.pos[i * 3]);
					buf.writeDouble(p.pos[i * 3 + 1]);
					buf.writeDouble(p.pos[i * 3 + 2]);
					for (int k = 0; k < 4; k++) {
						buf.writeFloat(p.rot[i * 4 + k]);
					}
				}
			},
			buf -> {
				int n = buf.readVarInt();
				int[] ids = new int[n];
				double[] pos = new double[n * 3];
				float[] rot = new float[n * 4];
				for (int i = 0; i < n; i++) {
					ids[i] = buf.readVarInt();
					pos[i * 3] = buf.readDouble();
					pos[i * 3 + 1] = buf.readDouble();
					pos[i * 3 + 2] = buf.readDouble();
					for (int k = 0; k < 4; k++) {
						rot[i * 4 + k] = buf.readFloat();
					}
				}
				return new BodyStatesS2C(ids, pos, rot);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static final byte RAGDOLL_END = 0;
	public static final byte RAGDOLL_HUMANOID = 1;
	public static final byte RAGDOLL_RIGID = 2;

	/**
	 * Ragdoll poses. For each entry: kind, rig id, entity id, a float "param" and a run of doubles.
	 * HUMANOID (any particle rig): param = skeleton scale, values = rig.n particles * xyz (world space).
	 * RIGID: param = half height of the box, values = center xyz + quaternion xyzw.
	 * END: no values (entity stands back up).
	 */
	public record RagdollStatesS2C(byte[] kinds, byte[] rigs, int[] ids, float[] params, double[] values) implements CustomPacketPayload {
		public static final Type<RagdollStatesS2C> TYPE = new Type<>(TitanMod.id("ragdoll_states"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RagdollStatesS2C> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.ids.length);
				int v = 0;
				for (int i = 0; i < p.ids.length; i++) {
					buf.writeByte(p.kinds[i]);
					buf.writeByte(p.rigs[i]);
					buf.writeVarInt(p.ids[i]);
					buf.writeFloat(p.params[i]);
					int count = valueCount(p.kinds[i], p.rigs[i]);
					for (int k = 0; k < count; k++) {
						buf.writeDouble(p.values[v++]);
					}
				}
			},
			buf -> {
				int n = buf.readVarInt();
				byte[] kinds = new byte[n];
				byte[] rigs = new byte[n];
				int[] ids = new int[n];
				float[] params = new float[n];
				it.unimi.dsi.fastutil.doubles.DoubleArrayList values = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
				for (int i = 0; i < n; i++) {
					kinds[i] = buf.readByte();
					rigs[i] = buf.readByte();
					ids[i] = buf.readVarInt();
					params[i] = buf.readFloat();
					int count = valueCount(kinds[i], rigs[i]);
					for (int k = 0; k < count; k++) {
						values.add(buf.readDouble());
					}
				}
				return new RagdollStatesS2C(kinds, rigs, ids, params, values.toDoubleArray());
			}
		);

		public static int valueCount(byte kind, byte rig) {
			return switch (kind) {
				case RAGDOLL_HUMANOID -> {
					Rig r = Rig.byId(rig);
					yield r == null ? 0 : r.n * 3;
				}
				case RAGDOLL_RIGID -> 7;
				default -> 0;
			};
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static final byte HOLD_BODY = 0;
	public static final byte HOLD_RAGDOLL = 1;
	public static final byte HOLD_RIGID_RAGDOLL = 2;
	public static final byte HOLD_ENTITY = 3;

	/**
	 * Sent to the holding giant so their client can render the held thing glued to the real-time hand pose.
	 * local = offset xyz + rotation xyzw of the held thing in the hand's frame.
	 */
	public record HoldS2C(int entityId, byte hand, boolean holding, byte kind, int particle, float[] local)
		implements CustomPacketPayload {
		public static final Type<HoldS2C> TYPE = new Type<>(TitanMod.id("hold"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HoldS2C> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.entityId);
				buf.writeByte(p.hand);
				buf.writeBoolean(p.holding);
				buf.writeByte(p.kind);
				buf.writeVarInt(p.particle);
				for (int k = 0; k < 7; k++) {
					buf.writeFloat(p.local[k]);
				}
			},
			buf -> {
				int id = buf.readVarInt();
				byte hand = buf.readByte();
				boolean holding = buf.readBoolean();
				byte kind = buf.readByte();
				int particle = buf.readVarInt();
				float[] local = new float[7];
				for (int k = 0; k < 7; k++) {
					local[k] = buf.readFloat();
				}
				return new HoldS2C(id, hand, holding, kind, particle, local);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.serverboundPlay().register(GrabC2S.TYPE, GrabC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ResizeC2S.TYPE, ResizeC2S.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GiantStateS2C.TYPE, GiantStateS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BodyShapeS2C.TYPE, BodyShapeS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BodyStatesS2C.TYPE, BodyStatesS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RagdollStatesS2C.TYPE, RagdollStatesS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HoldS2C.TYPE, HoldS2C.CODEC);
	}
}
