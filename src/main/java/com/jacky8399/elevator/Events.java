package com.jacky8399.elevator;

import com.jacky8399.elevator.utils.ItemUtils;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.CopperBulb;
import org.bukkit.conversations.ConversationContext;
import org.bukkit.conversations.ConversationFactory;
import org.bukkit.conversations.Prompt;
import org.bukkit.conversations.StringPrompt;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import static com.jacky8399.elevator.Elevator.ADVNTR;
import static com.jacky8399.elevator.Messages.*;

public class Events implements Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!ItemUtils.isControllerItem(e.getItemInHand()))
            return;
        Block block = e.getBlock();
        block.setBlockData(ElevatorController.MATERIAL.createBlockData(data -> ((Directional) data).setFacing(BlockFace.DOWN)));

        ElevatorController controller = new ElevatorController(block.getWorld(), block, BoundingBox.of(
                block.getRelative(-1, -4, -1),
                block.getRelative(1, -1, 1)
        ));
        controller.save();
        ElevatorManager.elevators.put(block, controller);

        Player player = e.getPlayer();
        ADVNTR.player(player).sendMessage(msgEditCabinInstructions);
        controller.showOutline(player);

    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null)
            return;
        Player player = e.getPlayer();
        Audience audience = ADVNTR.player(player);
        Block block = e.getClickedBlock();
        ElevatorController controller = ElevatorManager.elevators.get(block);
        if (controller != null) {
            if (!player.isSneaking()) {
                e.setCancelled(true);
                if (e.getMaterial() == Material.SLIME_BALL) {
                    audience.sendMessage(msgEditCabinPos1);
                    ElevatorManager.playerEditingElevator.put(player, controller);
                } else {
                    audience.sendMessage(msgEditCabinInstructions);
                    controller.showOutline(e.getPlayer());
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
            String floorName = MiniMessage.miniMessage().serialize(floor.get().name());
            // rename floor
            audience.sendMessage(renderMessage(msgEnterFloorName, Map.of("floor_name", Component.text(floorName))));
            player.beginConversation(new ConversationFactory(Elevator.INSTANCE)
                    .withFirstPrompt(new StringPrompt() {
                        @Override
                        public @NotNull String getPromptText(@NotNull ConversationContext context) {
                            return ""; // why does it not work lol
                        }

                        @Override
                        public @Nullable Prompt acceptInput(@NotNull ConversationContext context, @Nullable String input) {
                            if (input != null && !floorName.equals(input)) {
                                Component newFloorName = parsePlayerMiniMessage(input);
                                var controller = ElevatorManager.managedFloors.get(block);
                                if (controller != null) {
                                    controller.floorNameOverrides.put(y, newFloorName);
                                    for (int i = 0; i < controller.floors.size(); i++) {
                                        var floor = controller.floors.get(i);
                                        if (floor.y() == y) {
                                            controller.floors.set(i, new ElevatorController.ElevatorFloor(newFloorName, y, floor.source()));
                                        }
                                    }
                                    audience.sendMessage(newFloorName);
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
                    audience.sendMessage(msgEditCabinPos2);
                } else {
                    BoundingBox bb = BoundingBox.of(firstPos.getBlock(), block);
                    editingController.cabin.resize(bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ());
                    editingController.scanFloors();
                    editingController.showOutline(player);
                    audience.sendMessage(msgEditCabinSuccess);
                    ElevatorManager.playerEditingElevator.remove(player);
                    ElevatorManager.playerEditingElevatorPos.remove(player);
                }
            } else {
                // cancel
                audience.sendMessage(msgEditCabinFailed);
                ElevatorManager.playerEditingElevator.remove(player);
                ElevatorManager.playerEditingElevatorPos.remove(player);
            }
        }
    }

    private static final Set<Chunk> loadedChunks = new HashSet<>();
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        Chunk chunk = e.getChunk();
        Bukkit.getScheduler().runTaskLater(Elevator.INSTANCE, () -> {
            if (loadedChunks.add(chunk))
                loadChunkElevators(chunk);
        }, 10);
    }

    static void loadChunkElevators(Chunk chunk) {
        loadedChunks.add(chunk);
        int loaded = 0;
        long gameTime = chunk.getWorld().getGameTime();
        for (BlockState state : chunk.getTileEntities()) {
            Block block = state.getBlock();
            if (ElevatorManager.elevators.containsKey(block)) {
                Elevator.LOGGER.log(Level.WARNING,
                        "An elevator already exists at %d, %d, %d! Skipping...".formatted(block.getX(), block.getY(), block.getZ()),
                        Config.debug ? new RuntimeException() : null);
                continue;
            }
            if (ElevatorManager.recentlyUnloadedElevators.get(block) instanceof Long unloadedAt) {
                if (gameTime - unloadedAt < 2) {
                    Elevator.LOGGER.warning("Elevator at %d, %d, %d was unloaded %d ticks ago! Skipping...".formatted(
                            block.getX(), block.getY(), block.getZ(), gameTime - unloadedAt
                    ));
                    continue;
                }
            }
            ElevatorController controller = ElevatorController.load(block, (TileState) state);
            if (controller != null) {
                ElevatorManager.elevators.put(block, controller);
                loaded++;
            }
        }
        if (Config.debug && loaded != 0) {
            Elevator.LOGGER.info("Loaded %d elevators for chunk %d, %d".formatted(loaded, chunk.getX(), chunk.getZ()));
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Chunk chunk = e.getChunk();
        if (!loadedChunks.remove(chunk))
            return;
        if (!chunk.isLoaded()) {
            Elevator.LOGGER.info("Chunk isn't loaded!!");
            e.getWorld().getChunkAt(chunk.getX(), chunk.getZ());
        }
        int unloaded = 0;
        for (BlockState state : chunk.getTileEntities()) {
            Block block = state.getBlock();
            ElevatorController controller = ElevatorManager.elevators.remove(block);
            if (controller != null) {
                controller.cleanUp();
                controller.save();
                ElevatorManager.removeElevator(controller);
                ElevatorManager.setUnloadedAt(block, block.getWorld().getGameTime());
                unloaded++;
            }
        }

        if (Config.debug && unloaded != 0) {
            Elevator.LOGGER.info("Unloaded %d elevators for chunk %d, %d".formatted(unloaded, chunk.getX(), chunk.getZ()));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        ElevatorManager.playerElevatorCache.remove(e.getPlayer());
    }

    // block redstone signal to managed doors
    @EventHandler
    public void onBlockRedstone(BlockRedstoneEvent e) {
        Block block = e.getBlock();
        if (ElevatorManager.managedDoors.containsKey(block)) {
            BlockData data = block.getBlockData();
            switch (data) {
                case Openable openable -> e.setNewCurrent(openable.isOpen() ? 15 : 0);
                case CopperBulb copperBulb -> e.setNewCurrent(0);
                case Lightable lightable -> e.setNewCurrent(lightable.isLit() ? 15 : 0);
                default -> {}
            }
        }
    }

    @EventHandler
    public void onPlayerHotbar(PlayerItemHeldEvent e) {
        int diff = e.getNewSlot() - e.getPreviousSlot();
        int adjusted; // handle scrolling between slots 1 and 9
        if (diff > 4)
            adjusted = diff - 9;
        else if (diff < -4)
            adjusted = diff + 9;
        else
            adjusted = diff;
        // when scrolling up, the difference would be negative
        // since floors are stored in ascending Y order, we need to traverse in the opposite direction
        adjusted = -adjusted;
        Player player = e.getPlayer();
        var oldElevator = ElevatorManager.playerElevatorCache.get(player);
        if (oldElevator != null) {
            ElevatorManager.playerElevatorCache.put(player, new ElevatorManager.PlayerElevator(oldElevator.controller(),
                    oldElevator.floorDiff() + adjusted));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDestroyed(BlockBreakEvent e) {
        // check if rope
        Block block = e.getBlock();
        if (!Config.elevatorRopeBlock.matches(block.getBlockData()))
            return;
        // scan up to try to find the controller
        var controller = doRopeScan(block);
        if (controller != null) {
            e.setCancelled(true);
            controller.removeRope();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockLoot(BlockDropItemEvent e) {
        // check if rope
        Block block = e.getBlock();
        BlockState state = e.getBlockState();
        ElevatorController controller = ElevatorManager.elevators.get(block);
        if (controller != null) {
            List<Item> drops = e.getItems();
            drops.clear();
            drops.add(block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), ItemUtils.getControllerItem()));
            controller.cleanUp();
            ElevatorManager.removeElevator(controller);
        } else if (Config.elevatorRopeBlock.matches(state.getBlockData())) {
            // scan up to try to find the controller
            controller = doRopeScan(block);
            if (controller != null) {
                e.setCancelled(true);
                controller.removeRope();
            }
        }
    }

    private static ElevatorController doRopeScan(Block block) {
        World world = block.getWorld();
        int maxHeight = world.getMaxHeight();
        Location location = block.getLocation().add(0, 1, 0);
        while (location.getY() <= maxHeight) {
            Block up = world.getBlockAt(location);
            if (!Config.elevatorRopeBlock.matches(up.getBlockData()) && !ElevatorController.MATERIAL.equals(up.getType())) {
                return null;
            }

            ElevatorController controller = ElevatorManager.elevators.get(up);
            if (controller != null) {
                return controller;
            }
            location.add(0, 1, 0);
        }
        return null;
    }

}
