package dev.titanvr.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/** The giant hunters' loadouts. Each trades something away; consumables are topped up every so often. */
public enum HunterClass {
	ARCHER("Archer", ChatFormatting.GREEN, "Power IV bow with endless arrows and a speed boost. Leather armour: fragile up close."),
	AVIATOR("Aviator", ChatFormatting.AQUA, "Elytra and rockets, multishot crossbow. Fly circles round the giant, but no chestplate and little ammo."),
	SMASHER("Smasher", ChatFormatting.GOLD, "Mace and wind charges: launch yourself up and slam down on the giant. Iron armour, but slow."),
	BOMBER("Bomber", ChatFormatting.RED, "Splash potions of Harming II and TNT: huge damage, but only a few at a time."),
	SCOUT("Scout", ChatFormatting.LIGHT_PURPLE, "Very fast, jumps high, ender pearls and a sharp sword. No armour at all.");

	public final String title;
	public final ChatFormatting color;
	public final String description;

	HunterClass(String title, ChatFormatting color, String description) {
		this.title = title;
		this.color = color;
		this.description = description;
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static HunterClass byId(String id) {
		for (HunterClass c : values()) {
			if (c.id().equalsIgnoreCase(id)) {
				return c;
			}
		}
		return null;
	}

	public Component label() {
		return Component.literal(this.title).withStyle(this.color);
	}

	/** Consumables that resupply tops back up to, as (item, count). */
	private List<ItemStack> ammo(ServerPlayer p) {
		List<ItemStack> list = new ArrayList<>();
		switch (this) {
			case ARCHER -> list.add(new ItemStack(Items.ARROW, 1));
			case AVIATOR -> {
				list.add(new ItemStack(Items.FIREWORK_ROCKET, 16));
				list.add(new ItemStack(Items.ARROW, 24));
			}
			case SMASHER -> list.add(new ItemStack(Items.WIND_CHARGE, 6));
			case BOMBER -> {
				for (int i = 0; i < 6; i++) {
					list.add(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRONG_HARMING));
				}
				list.add(new ItemStack(Items.TNT, 4));
			}
			case SCOUT -> list.add(new ItemStack(Items.ENDER_PEARL, 4));
		}
		return list;
	}

	/** Clears the player and hands out this class's kit and effects. */
	public void equip(ServerPlayer p) {
		p.getInventory().clearContent();
		p.removeAllEffects();
		switch (this) {
			case ARCHER -> {
				give(p, tool(p, Items.BOW, Enchantments.POWER, 4, Enchantments.INFINITY, 1));
				armor(p, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
				effect(p, MobEffects.SPEED, 0);
			}
			case AVIATOR -> {
				give(p, tool(p, Items.CROSSBOW, Enchantments.MULTISHOT, 1, Enchantments.QUICK_CHARGE, 2));
				armor(p, Items.LEATHER_HELMET, Items.ELYTRA, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
				p.getItemBySlot(EquipmentSlot.CHEST).set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
			}
			case SMASHER -> {
				give(p, tool(p, Items.MACE, Enchantments.DENSITY, 3, Enchantments.WIND_BURST, 1));
				armor(p, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
				ItemStack boots = p.getItemBySlot(EquipmentSlot.FEET);
				boots.enchant(ench(p, Enchantments.FEATHER_FALLING), 4);
				effect(p, MobEffects.SLOWNESS, 0);
			}
			case BOMBER -> {
				give(p, unbreakable(new ItemStack(Items.FLINT_AND_STEEL)));
				armor(p, Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS);
			}
			case SCOUT -> {
				give(p, tool(p, Items.DIAMOND_SWORD, Enchantments.SHARPNESS, 3, null, 0));
				effect(p, MobEffects.SPEED, 1);
				effect(p, MobEffects.JUMP_BOOST, 1);
			}
		}
		for (ItemStack s : ammo(p)) {
			give(p, s);
		}
		give(p, new ItemStack(Items.BREAD, 16));
	}

	/** Tops consumables back up to their starting amounts. */
	public void resupply(ServerPlayer p) {
		List<ItemStack> wanted = ammo(p);
		// Merge duplicates (e.g. six single potions) into one target count per item
		List<ItemStack> targets = new ArrayList<>();
		for (ItemStack s : wanted) {
			ItemStack t = targets.stream().filter(o -> ItemStack.isSameItemSameComponents(o, s)).findFirst().orElse(null);
			if (t == null) {
				targets.add(s.copy());
			} else {
				t.grow(s.getCount());
			}
		}
		for (ItemStack t : targets) {
			int have = 0;
			for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
				ItemStack s = p.getInventory().getItem(i);
				if (ItemStack.isSameItemSameComponents(s, t)) {
					have += s.getCount();
				}
			}
			int missing = t.getCount() - have;
			int per = t.getMaxStackSize();
			while (missing > 0) {
				int n = Math.min(missing, per);
				give(p, t.copyWithCount(n));
				missing -= n;
			}
		}
	}

	private static void give(ServerPlayer p, ItemStack s) {
		if (!p.getInventory().add(s) && !s.isEmpty()) {
			p.drop(s, false);
		}
	}

	private static Holder<Enchantment> ench(ServerPlayer p, ResourceKey<Enchantment> key) {
		return p.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
	}

	private static ItemStack tool(ServerPlayer p, Item item, ResourceKey<Enchantment> a, int la, ResourceKey<Enchantment> b, int lb) {
		ItemStack s = unbreakable(new ItemStack(item));
		s.enchant(ench(p, a), la);
		if (b != null) {
			s.enchant(ench(p, b), lb);
		}
		return s;
	}

	private static ItemStack unbreakable(ItemStack s) {
		s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		return s;
	}

	private static void armor(ServerPlayer p, Item head, Item chest, Item legs, Item feet) {
		p.setItemSlot(EquipmentSlot.HEAD, unbreakable(new ItemStack(head)));
		p.setItemSlot(EquipmentSlot.CHEST, unbreakable(new ItemStack(chest)));
		p.setItemSlot(EquipmentSlot.LEGS, unbreakable(new ItemStack(legs)));
		p.setItemSlot(EquipmentSlot.FEET, unbreakable(new ItemStack(feet)));
	}

	private static void effect(ServerPlayer p, Holder<MobEffect> effect, int amplifier) {
		p.addEffect(new MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION, amplifier, false, false, true));
	}
}
