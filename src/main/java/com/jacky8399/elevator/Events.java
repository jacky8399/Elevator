package com.jacky8399.elevator;

import com.jacky8399.elevator.utils.ItemUtils;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;

import java.util.List;

public class Events implements Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!ItemUtils.isControllerItem(e.getItemInHand()))
            return;
        Block block = e.getBlock();
        block.setBlockData(Material.DROPPER.createBlockData(data -> ((Directional) data).setFacing(BlockFace.DOWN)));
        TileState state = (TileState) block.getState();


        ElevatorController controller = new ElevatorController(block.getWorld(), block.getLocation(), BoundingBox.of(
                block.getRelative(-1, -4, -1),
                block.getRelative(1, -1, 1)
        ));
        Elevator.elevators.put(new BlockVector(block.getX(), block.getY(), block.getZ()), controller);

        controller.showOutline(List.of(e.getPlayer()));

    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;
        Block block = e.getClickedBlock();
        ElevatorController controller = Elevator.elevators.get(new BlockVector(block.getX(), block.getY(), block.getZ()));
        if (controller == null)
            return;
        e.setCancelled(true);
        if (e.getPlayer().isSneaking()) {
            controller.showOutline(List.of(e.getPlayer()));
            return;
        }
        if (controller.moving)
            controller.immobilize();
        else
            controller.mobilize();
    }

}
