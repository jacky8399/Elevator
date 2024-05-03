package com.jacky8399.elevator;

import com.jacky8399.elevator.utils.BlockUtils;
import com.jacky8399.elevator.utils.ItemUtils;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Openable;
import org.bukkit.conversations.ConversationContext;
import org.bukkit.conversations.ConversationFactory;
import org.bukkit.conversations.Prompt;
import org.bukkit.conversations.StringPrompt;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
        ElevatorManager.elevators.put(block, controller);

        controller.showOutline(List.of(e.getPlayer()));

    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null)
            return;
        Player player = e.getPlayer();
        Block block = e.getClickedBlock();
        ElevatorController controller = ElevatorManager.elevators.get(block);
        if (controller != null) {
            e.setCancelled(true);
            if (!player.isSneaking()) {
                if (e.getMaterial() == Material.SLIME_BALL) {
                    player.sendMessage(Config.msgEditCabinPos1);
                    ElevatorManager.playerEditingElevator.put(player, controller);
                } else {
                    player.sendMessage(Config.msgEditCabinInstructions);
                    controller.showOutline(List.of(e.getPlayer()));
                }
            }
            return;
        }
        ElevatorController controllerForFloor = ElevatorManager.managedFloors.get(block);
        if (controllerForFloor != null && e.getMaterial() == Material.NAME_TAG) {
            int y = block.getY();
            var floor = controllerForFloor.floors.stream().filter(f -> f.y() == y).findFirst();
            if (floor.isEmpty())
                return;
            String floorName = Config.untranslateColor(floor.get().name());
            // rename floor
            player.sendMessage(Config.msgEnterFloorName.replace("{floorName}", floorName));
            player.beginConversation(new ConversationFactory(Elevator.INSTANCE)
                    .withFirstPrompt(new StringPrompt() {
                        @Override
                        public @NotNull String getPromptText(@NotNull ConversationContext context) {
                            return ""; // why does it not work lol
                        }

                        @Override
                        public @Nullable Prompt acceptInput(@NotNull ConversationContext context, @Nullable String input) {
                            if (input != null && !floorName.equals(input)) {
                                String newFloorName = Config.translateColor(input);
                                var controller = ElevatorManager.managedFloors.get(block);
                                if (controller != null) {
                                    controller.floorNameOverrides.put(y, newFloorName);
                                    for (int i = 0; i < controller.floors.size(); i++) {
                                        var floor = controller.floors.get(i);
                                        if (floor.y() == y) {
                                            controller.floors.set(i, new ElevatorController.ElevatorFloor(newFloorName, y, floor.source()));
                                        }
                                    }
                                    player.sendMessage(newFloorName);
                                }
                            }
                            return Prompt.END_OF_CONVERSATION;
                        }
                    })
                    .buildConversation(player)
            );
            return;
        }
        ElevatorController editingController = ElevatorManager.playerEditingElevator.get(player);
        if (editingController != null && e.getHand() == EquipmentSlot.HAND) {
            if (e.getMaterial() == Material.SLIME_BALL) {
                Location firstPos = ElevatorManager.playerEditingElevatorPos.get(player);
                if (firstPos == null) {
                    ElevatorManager.playerEditingElevatorPos.put(player, block.getLocation());
                    player.sendMessage(Config.msgEditCabinPos2);
                } else {
                    BoundingBox bb = BoundingBox.of(firstPos.getBlock(), block);
                    editingController.cabin.resize(bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ());
                    editingController.showOutline(List.of(player));
                    player.sendMessage(Config.msgEditCabinSuccess);
                    ElevatorManager.playerEditingElevator.remove(player);
                    ElevatorManager.playerEditingElevatorPos.remove(player);
                }
            } else {
                // cancel
                player.sendMessage(Config.msgEditCabinFailed);
                ElevatorManager.playerEditingElevator.remove(player);
                ElevatorManager.playerEditingElevatorPos.remove(player);
            }
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
                ElevatorManager.elevators.put(block, controller);
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

    @EventHandler
    public void onPlayerHotbar(PlayerItemHeldEvent e) {

    }

}
