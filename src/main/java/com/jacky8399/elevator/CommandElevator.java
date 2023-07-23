package com.jacky8399.elevator;

import com.jacky8399.elevator.utils.ItemUtils;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class CommandElevator implements TabExecutor {
    private static boolean checkPermission(CommandSender sender, String perm) {
        if (!sender.hasPermission("elevator." + perm)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to do this.");
            return false;
        }
        return true;
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
        }

        if (!(sender instanceof Player player))
            return true;

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
            case "up" -> {
                if (!checkPermission(player, "command.up"))
                    return true;
                var cache = ElevatorManager.playerElevatorCache.get(player);
                if (cache == null) {
                    player.sendMessage(ChatColor.RED + "You are not in an elevator!");
                    return true;
                }
                ElevatorController controller = cache.controller();
                controller.moveUp();
            }
            case "down" -> {
                if (!checkPermission(player, "command.down"))
                    return true;
                var cache = ElevatorManager.playerElevatorCache.get(player);
                if (cache == null) {
                    player.sendMessage(ChatColor.RED + "You are not in an elevator!");
                    return true;
                }
                ElevatorController controller = cache.controller();
                controller.moveDown();
            }
            case "scan" -> {
                if (!checkPermission(player, "command.scan"))
                    return true;
                var cache = ElevatorManager.playerElevatorCache.get(player);
                if (cache == null) {
                    player.sendMessage(ChatColor.RED + "You are not in an elevator!");
                    return true;
                }
                ElevatorController controller = cache.controller();
                controller.scanFloors();
                player.sendMessage(ChatColor.YELLOW + "Found " + controller.floors.size() + " floors:");
                for (int i = 0; i < controller.floors.size(); i++) {
                    var floor = controller.floors.get(i);
                    player.sendMessage(ChatColor.GREEN + (i == controller.currentFloorIdx ? "> " : "- ") + floor.name() + " @ Y=" + floor.y());
                }
            }
        }

        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return switch (args.length) {
            default -> List.of();
            case 1 -> List.of("givecontroller", "up", "down", "scan");
        };
    }
}
