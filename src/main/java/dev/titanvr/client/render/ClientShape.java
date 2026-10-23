package dev.titanvr.client.render;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.world.level.block.state.BlockState;

/** Client copy of a body's voxels with their block models resolved once and reused every frame. */
public final class ClientShape {
	public final float[] boxes;
	public final BlockState[] states;
	public BlockModelRenderState[] models;

	public ClientShape(float[] boxes, BlockState[] states) {
		this.boxes = boxes;
		this.states = states;
	}

	public int count() {
		return this.states.length;
	}

	public float radius() {
		float r = 0.5F;
		for (int i = 0; i < this.states.length; i++) {
			float x = this.boxes[i * 6], y = this.boxes[i * 6 + 1], z = this.boxes[i * 6 + 2];
			r = Math.max(r, (float) Math.sqrt(x * x + y * y + z * z) + 0.87F);
		}
		return r;
	}
}
