package com.bgsoftware.wildchests.listeners;

import com.bgsoftware.wildchests.WildChestsPlugin;
import com.bgsoftware.wildchests.objects.chests.WChest;
import org.bukkit.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.scheduler.BukkitRunnable;

public final class ChunksListener implements Listener {

    private final WildChestsPlugin plugin;

    public ChunksListener(WildChestsPlugin plugin){
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(ChunkLoadEvent e){
        new BukkitRunnable() {
            @Override
            public void run() {
                handleChunkLoad(plugin, e.getChunk());
            }
        }.runTaskLater(plugin, 20);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent e){
        plugin.getDataHandler().saveDatabase(e.getChunk(), true);
    }

    public static void handleChunkLoad(WildChestsPlugin plugin, Chunk chunk){
        boolean loaded = false;
        for(World w: Bukkit.getServer().getWorlds())
        {
            if(w.getName().equals(chunk.getWorld().getName()))
            {
                loaded = true;
                break;
            }
        }
        if(loaded) {
            plugin.getChestsManager().getChests(chunk).forEach(chest -> {
                Location location = chest.getLocation();
                Material blockType = location.getBlock().getType();
                if (blockType != Material.CHEST) {
                    WildChestsPlugin.log("Loading chunk " + chunk.getX() + ", " + chunk.getX() + " but found a chest not " +
                            "associated with a chest block but " + blockType + " at " + location.getWorld().getName() + ", " +
                            location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ());
                    chest.remove();
                } else {
                    ((WChest) chest).onChunkLoad();
                }
            });
        }
        else {
            handleChunkLoad(plugin, chunk);
        }
    }

}
