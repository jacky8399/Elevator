package com.jacky8399.elevator;

import com.jacky8399.elevator.utils.BlockUtils;
import com.jacky8399.elevator.utils.FloorScan;
import com.jacky8399.elevator.utils.ItemUtils;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static com.jacky8399.elevator.Elevator.ADVNTR;
import static com.jacky8399.elevator.Messages.*;

public class CommandElevator implements TabExecutor {
    public static Player scanner; // debug

    private static boolean checkPermission(CommandSender sender, String perm) {
        if (!sender.hasPermission("elevator." + perm)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to do this.");
            return false;
        }
        return true;
    }

    private static ElevatorController checkElevator(Player player) {
        ElevatorManager.PlayerElevator cache = ElevatorManager.playerElevatorCache.get(player);
        if (cache != null) {
            return cache.controller();
        }
        Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock != null) {
            ElevatorController targetController = ElevatorManager.elevators.get(targetBlock);
            if (targetController != null) {
                return targetController;
            }
            ElevatorController controllerFromFloor = ElevatorManager.managedFloors.get(targetBlock);
            if (controllerFromFloor != null) {
                return controllerFromFloor;
            }
        }
        ADVNTR.player(player).sendMessage(msgErrorNotInElevator);
        return null;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.GREEN + "Elevator v" + Elevator.INSTANCE.getDescription().getVersion());
            return true;
        }

        if (args[0].equals("reload") && checkPermission(sender, "command.reload")) {
            Elevator.INSTANCE.reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "Config reloaded.");
            return true;
        }

        if (!(sender instanceof Player player))
            return true;
        Audience audience = ADVNTR.player(player);

        switch (args[0]) {
            case "givecontroller" -> {
                if (!checkPermission(player, "command.givecontroller"))
                    return true;
                player.getInventory().addItem(ItemUtils.getControllerItem());
            }
            case "debug" -> {
                if (!checkPermission(player, "command.debug"))
                    return true;
                Config.debug = !Config.debug;
                player.sendMessage("Set debug to " + Config.debug);
            }
            case "resize" -> {
                if (!checkPermission(player, "command.resize"))
                    return true;
                if (args.length != 7) {
                    player.sendMessage(ChatColor.RED + "Usage: /" + label + " resize <x1> <y1> <z1> <x2> <y2> <z2>");
                    return true;
                }
                var cache = ElevatorManager.playerElevatorCache.get(player);
                if (cache == null) {
                    player.sendMessage(ChatColor.RED + "You are not in an elevator!");
                    return true;
                }

                int x1 = Integer.parseInt(args[1]);
                int y1 = Integer.parseInt(args[2]);
                int z1 = Integer.parseInt(args[3]);
                int x2 = Integer.parseInt(args[4]);
                int y2 = Integer.parseInt(args[5]);
                int z2 = Integer.parseInt(args[6]);

                ElevatorController controller = cache.controller();
                controller.cabin.resize(
                        Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                        Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, Math.max(z1, z2) + 1
                );
                controller.scanFloors();
                player.sendMessage(ChatColor.GREEN + "Redefined border");

            }
            case "maintenance" -> {
                if (!checkPermission(player, "command.maintenance"))
                    return true;
                var elevator = checkElevator(player);
                if (elevator == null) {
                    return true;
                }
                elevator.maintenance = !elevator.maintenance;
                Component message = elevator.maintenance ? msgBeginMaintenance : msgEndMaintenance;
                audience.sendMessage(message);
            }
            case "up" -> {
                if (!checkPermission(player, "command.up"))
                    return true;
                var cache = checkElevator(player);
                if (cache == null) {
                    return true;
                }
                cache.moveUp();
            }
            case "down" -> {
                if (!checkPermission(player, "command.down"))
                    return true;
                var cache = checkElevator(player);
                if (cache == null) {
                    return true;
                }
                cache.moveDown();
            }
            case "scan" -> {
                if (!checkPermission(player, "command.scan"))
                    return true;
                var elevator = checkElevator(player);
                if (elevator == null) {
                    return true;
                }
                scanner = player;
                FloorScan result = elevator.scanFloors();
                scanner = null;
                Component message = result.getMessage();
                if (message != Component.empty())
                    audience.sendMessage(message);
                List<Display> displays = result.createDisplay(elevator, player);
                BlockUtils.ensureCleanUp(displays, 10 * 20);
            }
            case "fixgravity" -> {
                if (!checkPermission(player, "command.fixgravity"))
                    return true;
                if (args.length == 2) {
                    var entities = Bukkit.selectEntities(player, args[1]);
                    for (var entity : entities) {
                        entity.setGravity(true);
                    }
                } else {
                    player.setGravity(true);
                }
            }
            case "setspeed" -> {
                if (!checkPermission(player, "command.setspeed"))
                    return true;
                if (args.length != 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /" + label + " setspeed <speed>");
                    return true;
                }
                int speed;
                try {
                    speed = Integer.parseInt(args[1]);
                    if (speed <= 0 || speed > 20)
                        throw new IllegalArgumentException("Speed must be an integer between 1 - 20");
                } catch (Exception ex) {
                    player.sendMessage(ChatColor.RED + "Invalid speed: " + ex.getMessage());
                    return true;
                }
                var cache = checkElevator(player);
                if (cache == null)
                    return true;
                cache.speed = speed;
                player.sendMessage(ChatColor.GREEN + "Set elevator speed to " + speed + " blocks per second");
                if (20 % speed != 0) {
                    player.sendMessage(ChatColor.YELLOW + "Please note that speeds that are not factors of 20 will never be supported.");
                }
            }
            case "setmaxheight" -> {
                if (!checkPermission(player, "command.setmaxheight"))
                    return true;
                if (args.length != 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /" + label + " setmaxheight <speed>");
                    return true;
                }
                int maxHeight;
                try {
                    maxHeight = Integer.parseInt(args[1]);
                    if (maxHeight <= 0)
                        throw new IllegalArgumentException("Max height must be greater than 0");
                } catch (Exception ex) {
                    player.sendMessage(ChatColor.RED + "Invalid height: " + ex.getMessage());
                    return true;
                }
                var elevator = checkElevator(player);
                if (elevator == null)
                    return true;
                elevator.maxHeight = maxHeight;
                player.sendMessage(ChatColor.GREEN + "Set elevator max height to " + maxHeight + " blocks");
            }
            default -> player.sendMessage(ChatColor.RED + "Unknown command " + args[0]);
        }
        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> strings = switch (args.length) {
            default -> List.of();
            case 1 -> List.of("givecontroller", "up", "down", "scan", "maintenance", "resize", "setspeed", "setmaxheight");
            case 2 -> switch (args[0]) {
                case "setspeed" -> List.of("1", "2", "4", "5", "10", "20");
                default -> List.of();
            };
        };
        return StringUtil.copyPartialMatches(args[args.length - 1], strings, new ArrayList<>());
    }
}
