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

                Block above1 = block.getRelative(BlockFace.UP);
                Block above2 = above1.getRelative(BlockFace.UP);
                boolean hasString = (above1.getType() == Material.TRIPWIRE || above2.getType() == Material.TRIPWIRE);
                boolean isPowered = block.isBlockPowered() || block.isBlockIndirectlyPowered();

                boolean success = false;
                
                if (hasString && isPowered) {
                    // 获取最新的 BlockState 并强制转换为 Dispenser
                    if (block.getState() instanceof Dispenser dispenser) {
                        Inventory inv = dispenser.getInventory();
                        
                        // 调试：看看这个容器到底有几个格子
                        int invSize = inv.getSize();
                        int beforeCount = countString(inv);

                        // --- 强力注入逻辑开始 ---
                        for (int i = 0; i < invSize; i++) {
                            ItemStack item = inv.getItem(i);
                            
                            // 情况 A: 格子是空的
                            if (item == null || item.getType() == Material.AIR) {
                                inv.setItem(i, new ItemStack(Material.STRING, 1));
                                success = true;
                                break;
                            } 
                            // 情况 B: 格子已经是线，且没堆叠满
                            else if (item.getType() == Material.STRING && item.getAmount() < 64) {
                                item.setAmount(item.getAmount() + 1);
                                success = true;
                                break;
                            }
                        }

                        if (success) {
                            // 关键：提交修改，true 表示强制应用，false 表示不触发物理邻居更新
                            dispenser.update(true, false);
                            
                            int afterCount = countString(inv);
                            getLogger().info(String.format("[DEBUG] 坐标 %d,%d,%d | 类型: %s | 数量: %d -> %d", 
                                block.getX(), block.getY(), block.getZ(), inv.getType(), beforeCount, afterCount));
                        } else {
                            // 如果循环结束 success 还是 false，说明 9 个格子全满了且全是 64 个
                            // getLogger().warning("[DEBUG] 刷线失败：所有格子已满！");
                        }
                    }
                }

                // Action Bar 显示
                String locStr = String.format("§7[%d, %d, %d]", block.getX(), block.getY(), block.getZ());
                String pStr = isPowered ? "§a✔ 电力" : "§c✘ 没电";
                String sStr = hasString ? "§a✔ 有线" : "§c✘ 没线";
                String workStr = success ? "§b§l[+1 产出中]" : (isPowered && hasString ? "§6[满/错误]" : "§8[待机]");

                TextComponent msg = new TextComponent(workStr + " " + locStr + " " + pStr + " §8| " + sStr);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, msg);
                }
            }

            // 修正后的计数方法
            private int countString(Inventory inv) {
                int count = 0;
                for (ItemStack item : inv.getContents()) {
                    if (item != null && item.getType() == Material.STRING) {
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