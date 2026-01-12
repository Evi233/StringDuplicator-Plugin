package com.evidence.stringduplicator;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.block.Dispenser;
import org.bukkit.inventory.Inventory;

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
        registerRecipe();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("String Duplicator (Action Bar Diagnostic) enabled!");
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
            lore.add("§7合成配方: 7石头 + 2任意门");
            lore.add("§7用法: 通红石电 + 上方放线");
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(MACHINE_KEY, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        this.machineItemCache = item;
    }

    private void registerRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(this, "string_duplicator_recipe");

        // --- 新增：如果已存在同名合成表，先将其移除 ---
        getServer().removeRecipe(recipeKey);

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, machineItemCache);
        recipe.shape("SSS", "SDS", "SDS");
        recipe.setIngredient('S', Material.STONE);
        recipe.setIngredient('D', Material.OAK_DOOR);
        
        getServer().addRecipe(recipe);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item == null || item.getItemMeta() == null) return;
        
        if (item.getItemMeta().getPersistentDataContainer().has(MACHINE_KEY, PersistentDataType.BYTE)) {
            Block block = event.getBlockPlaced();
            if (block.getState() instanceof TileState state) {
                state.getPersistentDataContainer().set(MACHINE_KEY, PersistentDataType.BYTE, (byte) 1);
                state.update();
                event.getPlayer().sendMessage("§a刷线机已放置！靠近机器可查看状态。");
                
                // 放置后即便没电也启动一个只显示诊断的任务
                startMachine(block);
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        stopMachine(event.getBlock());
    }

    @EventHandler
    public void onRedstoneEvent(BlockRedstoneEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.DISPENSER) return;
        if (!(block.getState() instanceof TileState state)) return;
        if (!state.getPersistentDataContainer().has(MACHINE_KEY, PersistentDataType.BYTE)) return;

        // 红石信号改变时尝试触发任务启动
        startMachine(block);
    }

    private void startMachine(Block block) {
        if (runningTasks.containsKey(block)) return;

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (block.getType() != Material.DISPENSER) {
                    this.cancel();
                    runningTasks.remove(block);
                    return;
                }

                // 1. 物理状态检测
                Block above1 = block.getRelative(BlockFace.UP);
                Block above2 = above1.getRelative(BlockFace.UP);
                boolean hasString = above1.getType() == Material.TRIPWIRE || above2.getType() == Material.TRIPWIRE;
                boolean isPowered = block.isBlockPowered() || block.isBlockIndirectlyPowered();

                // 2. 构造 Action Bar 诊断消息
                String powerText = isPowered ? "§a✔ 已通电" : "§c✘ 未通电";
                String stringText = hasString ? "§a✔ 已检测到线" : "§c✘ 未检测到线";
                String status = isPowered && hasString ? "§b[运行中]" : "§7[待机中]";
                String message = status + " " + powerText + " §8| " + stringText;

                // 3. 发送给周围 5 格内的玩家
                for (Player player : block.getWorld().getPlayers()) {
                    if (player.getLocation().distance(block.getLocation()) <= 5) {
                        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
                    }
                }

                // 4. 只有在满足条件时才执行刷线逻辑
                if (hasString && isPowered) {
                    if (block.getState() instanceof Dispenser dispenser) {
                        Inventory inv = dispenser.getInventory();
                        if (inv.firstEmpty() != -1) {
                            inv.addItem(new ItemStack(Material.STRING));
                            dispenser.update();
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0L, 10L); // 每 0.5 秒更新一次
        runningTasks.put(block, task);
    }

    private void stopMachine(Block block) {
        BukkitTask task = runningTasks.remove(block);
        if (task != null) task.cancel();
    }
}