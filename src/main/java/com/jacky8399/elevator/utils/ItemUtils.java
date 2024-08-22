package com.jacky8399.elevator.utils;

import com.jacky8399.elevator.Config;
import com.jacky8399.elevator.Elevator;
import net.kyori.adventure.platform.bukkit.BukkitComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.persistence.PersistentDataType;

public class ItemUtils {
    public static final NamespacedKey ELEVATOR_CONTROLLER = new NamespacedKey(Elevator.INSTANCE, "elevator_controller");

    private static final BlockData PISTON_DOWN = Material.PISTON.createBlockData(data -> ((Directional) data).setFacing(BlockFace.DOWN));

    public static ItemStack getControllerItem() {
        ItemStack stack = new ItemStack(Material.PISTON);
        BlockDataMeta meta = (BlockDataMeta) stack.getItemMeta();
        meta.setItemName(BukkitComponentSerializer.legacy().serialize(Config.elevatorItemName));
        meta.setBlockData(PISTON_DOWN);
        meta.getPersistentDataContainer().set(ELEVATOR_CONTROLLER, PersistentDataType.BOOLEAN, true);
        stack.setItemMeta(meta);

        return stack;
    }

    public static boolean isControllerItem(ItemStack stack) {
        return stack != null && stack.getType() == Material.PISTON &&
                stack.getItemMeta().getPersistentDataContainer().has(ELEVATOR_CONTROLLER, PersistentDataType.BOOLEAN);
    }
}
