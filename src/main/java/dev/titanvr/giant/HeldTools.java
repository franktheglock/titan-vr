package dev.titanvr.giant;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Giant-sized hitboxes for whatever a giant holds: a chain of spheres along the tool's shaft from the fist to a
 * striking head, swinging with the controller. Lengths roughly follow Vivecraft's own tool reach.
 */
public final class HeldTools {
	private HeldTools() {
	}

	/** Shape of a held item in player-sized units: shaft length, shaft and head radius, mass multiplier. */
	private record Profile(double length, double shaftRadius, double headRadius, double mass) {
	}

	private static Profile profile(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		if (stack.is(ItemTags.SPEARS)) {
			return new Profile(1.5, 0.05, 0.08, 1.2);
		}
		if (stack.is(Items.TRIDENT)) {
			return new Profile(1.15, 0.05, 0.12, 1.4);
		}
		if (stack.is(Items.MACE)) {
			return new Profile(0.6, 0.05, 0.2, 2.5);
		}
		if (stack.is(ItemTags.SWORDS)) {
			return new Profile(0.9, 0.08, 0.1, 1.0);
		}
		if (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)) {
			return new Profile(0.7, 0.05, 0.16, 1.5);
		}
		if (stack.has(DataComponents.TOOL) || stack.has(DataComponents.WEAPON) || stack.is(Items.STICK) || stack.is(Items.BLAZE_ROD)
			|| stack.is(Items.BREEZE_ROD) || stack.is(Items.FISHING_ROD)) {
			return new Profile(0.6, 0.05, 0.08, 1.0);
		}
		return null;
	}

	/** Rebuilds the hand's collider chain for the item it holds. */
	public static void configure(HandState hand, ItemStack stack, double scale, double fistRadius, boolean vr) {
		hand.forwardZ = vr ? -1 : 1;
		hand.colliderDist[0] = 0;
		hand.colliderRadius[0] = fistRadius;
		Profile p = profile(stack);
		if (p == null) {
			hand.colliderCount = 1;
			hand.tool = ItemStack.EMPTY;
			hand.toolMass = 1;
			hand.toolHead = 0;
			return;
		}
		hand.tool = stack;
		hand.toolMass = p.mass;
		double length = p.length * scale;
		double shaft = Math.max(0.2, p.shaftRadius * scale);
		double head = Math.max(0.3, p.headRadius * scale);
		// Spheres overlap by about half so nothing slips between them.
		int n = Math.min(HandState.MAX_COLLIDERS - 1, Math.max(1, (int) Math.ceil((length - fistRadius) / shaft)));
		for (int i = 1; i <= n; i++) {
			double f = (double) i / n;
			hand.colliderDist[i] = fistRadius * (1 - f) + length * f;
			hand.colliderRadius[i] = i == n ? head : shaft;
		}
		hand.colliderCount = n + 1;
		hand.toolHead = n;
	}
}
