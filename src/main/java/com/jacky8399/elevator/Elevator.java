package com.jacky8399.elevator;

import com.jacky8399.elevator.animation.ElevatorAnimation;
import com.jacky8399.elevator.animation.TransformationAnimation;
import com.jacky8399.elevator.utils.BitmapGlyphInfo;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Elevator extends JavaPlugin {

    public static Elevator INSTANCE;

    public static Logger LOGGER;

    public static BukkitAudiences ADVNTR;

    public static ElevatorAnimation.Factory<?> SCHEDULER;

    public static Set<List<ElevatorBlock>> mustCleanupList = new HashSet<>();
    public static Set<Display> mustCleanup = Collections.newSetFromMap(new WeakHashMap<>());
    public static boolean disabling;

    @Override
    public void onEnable() {
        INSTANCE = this;
        LOGGER = getLogger();
        ADVNTR = BukkitAudiences.create(this);
        SCHEDULER = TransformationAnimation.FACTORY;

        saveDefaultConfig();
        if (!new File(getDataFolder(), "messages.yml").exists())
            saveResource("messages.yml", false);
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

        // force load BitmapGlyphInfo
        long start = System.nanoTime();
        BitmapGlyphInfo.getBitmapGlyphInfo('a');
        long elapsed = System.nanoTime() - start;
        LOGGER.info("Loading character info took " + elapsed / 1000000 + "ms");
    }

    @Override
    public void onDisable() {
        disabling = true;
        for (ElevatorController controller : ElevatorManager.elevators.values()) {
            try {
                controller.cleanUp();
                controller.save();
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, "Elevator at (%s, %d, %d, %d) failed to save".formatted(
                        controller.world.getName(),
                        controller.controller.getX(),
                        controller.controller.getY(),
                        controller.controller.getZ()
                ), ex);
            }
        }
        ElevatorManager.cleanUp();
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
        if (ADVNTR != null) {
            ADVNTR.close();
            ADVNTR = null;
        }
    }

    @Override
    public void reloadConfig() {
        // remove all ropes
        for (ElevatorController controller : ElevatorManager.elevators.values()) {
            controller.removeRope();
        }

        super.reloadConfig();
        Config.reload();
        Messages.reload();

        getConfig().options().copyDefaults(true).parseComments(true);

        saveConfig();

        // regenerate all ropes
        for (ElevatorController controller : ElevatorManager.elevators.values()) {
            controller.refreshRope();
        }
    }

    public void tick() {
        for (ElevatorController controller : ElevatorManager.elevators.values()) {
            controller.tick();
        }
    }
}
