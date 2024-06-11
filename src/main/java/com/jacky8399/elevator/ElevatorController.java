package com.jacky8399.elevator;

import com.jacky8399.elevator.utils.BlockUtils;
import com.jacky8399.elevator.utils.MathUtils;
import com.jacky8399.elevator.utils.PaperUtils;
import com.jacky8399.elevator.utils.PlayerUtils;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Comparator;
import java.util.*;
import java.util.logging.Level;

public class ElevatorController {
    public static final Material MATERIAL = Material.DROPPER;
    public static final double OMG_MAGICAL_DRAG_CONSTANT = 0.9800000190734863D;
    public static final int TRANSFORMATION_INTERVAL = 30;

    @NotNull
    World world;

    Block controller;

    @NotNull
    BoundingBox cabin;

    boolean maintenance;
    boolean moving;
    List<ElevatorBlock> movingBlocks;

    int ropeLength = 0;

    /** cabin entities and their Y offset relative to the cabin */
    Map<Entity, Double> cabinEntities;

    List<ElevatorFloor> floors = new ArrayList<>();
    int currentFloorIdx = 1;
    record ElevatorFloor(String name, int y, @Nullable Block source) {}

    Map<Integer, String> floorNameOverrides = new HashMap<>();

    /** How fast the elevator moves, in blocks per second */
    int speed = DEFAULT_SPEED;

    Vector velocity;
    long movementStartTick;
    long movementEndTick;
    int movementTime;

    Set<Block> managedDoors = new HashSet<>();

    public ElevatorController(@NotNull World world, @NotNull Block controller, @NotNull BoundingBox cabin) {
        this.world = world;
        this.controller = controller;
        this.cabin = cabin.clone();
    }

    // constructor for deserialization
    private ElevatorController(@NotNull BoundingBox cabin) {
        this.cabin = cabin.clone();
    }

//    private static final Vector BOUNDING_BOX_EPSILON = new Vector(0.05, 0.05, 0.05);
    private Collection<Entity> scanCabinEntities() {
        BoundingBox lenientBox = cabin.clone().expand(0.1, 0.1, 0.1);
        return world.getNearbyEntities(lenientBox, e -> {
            // these entities might be safe to teleport
            return e instanceof LivingEntity || e instanceof Hanging || e instanceof Vehicle || e instanceof Item;
        });
    }

    private Collection<Player> scanCabinPlayers() {
        BoundingBox lenientBox = cabin.clone().expand(0.1, 0.1, 0.1);
        var players = new ArrayList<Player>();
        for (var player : Bukkit.getOnlinePlayers()) {
            Location location = player.getLocation();
            if (lenientBox.contains(location.getX(), location.getY(), location.getZ())) {
                players.add(player);
            }
        }
        return players;
    }

    public void mobilize() {
        if (moving)
            return;
        setNearbyDoors(false);

        int minX = (int) cabin.getMinX();
        int minY = (int) cabin.getMinY();
        int minZ = (int) cabin.getMinZ();
        int maxX = (int) cabin.getMaxX();
        int maxY = (int) cabin.getMaxY();
        int maxZ = (int) cabin.getMaxZ();

        // scan cabin entities
        var entities = scanCabinEntities();
        this.cabinEntities = new HashMap<>();
        Location temp = controller.getLocation();
        for (var entity : entities) {
            double deltaY = onEnterCabin(entity, temp);
            this.cabinEntities.put(entity, deltaY);
        }

        virtualizeCabin(maxX, minX, maxZ, minZ, maxY, minY);

        // teleport display entities every 30 blocks travelled
        // to ensure that the display entities don't go out of tracking range
        int yDiff = movementTime * speed / 20;
        boolean down = velocity.getBlockY() < 0;
        int ticksPerInterval = 20 * TRANSFORMATION_INTERVAL / speed;
        var scheduler = Bukkit.getScheduler();
        Transformation movingTransformation = new Transformation(new Vector3f(0, down ? -TRANSFORMATION_INTERVAL : TRANSFORMATION_INTERVAL, 0),
                new Quaternionf(), new Vector3f(1), new Quaternionf());
        int points = yDiff / TRANSFORMATION_INTERVAL;
        for (int i = 0; i <= points; i++) {
            long delay = ticksPerInterval * (long) i; // WHY IS DELAY A LONG????
            if (i != 0) {
                boolean resetTransformation = i == points;
                // teleport first
                scheduler.runTaskLater(Elevator.INSTANCE, () -> {
                    if (Config.debug)
                        debug("Interpolation TP (reset: " + resetTransformation + ")");
                    Location location = new Location(null, 0, 0, 0);
                    ElevatorBlock.forEachDisplay(movingBlocks, display -> {
                            display.teleport(
                                        display.getLocation(location).add(0, down ? -TRANSFORMATION_INTERVAL : TRANSFORMATION_INTERVAL, 0));
//                                world.spawnParticle(Particle.BLOCK_MARKER, location, 0, Material.BARRIER.createBlockData());
                            if (resetTransformation) {
                                display.setInterpolationDuration(0);
                                display.setTransformation(MathUtils.DEFAULT_TRANSFORMATION);
                            }
                        }
                    );
                }, delay + (resetTransformation ? 0 : 2));
            }
            if (i != points) {
                scheduler.runTaskLater(Elevator.INSTANCE, () -> {
                    if (Config.debug)
                        debug("Interpolation Frame");
                    ElevatorBlock.forEachDisplay(movingBlocks, display -> {
                        display.setInterpolationDelay(0);
                        display.setInterpolationDuration(ticksPerInterval);
                        display.setTransformation(movingTransformation);
                    });
                }, delay + 2);
            } else {
                int finalDistance = (down ? -1 : 1) * yDiff % TRANSFORMATION_INTERVAL;
                Transformation finalTransformation = new Transformation(new Vector3f(0, finalDistance, 0),
                        new Quaternionf(), new Vector3f(1), new Quaternionf());
                int duration = movementTime - ticksPerInterval * points;
                scheduler.runTaskLater(Elevator.INSTANCE, () -> {
                    if (Config.debug)
                        debug("Final stretch: %d blocks for %d ticks".formatted(finalDistance, duration));
                    ElevatorBlock.forEachDisplay(movingBlocks, display -> {
                        display.setInterpolationDelay(0);
                        display.setInterpolationDuration(duration);
                        display.setTransformation(finalTransformation);
                    });
                }, delay + 2);
            }
        }

        moving = true;
    }

    /**
     * @return The Y offset of the entity
     */
    private double onEnterCabin(Entity entity, Location location) {
        entity.getLocation(location);
        // try to move players to the ground
        if (entity instanceof Player) {
            var rayTrace = world.rayTraceBlocks(location, new Vector(0, -1, 0), 2, FluidCollisionMode.ALWAYS, true);
            if (rayTrace != null) {
                location.setY(rayTrace.getHitPosition().getY());
                PaperUtils.teleport(entity, location);
            }
        } else if (entity instanceof ItemFrame itemFrame) {
            // turns out you can't make paintings fixed. too bad!
            itemFrame.setFixed(true);
        }

        return location.getY() - cabin.getMinY();
    }

    private void virtualizeCabin(int maxX, int minX, int maxZ, int minZ, int maxY, int minY) {
        int length = maxX - minX;
        int width = maxZ - minZ;
        int height = maxY - minY;

        int noOfBlocks = length * width * height + 2 * length * width + 1 /* rope */;
        movingBlocks = new ArrayList<>(noOfBlocks);
        var toBreak = new ArrayList<Block>();
        var toBreakNonSolid = new ArrayList<Block>();

        // offset Y by 2 tick since interpolation also begins in 2 tick
        float yPadding = (float) (velocity.getY() / 20 * 2);
        Block baseBlock = world.getBlockAt((int) cabin.getMinX(), (int) Math.round(cabin.getMinY()), (int) cabin.getMinZ());
        for (int j = minY; j < maxY; j++) {
            for (int i = minX; i < maxX; i++) {
                for (int k = minZ; k < maxZ; k++) {
                    Block block = world.getBlockAt(i, j, k);
                    Material type = block.getType();
                    if (block.equals(controller) || type == Material.AIR ||
                            Tag.DRAGON_IMMUNE.isTagged(type) || Tag.WITHER_IMMUNE.isTagged(type))
                        continue;

                    ElevatorBlock elevatorBlock = ElevatorBlock.spawnFor(world, baseBlock, block, block.getLocation().add(0, yPadding, 0));
                    movingBlocks.add(elevatorBlock);

                    if (type.isOccluding())
                        toBreak.add(block);
                    else
                        toBreakNonSolid.add(block);
                }
            }
        }
        // spawn virtual rope attached to the moving cabin
        Block ropeBlock = world.getBlockAt(controller.getX(), maxY, controller.getZ());
        movingBlocks.add(ElevatorBlock.spawnVirtualFor(world, baseBlock, ropeBlock, Config.elevatorRopeBlock, ropeBlock.getLocation().add(0, yPadding, 0)));

        Bukkit.getScheduler().runTask(Elevator.INSTANCE, () -> {
            for (Block block : toBreakNonSolid) {
                block.setType(Material.AIR, false);
            }
            for (Block block : toBreak) {
                block.setType(Material.AIR);
            }
        });
    }

    public void immobilize() {
        if (!moving)
            return;
        // round cabin
        cabin.resize(Math.round(cabin.getMinX()), Math.round(cabin.getMinY()), Math.round(cabin.getMinZ()),
                Math.round(cabin.getMaxX()), Math.round(cabin.getMaxY()), Math.round(cabin.getMaxZ()));
        if (Config.debug)
            debug("New cabin location: " + cabin);

        // solidify
        Location location = controller.getLocation();
        var oldMovingBlocks = List.copyOf(movingBlocks);
        Block baseBlock = world.getBlockAt((int) cabin.getMinX(), (int) Math.round(cabin.getMinY()), (int) cabin.getMinZ());
        for (ElevatorBlock block : movingBlocks) {
            if (block.display() != null) {
//                block.stand().getLocation(location);
                block.display().setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1), new Quaternionf()));

                if (block.blockState() == null) // don't place if virtual
                    continue;
                baseBlock.getLocation(location);
                location.add(block.pos().x(), block.pos().y(), block.pos().z());
                // I love floating point errors
                location.setY(Math.round(location.getY()));
                Block solidBlock = location.getBlock();
                BlockState state = block.blockState();
                if (solidBlock.getType() == Material.AIR) {
                    state.copy(solidBlock.getLocation()).update(true);
                } else {
                    BlockData toPlace = state.getBlockData();
                    world.playEffect(location, Effect.STEP_SOUND, toPlace.getMaterial());
                    world.dropItemNaturally(location, new ItemStack(toPlace.getPlacementMaterial()));
                }
            }
            // defer removal
        }
        movingBlocks.clear();
        Elevator.mustCleanupList.add(oldMovingBlocks);
        Runnable runnable = () -> {
            for (ElevatorBlock block : oldMovingBlocks) {
                block.remove();
            }
            Elevator.mustCleanupList.remove(oldMovingBlocks);
            if (Config.debug) {
                debug("Cleaned up " + oldMovingBlocks.size() + " entities");
            }
        };
        if (Elevator.disabling) { // remove now if shutting down
            runnable.run();
        } else {
            Bukkit.getScheduler().runTaskLater(Elevator.INSTANCE, runnable, 2);
        }


        double cabinMinY = cabin.getMinY();

        for (var entry : cabinEntities.entrySet()) {
            Entity entity = entry.getKey();
            double offset = entry.getValue();

            onLeaveCabin(entity, location, offset);
        }
        cabinEntities.clear();
        setNearbyDoors(true);

        velocity = null;
        moving = false;

        if (!Elevator.disabling)
            refreshRope();
    }

    private void onLeaveCabin(Entity entity, Location location, double offset) {
        entity.setGravity(true);
        entity.setFallDistance(0);
        // undo special treatment of players and hangables
        if (entity instanceof Player player) {
            PlayerUtils.unsetAllowFlight(player);
        } else if (entity instanceof ItemFrame itemFrame) {
            itemFrame.setFixed(false);
        }
        // prevent glitching through blocks
        entity.getLocation(location).setY(Math.round(cabin.getMinY() + offset) + 0.1);
        PaperUtils.teleport(entity, location);
    }

    private void setNearbyDoors(boolean state) {
        // funky way to toggle doors
        List<Block> visited = new ArrayList<>();
        BoundingBox doorBox = cabin.clone().expand(1, 1, 1);
        BlockUtils.forEachBlock(world, doorBox, block -> {
            BlockData data = block.getBlockData();
            boolean shouldManage = false;
            if (data instanceof Door door) {
                shouldManage = true;
                door.setOpen(state);
                door.setPowered(false);
                block.setBlockData(door, false);

                if (door.getHalf() == Bisected.Half.BOTTOM)
                    world.playSound(block.getLocation(), state ? Sound.BLOCK_WOODEN_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.5f, 1);
            } else if (data instanceof TrapDoor trapDoor) {
                shouldManage = true;
                trapDoor.setOpen(!state);
                trapDoor.setPowered(false);
                block.setBlockData(trapDoor, false);

                world.playSound(block.getLocation(), state ? Sound.BLOCK_WOODEN_TRAPDOOR_OPEN : Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, 0.5f, 1);
            }

            if (shouldManage) {
                visited.add(block);
                if (managedDoors.add(block))
                    ElevatorManager.managedDoors.put(block, this);
            }
        });
        // remove stale
        var stale = new HashSet<>(managedDoors);
        visited.forEach(stale::remove);
        for (var staleDoor : stale) {
            BlockData data = staleDoor.getBlockData();
            // unmanage if no longer a door
            if (!(data instanceof Door || data instanceof TrapDoor)) {
                ElevatorManager.managedDoors.remove(staleDoor);
                managedDoors.remove(staleDoor);
            }
        }
    }

    public void save() {
        if (Config.debug)
            debug("Saving " + controller);
        TileState state = (TileState) controller.getState();
        state.getPersistentDataContainer().set(ELEVATOR_CONTROLLER, STORAGE, this);
        state.update();
    }

    @Nullable
    public static ElevatorController load(Block block, TileState state) {
        if (block.getType() != MATERIAL)
            return null;
        try {
            ElevatorController controller = state.getPersistentDataContainer().get(ELEVATOR_CONTROLLER, STORAGE);
            if (controller == null)
                return null;
            if (Config.debug) {
                Elevator.LOGGER.log(Level.INFO, "Loaded " + block, new RuntimeException());
            }
            controller.controller = block;
            controller.world = block.getWorld();
            controller.scanFloors();
            controller.refreshRope();
            return controller;
        } catch (Exception ex) {
            Elevator.LOGGER.log(Level.SEVERE, "Failed to load ElevatorController: ", ex);
            return null;
        }
    }

    public void cleanUp() {
        immobilize();
        removeRope();
    }

    public void scanFloors() {
        removeRope();
        // clear previous floors from the global cache
        for (ElevatorFloor floor : floors) {
            if (floor.source != null)
                ElevatorManager.managedFloors.remove(floor.source);
        }

        int minX = (int) cabin.getMinX();
        int minY = (int) cabin.getMinY();
        int minZ = (int) cabin.getMinZ();
        int maxX = (int) cabin.getMaxX();
        int maxY = (int) cabin.getMaxY();
        int maxZ = (int) cabin.getMaxZ();

        record FloorScanner(Block block, BlockFace face) {}
        List<FloorScanner> scanners = new ArrayList<>();

        for (int j = minY; j < maxY; j++) {
            for (int i = minX; i < maxX; i++) {
                for (int k = minZ; k < maxZ; k++) {
                    Block block = world.getBlockAt(i, j, k);

                    if (block.getType() == Config.elevatorScannerBlock) {
                        if (Config.elevatorScannerDirectional && block.getBlockData() instanceof Directional directional) {
                            scanners.add(new FloorScanner(block, directional.getFacing()));
                        } else {
                            scanners.add(new FloorScanner(block, null));
                        }
                    }
                }
            }
        }

        Location temp = controller.getLocation();
        int shaftTop = world.getMaxHeight(), shaftBottom = world.getMinHeight();
        for (int i = minX; i < maxX; i++) {
            temp.setX(i);
            for (int k = minZ; k < maxZ; k++) {
                temp.setZ(k);

                temp.setY(minY - 1);
                int currentBottom = BlockUtils.rayTraceVertical(temp, false) + 1;
                shaftBottom = Math.max(shaftBottom, currentBottom);

                temp.setY(maxY);
                int currentTop = BlockUtils.rayTraceVertical(temp, true);
                shaftTop = Math.min(shaftTop, currentTop);
            }
        }

        floors.clear();
        if (scanners.isEmpty()) {
            currentFloorIdx = -1;
            // no indicators, use top and bottom of the shaft
            int topLevel = shaftTop - maxY + minY;
            int floor = 1;
            if (shaftBottom != minY)
                floors.add(new ElevatorFloor(String.valueOf(floor++), shaftBottom, null));
            floors.add(new ElevatorFloor(String.valueOf(floor), minY, null));
            currentFloorIdx = floor++ - 1;
            if (topLevel != shaftBottom && topLevel != minY)
                floors.add(new ElevatorFloor(String.valueOf(floor), topLevel, null));
            if (Config.debug)
                debug("No scanner, floors: " + floors);
        } else {
            record TempFloor(int cabinY, Block source, String name) {}
            List<TempFloor> tempFloors = new ArrayList<>();
            for (var scanner : scanners) {
                int y = scanner.block.getY();
                Location location = scanner.block.getRelative(scanner.face).getLocation();
                for (int i = shaftBottom + y - minY, end = shaftTop - (maxY - y); i <= end; i++) {
                    location.setY(i);
                    Block block = location.getBlock();
                    if (block.getType() == Config.elevatorFloorBlock) {
                        if (Config.debug)
                            debug("Found floor at " + block);

                        String floorName = floorNameOverrides.get(i); // look for a name override
                        // else look for a sign and use it as the floor name
                        if (floorName == null) {
                            for (BlockFace face : BlockFace.values()) {
                                Block side = block.getRelative(face);
                                if (Tag.SIGNS.isTagged(side.getType())) {
                                    Sign sign = (Sign) side.getState();

                                    floorName = sign.getSide(Side.FRONT).getLine(0);
                                }
                            }
                        }
                        tempFloors.add(new TempFloor(i - minY + y, block, floorName));
                    }
                }
            }
            tempFloors.sort(Comparator.comparingInt(tf -> -tf.cabinY));

            int closestFloorDist = world.getMaxHeight();
            int closestFloor = -1;
            for (int i = 0; i < tempFloors.size(); i++) {
                TempFloor floor = tempFloors.get(i);
                int floorY = floor.cabinY;
                String realName = floor.name != null ? floor.name : Config.getDefaultFloorName(tempFloors.size() - i - 1);
                floors.add(new ElevatorFloor(realName, floorY, floor.source));
                if (Math.abs(floorY - minY) < closestFloorDist) {
                    closestFloorDist = Math.abs(floorY - minY);
                    closestFloor = i;
                }
            }
            currentFloorIdx = closestFloor;

            if (Config.debug)
                debug("Floors: " + floors + ", current: " + currentFloorIdx);
        }
        var floorYs = new HashSet<Integer>();
        for (ElevatorFloor floor : floors) {
            floorYs.add(floor.y);
            // add new floors to the global cache
            if (floor.source != null)
                ElevatorManager.managedFloors.put(floor.source, this);
        }
        // remove unused floor overrides
        floorNameOverrides.keySet().retainAll(floorYs);
        refreshRope();
    }

    public void moveUp() {
//        scanFloors();

        int minY = (int) cabin.getMinY();

        // check current floor and upper floor in case the current floor changed
        ElevatorFloor currentFloor = floors.get(this.currentFloorIdx);
        if (currentFloor.y > minY) {
            moveTo(currentFloor.y);
            return;
        }

        if (this.currentFloorIdx != 0) {
            ElevatorFloor upperFloor = floors.get(this.currentFloorIdx - 1);
            moveTo(upperFloor.y);
        }
    }

    public void moveDown() {
//        scanFloors();
        int minY = (int) cabin.getMinY();

        // check current floor and lower floor in case the current floor changed
        ElevatorFloor currentFloor = floors.get(this.currentFloorIdx);
        if (currentFloor.y < minY) {
            moveTo(currentFloor.y);
            return;
        }

        if (this.currentFloorIdx != floors.size() - 1) {
            ElevatorFloor lowerFloor = floors.get(this.currentFloorIdx + 1);
            moveTo(lowerFloor.y);
        }
    }


    private static final int DEFAULT_SPEED = 5;
    private void moveTo(int y) {
        int yDiff = y - (int) cabin.getMinY();
        if (yDiff == 0 || moving)
            return;
        if (Config.debug)
            debug("Moving to " + y + " (" + yDiff + " blocks)");
        velocity = new Vector(0, yDiff > 0 ? speed : -speed, 0);
        movementStartTick = world.getGameTime();
        movementTime = Math.abs(yDiff) * 20 / speed;
        movementEndTick = movementStartTick + movementTime;

        mobilize();
        if (Config.debug)
            debug("Moving " + movingBlocks.size() + " blocks to y=" + y + " at velocity=" + velocity + " for " + movementTime + " ticks");
        doMove();
    }

    public void tick() {
        if (!controller.getChunk().isLoaded()) {
            Elevator.LOGGER.warning("Elevator (%d,%d,%d) is in an unloaded chunk, pretty scary stuff!".formatted(controller.getX(), controller.getY(), controller.getZ()));
            ElevatorManager.removeElevator(this); // don't save
            return;
        }
        if (controller.getType() != MATERIAL) {
            cleanUp();
            ElevatorManager.removeElevator(this); // don't save
            return;
        }

        if (maintenance) {
            if ((int) world.getGameTime() % 5 == 0) {
                BaseComponent msgMaintenance = TextComponent.fromLegacy(Config.msgMaintenance);
                for (Player player : scanCabinPlayers()) {
                    // I love spigot
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, msgMaintenance);
//                    player.sendActionBar(Config.msgMaintenance);
                }
            }
            return;
        }

        if (moving) {
            if (movementTime == 0) {
                immobilize();
            } else {
                doMove();
            }
        } else {
            // check for cooldown
            long ticksSinceMovementEnd = world.getGameTime() - movementEndTick;
            if (ticksSinceMovementEnd < Config.elevatorCooldown) {
                String cooldownMsg = Config.msgCooldown.replace("{cooldown}",
                        String.valueOf((int) ((Config.elevatorCooldown - ticksSinceMovementEnd) / 20)));
                BaseComponent cooldownComponent = TextComponent.fromLegacy(cooldownMsg);
                for (Player player : scanCabinPlayers()) {
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, cooldownComponent);
//                    player.sendActionBar(cooldownMsg);
                }
                return;
            }

            if ((world.getGameTime() & 1) == 0) {
                int currentY = (int) cabin.getMinY();
                // check for elevator calls
                for (int i = 0; i < floors.size(); i++) {
                    var floor = floors.get(i);
                    if (floor.source != null && floor.y != currentY) {
                        BlockData data = floor.source.getBlockData();
                        if (data.getMaterial() == Config.elevatorFloorBlock && floor.source.isBlockIndirectlyPowered()) {
                            if (Config.debug)
                                debug("Summoned by note block at (%d,%d,%d), going to y=%d".formatted(
                                        floor.source.getX(), floor.source.getY(), floor.source.getZ(), floor.y
                                ));
                            currentFloorIdx = i;
                            moveTo(floor.y);
                            break;
                        }
                    }
                }
            }
            // player check
            doPlayerTick();
        }
    }

    private void doPlayerTick() {
        var players = scanCabinPlayers();
        for (Player player : players) {
            if (currentFloorIdx == -1 || floors.isEmpty()) {
                ElevatorManager.playerElevatorCache.put(player, new ElevatorManager.PlayerElevator(this, 0));
//                player.sendActionBar(Config.msgNoFloors);
                continue;
            }

            if (player.isFlying() || player.isGliding())
                continue;

            boolean jumping = player.getVelocity().getY() > 0;
            var cache = ElevatorManager.playerElevatorCache.get(player);

            // check for scrolling
            int floorIdx = currentFloorIdx;

            String rawMessage;
            // TODO redo floor selection
            if (true || cache == null || cache.floorIdx() == currentFloorIdx) {
                if (jumping || player.isSneaking()) {
                    // move up or down a floor
                    int newFloor = currentFloorIdx + (jumping ? -1 : 1);
                    if (newFloor >= 0 && newFloor < floors.size()) {
                        // reset the selection
                        ElevatorManager.playerElevatorCache.remove(player);

                        currentFloorIdx = newFloor;
                        moveTo(floors.get(newFloor).y);
                        return;
                    }
                }
                ElevatorManager.playerElevatorCache.put(player,
                        new ElevatorManager.PlayerElevator(this, floorIdx));

                rawMessage = Config.msgCurrentFloorTemplate;
            } else {
//                floorIdx = cache.floorIdx();
//
//                ElevatorFloor floor = floorIdx >= 0 && floorIdx < floors.size() ? floors.get(floorIdx) : null;
//                if (floor != null && (jumping || player.isSneaking())) {
//                    // reset the selection
//                    ElevatorManager.playerElevatorCache.remove(player);
//
//                    currentFloorIdx = floorIdx;
//                    moveTo(floor.y);
//                }
//
//                rawMessage = Config.msgFloorTemplate;
            }

            String message = Config.getFloorMessage(rawMessage,
                    floorIdx != floors.size() - 1 ? floors.get(floorIdx + 1).name : null,
                    floors.get(floorIdx).name,
                    floorIdx != 0 ? floors.get(floorIdx - 1).name : null
            );

            BaseComponent component = TextComponent.fromLegacy(message);
//            player.sendActionBar(message);
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, component);
        }
    }

    void doMove() {
        movementTime--;
        long elapsed = world.getGameTime() - movementStartTick;
        boolean doTeleport = elapsed % 10 == 0;
        // sync armor stands
        Location temp = controller.getLocation();
        Vector delta = velocity.clone().multiply(1/20f);
//        Bukkit.getScheduler().runTask(Elevator.INSTANCE, () -> {
        BoundingBox lenientCabinBox = cabin.clone().expand(0.1);
            double cabinMinY = cabin.getMinY();

            double entityYVel = delta.getY();
            for (var iter = cabinEntities.entrySet().iterator(); iter.hasNext(); ) {
                var entry = iter.next();
                Entity entity = entry.getKey();
                double offset = entry.getValue();

                if (!entity.isValid()) {
                    iter.remove();
                    continue;
                }

                entity.getLocation(temp);
                if (!lenientCabinBox.contains(temp.getX(), temp.getY(), temp.getZ())) {
                    onLeaveCabin(entity, temp, offset);
                    iter.remove();
                    continue;
                }

                // check if still in cabin

                Vector velocity = entity.getVelocity();
                boolean mustTeleport = entity instanceof ItemFrame;

                if (doTeleport || mustTeleport) { // hanging entities cannot have velocity (I think)
                    // force synchronize location
                    double expectedY = cabinMinY + offset;
                    double actualY = temp.getY();
                    if (Math.abs(expectedY - actualY) > 0.5 || mustTeleport) {
                        if (Config.debug && !mustTeleport) {
                            debug(("Player: %s, offset: %.2f, cabin Y: %.2f, expected Y: %.4f\n" +
                                    "actual Y: %.4f (location: %.2f, velocity: %.2f)").formatted(
                                    entity.getName(), offset, cabinMinY, expectedY,
                                    actualY, temp.getY(), velocity.getY()
                            ));
                        }
                        temp.setY(expectedY);
                        PaperUtils.teleport(entity, temp);
                    }
                }
                entity.setGravity(false);
                if (entity instanceof Player player) {
                    PlayerUtils.setAllowFlight(player);
                    player.setFlying(false);
                }
                velocity.setY(entityYVel);
                entity.setVelocity(velocity);
            }
//        });
        cabin.shift(delta);

        refreshRope();
    }

    void refreshRope() {
        BlockData ropeMaterial = Config.elevatorRopeBlock;

        int expectedLength = Math.max(0, (int) Math.floor(controller.getY() - cabin.getMaxY() +
                (moving ? (velocity.getY() > 0 ? 0.2 : -0.2) : 0) // physical rope must lag behind attached rope
        ));
        int currentLength = ropeLength;
        if (expectedLength > currentLength) {
            // place more
            for (int i = currentLength; i < expectedLength; i++) {
                Block block = controller.getRelative(0, -i - 1, 0);
//                if (block.getType() == Material.AIR) {
                    block.setBlockData(ropeMaterial, false);
//                } else {
//                    removeRope();
//                    if (Config.debug) {
//                        debug("Dangerous rope placement at %s. Expected rope length: %d, current length: %d".formatted(block, expectedLength, currentLength));
//                    }
//                }
            }
        } else if (expectedLength < currentLength) {
            // remove excess
            for (int i = expectedLength; i < currentLength; i++) {
                Block block = controller.getRelative(0, -i - 1, 0);
//                if (block.getBlockData().matches(ropeMaterial)) {
                    block.setType(Material.AIR, false);
//                } else {
//                    removeRope();
//                    if (Config.debug) {
//                        debug("Dangerous rope removal at %s. Expected rope length: %d, current length: %d".formatted(block, expectedLength, currentLength));
//                    }
//                }
            }
        }
        ropeLength = expectedLength;
    }

    void removeRope() {
        for (int i = 1; i <= ropeLength; i++) {
            Block block = controller.getRelative(0, -i, 0);
            if (block.getBlockData().matches(Config.elevatorRopeBlock)) {
                block.setType(Material.AIR, false);
            }
        }
        ropeLength = 0;
    }

    void showOutline(Player player) {
        List<Display> cleanUp = new ArrayList<>();
        try {
            Location controllerLoc = controller.getLocation();
            List<BlockDisplay> cabinOutline = BlockUtils.createLargeOutline(world, cabin, player, Color.AQUA);
            List<BlockDisplay> controllerOutline = BlockUtils.createOutline(world, BoundingBox.of(controller), MATERIAL.createBlockData(), player, Color.WHITE);
            cleanUp.addAll(cabinOutline);
            cleanUp.addAll(controllerOutline);
            for (var floor : floors) {
                if (floor.source != null) {
//                    Location location = floor.source.getLocation().toCenterLocation();
                    cleanUp.addAll(BlockUtils.createOutline(world, BoundingBox.of(floor.source), floor.source.getBlockData(), player, Color.YELLOW));
//                    cleanUp.addAll(BlockUtils.createLine(location, controllerLoc.clone().subtract(location).toVector(),
//                            (float) controllerLoc.distance(location), player, Color.GRAY));
                }
            }
        } finally {
            Elevator.mustCleanup.addAll(cleanUp);
            Bukkit.getScheduler().runTaskLater(Elevator.INSTANCE, () -> {
                cleanUp.forEach(entity -> {
                    entity.remove();
                    Elevator.mustCleanup.remove(entity);
                });
            }, 10 * 20);
        }
    }
    
    private void debug(String message) {
        String realMessage = "[Elevator DEBUG] " + message;
        for (Player player : scanCabinPlayers()) {
            player.sendMessage(realMessage);
        }
        Bukkit.getConsoleSender().sendMessage(realMessage);
    }

    public static final NamespacedKey ELEVATOR_CONTROLLER = new NamespacedKey(Elevator.INSTANCE, "elevator_controller");
    private static final PersistentDataType<PersistentDataContainer, ElevatorController> STORAGE = new Storage();
    private static class Storage implements PersistentDataType<PersistentDataContainer, ElevatorController> {

        private static NamespacedKey key(String string) {
            return new NamespacedKey(Elevator.INSTANCE, string);
        }

        private static final NamespacedKey DATA_VERSION_KEY = key("data_version");
        private static final int DATA_VERSION = 1;
        private static final NamespacedKey CABIN_KEY = key("cabin");
        private static final NamespacedKey FLOOR_NAMES_KEY = key("floor_names");
        private static final NamespacedKey MAINTENANCE_KEY = key("maintenance");
        private static final NamespacedKey SPEED_KEY = key("speed");

        @NotNull
        @Override
        public Class<PersistentDataContainer> getPrimitiveType() {
            return PersistentDataContainer.class;
        }

        @NotNull
        @Override
        public Class<ElevatorController> getComplexType() {
            return ElevatorController.class;
        }

        @NotNull
        @Override
        public PersistentDataContainer toPrimitive(@NotNull ElevatorController complex, @NotNull PersistentDataAdapterContext context) {
            PersistentDataContainer container = context.newPersistentDataContainer();
            container.set(DATA_VERSION_KEY, INTEGER, DATA_VERSION);
            BoundingBox cabin = complex.cabin;
            int[] cabinCoords = new int[] {
                    (int) cabin.getMinX(), (int) cabin.getMinY(), (int) cabin.getMinZ(),
                    (int) cabin.getMaxX(), (int) cabin.getMaxY(), (int) cabin.getMaxZ()
            };
            container.set(CABIN_KEY, INTEGER_ARRAY, cabinCoords);
            if (!complex.floorNameOverrides.isEmpty()) {
                var floorNames = context.newPersistentDataContainer();
                complex.floorNameOverrides.forEach((y, name) -> floorNames.set(key(Integer.toString(y)), STRING, name));
                container.set(FLOOR_NAMES_KEY, TAG_CONTAINER, floorNames);
            }
            if (complex.maintenance) {
                container.set(MAINTENANCE_KEY, BOOLEAN, true);
            }
            if (complex.speed != DEFAULT_SPEED) {
                container.set(SPEED_KEY, INTEGER, complex.speed);
            }
            return container;
        }

        @NotNull
        @Override
        public ElevatorController fromPrimitive(@NotNull PersistentDataContainer primitive, @NotNull PersistentDataAdapterContext context) {
            int dataVersion = primitive.get(DATA_VERSION_KEY, INTEGER);

            return switch (dataVersion) {
                case 1 -> {
                    int[] cabinCoords = primitive.get(CABIN_KEY, INTEGER_ARRAY);
                    BoundingBox box = new BoundingBox(cabinCoords[0], cabinCoords[1], cabinCoords[2], cabinCoords[3], cabinCoords[4], cabinCoords[5]);
                    var controller = new ElevatorController(box);

                    var floorNames = primitive.get(FLOOR_NAMES_KEY, TAG_CONTAINER);
                    if (floorNames != null) {
                        for (NamespacedKey key : floorNames.getKeys()) {
                            int y = Integer.parseInt(key.getKey());
                            controller.floorNameOverrides.put(y, floorNames.get(key, STRING));
                        }
                    }
                    controller.maintenance = primitive.getOrDefault(MAINTENANCE_KEY, BOOLEAN, false);
                    controller.speed = primitive.getOrDefault(SPEED_KEY, INTEGER, DEFAULT_SPEED);
                    yield controller;
                }
                default -> throw new IllegalArgumentException("Invalid data version " + dataVersion);
            };
        }
    }
}
