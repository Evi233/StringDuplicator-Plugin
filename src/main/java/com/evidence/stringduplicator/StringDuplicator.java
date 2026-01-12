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
                // 1. 基础检查
                if (block.getType() != Material.DISPENSER) {
                    stopMachine(block);
                    return;
                }

                // 2. 环境检测 (确保检测的是 TRIPWIRE 方块形态)
                Block above1 = block.getRelative(BlockFace.UP);
                Block above2 = above1.getRelative(BlockFace.UP);
                boolean hasString = (above1.getType() == Material.TRIPWIRE || above2.getType() == Material.TRIPWIRE);
                boolean isPowered = block.isBlockPowered() || block.isBlockIndirectlyPowered();

                // 3. 核心修复：直接操作库存
                boolean success = false;
                if (hasString && isPowered) {
                    // 强制获取当前的 TileState
                    org.bukkit.block.BlockState state = block.getState();
                    if (state instanceof Dispenser dispenser) {
                        Inventory inv = dispenser.getInventory();
                        
                        // 直接添加物品
                        HashMap<Integer, ItemStack> remaining = inv.addItem(new ItemStack(Material.STRING));
                        
                        // 如果没有剩余，说明成功存入
                        if (remaining.isEmpty()) {
                            success = true;
                            // 关键：在修改 Inventory 后，对于某些服务端版本，需要强制 update
                            // 使用 dispenser.update(true, false) 强制同步数据且不触发物理更新
                            dispenser.update(true, false); 
                        }
                    }
                }

                // 4. 诊断信息
                String locStr = String.format("§7[%d, %d, %d]", block.getX(), block.getY(), block.getZ());
                String powerStatus = isPowered ? "§a✔ 电力" : "§c✘ 没电";
                String stringStatus = hasString ? "§a✔ 有线" : "§c✘ 没线";
                String workStatus = success ? "§b§l[正在生产...]" : (isPowered && hasString ? "§e[容器已满!]" : "§7[待机]");

                String message = workStatus + " " + locStr + " " + powerStatus + " §8| " + stringStatus;

                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(message));
                    
                    // 5. 增加视觉反馈：如果刷成功了，在方块位置播个粒子效果
                    if (success && p.getLocation().distance(block.getLocation()) < 10) {
                        // 产生一点白色烟雾粒子和声音，证明逻辑真的跑通了
                        block.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 1.2, 0.5), 3);
                        p.playSound(block.getLocation(), org.bukkit.Sound.ENTITY_CHICKEN_EGG, 0.5f, 2.0f);
                    }
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