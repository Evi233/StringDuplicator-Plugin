package com.evidence.stringduplicator;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StringDuplicator extends JavaPlugin implements Listener {

    private final NamespacedKey MACHINE_KEY = new NamespacedKey(this, "is_string_duplicator");
    private final Map<Block, BukkitTask> runningTasks = new HashMap<>();
    private ItemStack machineItemCache;

    @Override
    public void onEnable() {
        createMachineItem();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new CraftingListener(this, machineItemCache), this);
        getLogger().info("String Duplicator enabled!");
    }

    @Override
    public void onDisable() {
        runningTasks.values().forEach(BukkitTask::cancel);
        runningTasks.clear();
    }

    private void createMachineItem() {
        ItemStack item = new ItemStack(Material.DISPENSER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§c§l刷线机");
            List<String> lore = new ArrayList<>();
            lore.add("§7丢掷合成: 15石头 + 2任意门");
            lore.add("§7用法: 通电 + 上方放线");
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(MACHINE_KEY, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        this.machineItemCache = item;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getItemMeta() == null || !item.getItemMeta().getPersistentDataContainer().has(MACHINE_KEY, PersistentDataType.BYTE)) {
            return;
        }
        Block block = event.getBlockPlaced();
        if (block.getState() instanceof TileState state) {
            state.getPersistentDataContainer().set(MACHINE_KEY, PersistentDataType.BYTE, (byte) 1);
            state.update();
            event.getPlayer().sendMessage("§a刷线机已放置！");
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (runningTasks.containsKey(event.getBlock())) {
            runningTasks.get(event.getBlock()).cancel();
            runningTasks.remove(event.getBlock());
        }
    }

    @EventHandler
    public void onRedstoneEvent(BlockRedstoneEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.DISPENSER) return;

        int newCurrent = event.getNewCurrent();
        int oldCurrent = event.getOldCurrent();
        boolean turnedOn = oldCurrent == 0 && newCurrent > 0;
        boolean turnedOff = oldCurrent > 0 && newCurrent == 0;

        if (!turnedOn && !turnedOff) return;
        if (!(block.getState() instanceof TileState state)) return;
        if (!state.getPersistentDataContainer().has(MACHINE_KEY, PersistentDataType.BYTE)) return;

        if (turnedOn) startMachine(block);
        else stopMachine(block);
    }

    private void startMachine(Block block) {
        if (runningTasks.containsKey(block)) return;

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (block.getType() != Material.DISPENSER) {
                    stopMachine(block);
                    return;
                }
                Block above = block.getRelative(BlockFace.UP);
                if (above.getType() == Material.TRIPWIRE) {
                    block.getWorld().dropItemNaturally(
                        block.getLocation().add(0.5, 1.2, 0.5), 
                        new ItemStack(Material.STRING)
                    );
                }
            }
        }.runTaskTimer(this, 0L, 10L);
        runningTasks.put(block, task);
    }

    private void stopMachine(Block block) {
        BukkitTask task = runningTasks.remove(block);
        if (task != null) task.cancel();
    }
}