package com.evidence.stringduplicator;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;

public class CraftingListener implements Listener {

    private final JavaPlugin plugin;
    private final ItemStack machineResult;

    public CraftingListener(JavaPlugin plugin, ItemStack machineResult) {
        this.plugin = plugin;
        this.machineResult = machineResult;
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        scheduleCheck(event.getItemDrop());
    }

    @EventHandler
    public void onMerge(ItemMergeEvent event) {
        scheduleCheck(event.getEntity());
    }

    private void scheduleCheck(Item item) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (item.isValid() && !item.isDead()) {
                    checkRecipe(item);
                }
            }
        }.runTaskLater(plugin, 20L);
    }

    private void checkRecipe(Item triggerItem) {
        Material type = triggerItem.getItemStack().getType();
        boolean isStone = (type == Material.STONE);
        boolean isDoor = Tag.DOORS.isTagged(type);

        if (!isStone && !isDoor) return;

        Collection<Entity> nearbyEntities = triggerItem.getWorld().getNearbyEntities(triggerItem.getLocation(), 1, 1, 1);

        Item stoneItem = null;
        Item doorItem = null;

        if (triggerItem.getItemStack().getType() == Material.STONE) stoneItem = triggerItem;
        if (Tag.DOORS.isTagged(triggerItem.getItemStack().getType())) doorItem = triggerItem;

        for (Entity entity : nearbyEntities) {
            if (!(entity instanceof Item)) continue;
            Item it = (Item) entity;
            ItemStack stack = it.getItemStack();

            if (stack.getType() == Material.STONE && stack.getAmount() >= 15) {
                stoneItem = it;
            } else if (Tag.DOORS.isTagged(stack.getType()) && stack.getAmount() >= 2) {
                doorItem = it;
            }
        }

        if (stoneItem != null && doorItem != null) {
            performCrafting(stoneItem, doorItem);
        }
    }

    private void performCrafting(Item stoneEntity, Item doorEntity) {
        Location loc = stoneEntity.getLocation().clone().add(0, 0.5, 0);

        ItemStack stoneStack = stoneEntity.getItemStack();
        if (stoneStack.getAmount() == 15) {
            stoneEntity.remove();
        } else {
            stoneStack.setAmount(stoneStack.getAmount() - 15);
            stoneEntity.setItemStack(stoneStack);
        }

        ItemStack doorStack = doorEntity.getItemStack();
        if (doorStack.getAmount() == 2) {
            doorEntity.remove();
        } else {
            doorStack.setAmount(doorStack.getAmount() - 2);
            doorEntity.setItemStack(doorStack);
        }

        loc.getWorld().dropItem(loc, machineResult.clone());
        loc.getWorld().spawnParticle(Particle.EXPLOSION_LARGE, loc, 1);
        loc.getWorld().playSound(loc, Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
    }
}