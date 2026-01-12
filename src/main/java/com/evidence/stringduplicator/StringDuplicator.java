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
                Block above2 = above1.getRelative(BlockFace.UP);
                boolean hasString = (above1.getType() == Material.TRIPWIRE || above2.getType() == Material.TRIPWIRE);
                boolean isPowered = block.isBlockPowered() || block.isBlockIndirectlyPowered();

                boolean success = false;
                
                // 只有在满足条件时才进入核心逻辑
                if (hasString && isPowered) {
                    if (block.getState() instanceof Dispenser dispenser) {
                        Inventory inv = dispenser.getInventory();
                        
                        // --- 调试日志：获取刷线前的数量 ---
                        int amountBefore = getAmount(inv, Material.STRING);
                        
                        // 执行刷线
                        HashMap<Integer, ItemStack> remaining = inv.addItem(new ItemStack(Material.STRING));
                        
                        if (remaining.isEmpty()) {
                            // 关键：强制同步状态到方块
                            dispenser.update(true, false); 
                            
                            // --- 调试日志：获取刷线后的数量 ---
                            int amountAfter = getAmount(inv, Material.STRING);
                            success = true;
                            
                            // 在控制台输出结果
                            getLogger().info(String.format("[DEBUG] 坐标 %d,%d,%d 刷线成功: %d -> %d", 
                                block.getX(), block.getY(), block.getZ(), amountBefore, amountAfter));
                        } else {
                            getLogger().warning("[DEBUG] 刷线失败：发射器已满！");
                        }
                    }
                }

                // 2. 发送 Action Bar 诊断信息
                String locStr = String.format("§7[%d, %d, %d]", block.getX(), block.getY(), block.getZ());
                String pStr = isPowered ? "§a✔ 电力" : "§c✘ 没电";
                String sStr = hasString ? "§a✔ 有线" : "§c✘ 没线";
                String workStr = success ? "§b§l[+1 产出中]" : (isPowered && hasString ? "§6[容器满]" : "§8[待机]");

                String message = workStr + " " + locStr + " " + pStr + " §8| " + sStr;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(message));
                }
            }

            // 辅助方法：计算背包中某种物品的总数
            private int getAmount(Inventory inv, Material mat) {
                int count = 0;
                for (ItemStack item : inv.getContents()) {
                    if (item != null && item.getType() == mat) {
                        count += item.getAmount();
                    }
                }
                return count;
            }
        }.runTaskTimer(this, 0L, 10L);
        runningTasks.put(block, task);
    }

    private void stopMachine(Block block) {
        BukkitTask task = runningTasks.remove(block);
        if (task != null) task.cancel();
    }
}