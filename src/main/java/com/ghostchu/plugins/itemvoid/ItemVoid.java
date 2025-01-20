package com.ghostchu.plugins.itemvoid;

import cc.carm.lib.easysql.EasySQL;
import com.ghostchu.plugins.itemvoid.database.DatabaseManager;
import com.ghostchu.plugins.itemvoid.gui.QueryGUI;
import com.ghostchu.plugins.itemvoid.gui.QueryMode;
import com.ghostchu.plugins.itemvoid.item.ItemVoidManager;
import com.ghostchu.plugins.itemvoid.listener.InventoryListener;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class ItemVoid extends JavaPlugin {
    private final Random RANDOM = new Random();
    private final Lock LOCK = new ReentrantLock();
    private DatabaseManager databaseManager;
    private ItemVoidManager itemVoidManager;

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        this.databaseManager = new DatabaseManager(this);
        this.itemVoidManager = new ItemVoidManager(this);
        Bukkit.getPluginManager().registerEvents(new InventoryListener(this), this);
        Bukkit.getOnlinePlayers().forEach(this::collectFromEntity);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            int count = itemVoidManager.getINSERT_QUEUE().size();
            if (count < getConfig().getInt("save.lazy-threshold")) {
                if (RANDOM.nextInt() != 0) {
                    return;
                }
            }
            int save = count / getConfig().getInt("save.batch-partition-divide-by");
            if (save < getConfig().getInt("save.min-batch-size")) {
                save = getConfig().getInt("save.min-batch-size");
            }
            if (!LOCK.tryLock()) {
                return;
            }
            try {
                itemVoidManager.pollItems(save)
                        .thenAccept(bakedVoidItems -> databaseManager.getDatabaseHelper().saveItems(bakedVoidItems).join())
                        .join();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            } finally {
                LOCK.unlock();
            }
        }, 1, 20);
        Metrics metrics = new Metrics(this, 21560);
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        itemVoidManager.pollItems(-1).thenAccept(bakedVoidItems -> {
            try {
                databaseManager.getDatabaseHelper().saveItems(bakedVoidItems).get(5, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                e.printStackTrace();
            }
        }).join();
        EasySQL.shutdownManager(getDatabaseManager().getSqlManager());
    }

    public ItemVoidManager getItemVoidManager() {
        return itemVoidManager;
    }


    public void collectFromChunk(Chunk chunk) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (BlockState tileEntity : chunk.getTileEntities()) {
                collectFromBlockState(tileEntity);
            }
            for (Entity entity : chunk.getEntities()) {
                collectFromEntity(entity);
            }
        }, 5L);
    }

    public void collectFromBlockState(BlockState state) {
        if (state instanceof InventoryHolder inventoryHolder) {
            itemVoidManager.discover(inventoryHolder.getInventory().getContents());
        }
    }

    public void collectFromEntity(Entity entity) {
        if (entity instanceof Player player) {
            itemVoidManager.discover(player.getInventory().getContents());
            itemVoidManager.discover(player.getEnderChest().getContents());
            itemVoidManager.discover(player.getInventory().getArmorContents());
        } else if (entity instanceof LivingEntity livingEntity) {
            EntityEquipment equipment = livingEntity.getEquipment();
            if (equipment != null) {
                itemVoidManager.discover(equipment.getArmorContents());
                for (EquipmentSlot value : EquipmentSlot.values()) {
                    try {
                        itemVoidManager.discover(equipment.getItem(value));
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("Invalid equipment slot: " + value + " for entity: " + entity.getName());
                    }
                }
            }
        }
        if (entity instanceof ItemFrame itemFrame) {
            itemVoidManager.discover(itemFrame.getItem());
        }
        if (entity instanceof InventoryHolder inventoryHolder) {
            itemVoidManager.discover(inventoryHolder.getInventory().getContents());
        }
    }

    public void collectFromInventory(Inventory inventory) {
        if (inventory.getType() == InventoryType.CREATIVE) {
            return;
        }
        ItemStack[] stacks = inventory.getContents();
        if (inventory.getHolder() != null && inventory.getLocation() != null) {
            itemVoidManager.discover(stacks);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("itemvoid.use")) {
            return false;
        }
        if (args.length < 1) {
            sender.sendMessage("Please given a query arugments：queryName, queryLore, status");
            return false;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "querymaterial" -> {
                if (args.length < 2) {
                    sender.sendMessage("Please give a query material, no space.");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only player can do this");
                    return true;
                }
                QueryGUI queryGUI = new QueryGUI(this, player, args[1], QueryMode.QUERY_MATERIAL);
                queryGUI.open();
            }
            case "queryname" -> {
                if (args.length < 2) {
                    sender.sendMessage("Please give a query keyword, no space.");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only player can do this");
                    return true;
                }
                QueryGUI queryGUI = new QueryGUI(this, player, args[1], QueryMode.QUERY_NAME);
                queryGUI.open();
            }
            case "querylore" -> {
                if (args.length < 2) {
                    sender.sendMessage("Please give a query keyword, no space.");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only player can do this");
                    return true;
                }
                QueryGUI queryGUI = new QueryGUI(this, player, args[1], QueryMode.QUERY_LORE);
                queryGUI.open();
            }
            case "forcesaveall" -> {
                try {
                    if (!LOCK.tryLock()) {
                        sender.sendMessage("Already have a save task running!");
                        return true;
                    }
                    itemVoidManager.pollItems(-1)
                            .thenAccept(bakedVoidItems -> databaseManager.getDatabaseHelper().saveItems(bakedVoidItems)
                                    .thenAccept((e) -> sender.sendMessage("Saved！ long[] = " + e.length)));
                } catch (Throwable throwable) {
                    throwable.printStackTrace();
                } finally {
                    LOCK.unlock();
                }
            }

            case "status" -> sender.sendMessage("Insert queue: " + getItemVoidManager().getINSERT_QUEUE().size());
        }
        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length < 2) {
            return List.of("queryName", "queryLore", "status", "forcesaveall", "queryByMaterial");
        }
        if (args.length == 3) {
            return List.of("keyword");
        }
        return Collections.emptyList();
    }
}
