package com.jacky8399.elevator;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Logger;

public final class Elevator extends JavaPlugin {

    public static Elevator INSTANCE;

    public static Logger LOGGER;


    public static Set<List<ElevatorBlock>> mustCleanupList = new HashSet<>();
    public static Set<Display> mustCleanup = Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void onEnable() {
        INSTANCE = this;
        LOGGER = getLogger();

        saveDefaultConfig();
        reloadConfig();

        getCommand("elevator").setExecutor(new CommandElevator());

        Bukkit.getPluginManager().registerEvents(new Events(), this);

        Bukkit.getScheduler().runTaskTimer(this, this::tick, 0, 1);

        // load all elevators
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                Events.loadChunkElevators(chunk);
            }
        }
    }

    @Override
    public void onDisable() {
        for (ElevatorController controller : ElevatorManager.elevators.values()) {
            controller.immobilize();
            controller.save();
        }
        ElevatorManager.elevators.clear();
        ElevatorManager.playerElevatorCache.clear();
        ElevatorManager.managedDoors.clear();
        for (List<ElevatorBlock> elevatorBlocks : mustCleanupList) {
            for (ElevatorBlock block : elevatorBlocks) {
                block.remove();
            }
        }
        mustCleanupList.clear();
        for (Display display : mustCleanup) {
            display.remove();
        }
        mustCleanup.clear();
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        Config.reload();

        getConfig().options().copyDefaults(true).parseComments(true);

        saveConfig();
    }

    public void tick() {
        for (ElevatorController controller : ElevatorManager.elevators.values()) {
            controller.tick();
        }
    }
}
