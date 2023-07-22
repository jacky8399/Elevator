package com.jacky8399.elevator;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.WeakHashMap;
import java.util.logging.Logger;

public final class Elevator extends JavaPlugin {

    public static Elevator INSTANCE;

    public static Logger LOGGER;


    @Override
    public void onEnable() {
        INSTANCE = this;
        LOGGER = getLogger();

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
    }

    public void tick() {
        for (ElevatorController controller : ElevatorManager.elevators.values()) {
            controller.tick();
        }
    }
}
