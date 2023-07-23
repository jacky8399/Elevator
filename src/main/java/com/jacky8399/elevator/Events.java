package com.jacky8399.elevator;

import com.jacky8399.elevator.utils.BlockUtils;
import com.jacky8399.elevator.utils.ItemUtils;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Openable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
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

        ElevatorController controller = new ElevatorController(block.getWorld(), block, BoundingBox.of(
                block.getRelative(-1, -4, -1),
                block.getRelative(1, -1, 1)
        ));
        controller.save();
        ElevatorManager.elevators.put(new BlockVector(block.getX(), block.getY(), block.getZ()), controller);

        controller.showOutline(List.of(e.getPlayer()));

    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;
        Block block = e.getClickedBlock();
        ElevatorController controller = ElevatorManager.elevators.get(new BlockVector(block.getX(), block.getY(), block.getZ()));
        if (controller == null)
            return;
        e.setCancelled(true);
        if (!e.getPlayer().isSneaking()) {
            controller.showOutline(List.of(e.getPlayer()));
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        loadChunkElevators(e.getChunk());
    }

    static void loadChunkElevators(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities()) {
            Block block = state.getBlock();
            ElevatorController controller = ElevatorController.load(block, (TileState) state);
            if (controller != null)
                ElevatorManager.elevators.put(BlockUtils.toVector(block), controller);
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Chunk chunk = e.getChunk();
        for (BlockState state : chunk.getTileEntities()) {
            Block block = state.getBlock();
            ElevatorController controller = ElevatorManager.elevators.remove(BlockUtils.toVector(block));
            if (controller != null) {
                controller.save();
                ElevatorManager.removeElevator(controller);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        ElevatorManager.playerElevatorCache.remove(e.getPlayer());
    }

    @EventHandler
    public void onBlockRedstone(BlockRedstoneEvent e) {
        Block block = e.getBlock();
        if (ElevatorManager.managedDoors.containsKey(block)) {
            Openable data = (Openable) block.getBlockData();
            e.setNewCurrent(data.isOpen() ? 15 : 0);
        }
    }

}
