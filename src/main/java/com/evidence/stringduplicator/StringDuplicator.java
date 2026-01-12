package com.evidence.stringduplicator;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
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
    private final NamespacedKey RECIPE_KEY = new NamespacedKey(this, "string_duplicator_recipe");
    private final Map<Block, BukkitTask> runningTasks = new HashMap<>();
    private ItemStack machineItemCache;

    @Override
    public void onEnable() {
        createMachineItem();
        registerRecipe();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("String Duplicator (Global Broadcast) enabled!");
    }

    @Override
    public void onDisable() {
        // 停止所有运行中的任务
        runningTasks.values().forEach(BukkitTask::cancel);
        runningTasks.clear();
        
        // 卸载合成表，防止热重载 (PlugManX) 报错
        getServer().removeRecipe(RECIPE_KEY);
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
        // 注册前先尝试移除旧的，确保热重载不报错
        getServer().removeRecipe(RECIPE_KEY);

        ShapedRecipe recipe = new ShapedRecipe(RECIPE_KEY, machineItemCache);
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
                event.getPlayer().sendMessage("§a刷线机已放置！全服玩家均可看到诊断信息。");
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

        // 只要有红石更新就尝试维护任务
        startMachine(block);
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

                // 1. 环境检测
                Block above1 = block.getRelative(BlockFace.UP);
                boolean hasString = (above1.getType() == Material.TRIPWIRE || block.getRelative(BlockFace.UP, 2).getType() == Material.TRIPWIRE);
                boolean isPowered = block.isBlockPowered() || block.isBlockIndirectlyPowered();

                if (hasString && isPowered) {
                    // 2. 获取快照
                    org.bukkit.block.BlockState state = block.getState();
                    if (state instanceof Dispenser dispenser) {
                        // 使用 getSnapshotInventory() 明确操作快照
                        Inventory inv = dispenser.getSnapshotInventory();
                        
                        // 调试：打印操作前的 Slot 0
                        ItemStack beforeItem = inv.getItem(0);
                        String beforeLog = (beforeItem == null) ? "EMPTY" : beforeItem.getType().name() + "x" + beforeItem.getAmount();

                        // 3. 强行在 Slot 0 塞入 1 根线（不管原来有什么）
                        inv.setItem(0, new ItemStack(Material.STRING, 1));
                        
                        // 4. 提交修改 (true, true) 强制应用并同步
                        boolean updateSuccess = dispenser.update(true, true);
                        
                        // 调试：打印操作后的 Slot 0
                        ItemStack afterItem = inv.getItem(0);
                        String afterLog = (afterItem == null) ? "EMPTY" : afterItem.getType().name() + "x" + afterItem.getAmount();

                        getLogger().info(String.format("[DEBUG] 坐标 %d,%d,%d | 更新:%b | Slot0: %s -> %s", 
                            block.getX(), block.getY(), block.getZ(), updateSuccess, beforeLog, afterLog));

                        // 5. 最后的保险：如果背包更新实在玄学，直接在世界上方刷出掉落物
                        // 这样即使发射器坏了，玩家也能拿到线
                        // block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.2, 0.5), new ItemStack(Material.STRING));
                    }
                }

                // Action Bar 依然保持
                String status = (hasString && isPowered) ? "§b§l[生产中]" : "§7[待机]";
                String message = status + " §a电:" + isPowered + " §e线:" + hasString;
                for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(message));
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