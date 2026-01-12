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
        registerRecipe(); // 注册工作台合成表
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("String Duplicator enabled! Recipe: 7 Stones + 2 Doors");
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
        // 创建合成表：Key 必须唯一
        NamespacedKey recipeKey = new NamespacedKey(this, "string_duplicator_recipe");
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, machineItemCache);

        // 设定形状 (S=Stone, D=Door)
        // 第一排 SSS, 第二排 SDS, 第三排 SDS
        recipe.shape("SSS", "SDS", "SDS");

        // 设定原料
        recipe.setIngredient('S', Material.STONE);
        recipe.setIngredient('D', Material.OAK_DOOR); // 默认用橡木门，Java API限制一个字符对应一种材质

        getServer().addRecipe(recipe);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item == null || item.getItemMeta() == null) return;
        
        if (!item.getItemMeta().getPersistentDataContainer().has(MACHINE_KEY, PersistentDataType.BYTE)) {
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
            // 在 StringDuplicator.java 里的 run() 内部
            public void run() {
                if (block.getType() != Material.DISPENSER) {
                    stopMachine(block);
                    return;
                }

                // 检查上方第 1 格和第 2 格，只要有线就工作
                Block above1 = block.getRelative(BlockFace.UP);
                Block above2 = above1.getRelative(BlockFace.UP);

                if (block.getState() instanceof Dispenser dispenser) {
                    Inventory inv = dispenser.getInventory();
                    if (inv.firstEmpty() != -1) {  // 检查是否有空位
                        inv.addItem(new ItemStack(Material.STRING));
                        dispenser.update();  // 更新方块状态
                    }
                }
            }
        }.runTaskTimer(this, 0L, 10L); // 每0.5秒生成一个
        runningTasks.put(block, task);
    }

    private void stopMachine(Block block) {
        BukkitTask task = runningTasks.remove(block);
        if (task != null) task.cancel();
    }
}