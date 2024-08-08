package com.jacky8399.elevator;

import com.jacky8399.elevator.utils.BlockUtils;
import com.jacky8399.elevator.utils.MathUtils;
import com.jacky8399.elevator.utils.PaperUtils;
import com.jacky8399.elevator.utils.PlayerUtils;
import net.kyori.adventure.platform.bukkit.BukkitComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ComponentSerializer;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.TrapDoor;
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

import java.util.*;
import java.util.logging.Level;

import static com.jacky8399.elevator.Elevator.ADVNTR;

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

    /** The floors available, in ascending Y order */
    List<ElevatorFloor> floors = new ArrayList<>();
    int currentFloorIdx = 1;

    /**
     * An elevator floor
     * @param name The floor name
     * @param y Where the cabin would be at
     * @param source The source block of the floor
     */
    record ElevatorFloor(Component name, int y, @Nullable Block source) {}

    Map<Integer, Component> floorNameOverrides = new HashMap<>();

    /** How fast the elevator moves, in blocks per second */
    int speed = DEFAULT_SPEED;
    /**
     * The maximum amount of blocks the cabin can travel
     * (i.e. distance between controller.y and cabin.maxY)
     * If non-zero, overrides {@link Config#elevatorMaxHeight}
      */
    int maxHeight = 0;

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
        setNearbyDoors(cabin, false);

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

        for (var entry : cabinEntities.entrySet()) {
            Entity entity = entry.getKey();
            double offset = entry.getValue();

            onLeaveCabin(entity, location, offset);
        }
        cabinEntities.clear();
        setNearbyDoors(cabin, true);

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

    private void setNearbyDoors(BoundingBox cabin, boolean open) {
        // funky way to toggle doors
        List<Block> oldDoors = List.copyOf(managedDoors);
        Set<Block> visited = new HashSet<>();
        BoundingBox doorBox = cabin.clone().expand(1, 1, 1);
        BlockUtils.forEachBlock(world, doorBox, block -> {
            BlockData data = block.getBlockData();
            boolean shouldManage = BlockUtils.isDoorLike(data);
            if (shouldManage) {
                BlockUtils.setDoorLikeState(block, open);
                visited.add(block);
                if (managedDoors.add(block))
                    ElevatorManager.managedDoors.put(block, this);
            }
        });
        // remove stale managed doors that are no longer door blocks
        for (Block oldDoor : oldDoors) {
            if (visited.contains(oldDoor)) continue;
            BlockData data = oldDoor.getBlockData();
            // unmanage if no longer a door
            if (!(data instanceof Door || data instanceof TrapDoor)) {
                ElevatorManager.managedDoors.remove(oldDoor);
                managedDoors.remove(oldDoor);
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

        // 1. find scanners in the cabin
        // faces represents the directions to check for floors
        record FloorScanner(Block block, List<BlockFace> faces) {}
        List<FloorScanner> scanners = new ArrayList<>();

        BlockUtils.forEachBlock(world, cabin, block -> {
            BlockData blockData = block.getBlockData();
            if (blockData.getMaterial() == Config.elevatorScannerBlock) {
                scanners.add(new FloorScanner(block,
                        // check for directionality
                        Config.elevatorScannerDirectional && blockData instanceof Directional directional ?
                                // reject up and down
                                directional.getFacing().getModY() == 0 ?
                                        List.of(directional.getFacing()) :
                                        List.of() :
                                BlockUtils.XZ_CARDINALS
                ));
            }
        });

        // 2. find the top and bottom of the elevator shaft
        int maxHeight = this.maxHeight != 0 ? this.maxHeight : Config.elevatorMaxHeight;
        int shaftTop = controller.getY() - 1;
        int shaftBottom = controller.getY() - maxHeight - (maxY - minY); // also account for the height of the cabin
        for (int i = minX; i < maxX; i++) {
            for (int k = minZ; k < maxZ; k++) {
                int currentBottom = BlockUtils.rayTraceVertical(world, i, minY - 1, k, false, shaftBottom);
                shaftBottom = Math.max(shaftBottom, currentBottom);
                int currentTop = BlockUtils.rayTraceVertical(world, i, maxY, k, true, shaftTop);
                shaftTop = Math.min(shaftTop, currentTop);
                if (Config.debug)
                    debug("Shaft scanning for (%d,%d): from %d to %d".formatted(i, k, currentBottom, currentTop));
            }
        }
        shaftTop++; // make shaftTop exclusive, similar to maxY
        if (Config.debug)
            debug("Shaft result: from %d to %d".formatted(shaftTop, shaftBottom));

        // 3. scan for floors
        floors.clear();
        if (scanners.isEmpty()) {
            // 3a. No scanners: floors will be the top and bottom of the shaft
            currentFloorIdx = -1;
            int topLevel = shaftTop - maxY + minY;
            // 1/F
            if (shaftBottom != minY)
                floors.add(new ElevatorFloor(Messages.defaultFloorName(0), shaftBottom, null));
            // 2/F
            if (topLevel != shaftBottom) // add top of the shaft if necessary
                floors.add(new ElevatorFloor(Messages.defaultFloorName(1), topLevel, null));
            if (Config.debug)
                debug("No scanner, floors: " + floors);
        } else {
            // 3b. Has scanners: scan for floor blocks in each scanner's preferred direction
            record TempFloor(int cabinY, Block source, Component name) {}
            List<TempFloor> tempFloors = new ArrayList<>();
            for (FloorScanner scanner : scanners) {
                int y = scanner.block.getY();
                int start = shaftBottom + y - minY;
                int end = shaftTop - (maxY - y);
                if (Config.debug) {
                    debug("Scanner at (%d,%d,%d) will scan from %d to %d".formatted(scanner.block.getX(), scanner.block.getY(), scanner.block.getZ(), start, end));
                    if (CommandElevator.scanner != null) {
                        List<BlockDisplay> cleanup = BlockUtils.createLargeOutline(world, new BoundingBox(
                                scanner.block.getX(), start, scanner.block.getZ(),
                                scanner.block.getX() + 1, end + 1, scanner.block.getZ() + 1
                        ), CommandElevator.scanner, Color.ORANGE);
                        BlockUtils.ensureCleanUp(cleanup, 10 * 20);
                    }
                }
                for (BlockFace face : scanner.faces) {
                    Location location = scanner.block.getLocation().add(face.getModX(), face.getModY(), face.getModZ());
                    for (int i = start; i <= end; i++) {
                        location.setY(i);
                        Block block = location.getBlock();
                        if (block.getType() == Config.elevatorFloorBlock) {

                            Component floorName = floorNameOverrides.get(i); // look for a name override
                            // else look for a sign and use it as the floor name
                            if (floorName == null)
                                floorName = BlockUtils.findAdjacentSigns(block);
                            tempFloors.add(new TempFloor(i - minY + y, block, floorName));
                        }
                    }
                }
            }
            // sort by ascending Y
            tempFloors.sort(Comparator.comparingInt(tf -> tf.cabinY));
            // find the current floor
            int closestFloorDist = world.getMaxHeight();
            int closestFloor = -1;
            for (int i = 0; i < tempFloors.size(); i++) {
                TempFloor floor = tempFloors.get(i);
                int floorY = floor.cabinY;
                Component realName = floor.name != null ? floor.name : Messages.defaultFloorName(i);
                floors.add(new ElevatorFloor(realName, floorY, floor.source));
                if (Math.abs(floorY - minY) < closestFloorDist) {
                    closestFloorDist = Math.abs(floorY - minY);
                    closestFloor = i;
                }
            }
            currentFloorIdx = closestFloor;
        }
        var floorYs = new HashSet<Integer>();
        for (ElevatorFloor floor : floors) {
            floorYs.add(floor.y);
            // add new floor sources to the global cache
            if (floor.source != null)
                ElevatorManager.managedFloors.put(floor.source, this);
            // manage nearby doors
            int shiftY = floor.y - (int) cabin.getMinY();
            BoundingBox expectedCabin = cabin.clone().shift(0, shiftY, 0);
            setNearbyDoors(expectedCabin, shiftY == 0); // open for current floor
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
        if (currentFloor.y < minY) {
            moveTo(currentFloor.y);
            return;
        }

        if (this.currentFloorIdx != floors.size() - 1) {
            ElevatorFloor upperFloor = floors.get(this.currentFloorIdx + 1);
            moveTo(upperFloor.y);
        }
    }

    public void moveDown() {
//        scanFloors();
        int minY = (int) cabin.getMinY();

        // check current floor and lower floor in case the current floor changed
        ElevatorFloor currentFloor = floors.get(this.currentFloorIdx);
        if (currentFloor.y > minY) {
            moveTo(currentFloor.y);
            return;
        }

        if (this.currentFloorIdx != 0) {
            ElevatorFloor lowerFloor = floors.get(this.currentFloorIdx - 1);
            moveTo(lowerFloor.y);
        }
    }


    private static final int DEFAULT_SPEED = 5;
    private void moveTo(int y) {
        if (maintenance)
            return;
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
                Component msgMaintenance = Messages.msgMaintenance;
                for (Player player : scanCabinPlayers()) {
                    ADVNTR.player(player).sendActionBar(msgMaintenance);
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
                Component cooldownMsg = Messages.renderMessage(Messages.msgCooldown,
                        Map.of("cooldown", Component.text((int) ((Config.elevatorCooldown - ticksSinceMovementEnd) / 20))));
                for (Player player : scanCabinPlayers()) {
                    ADVNTR.player(player).sendActionBar(cooldownMsg);
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

            var audience = ADVNTR.player(player);
            Component template;
            // TODO redo floor selection
            if (true || cache == null || cache.floorIdx() == currentFloorIdx) {
                if (jumping || player.isSneaking()) {
                    // move up or down a floor
                    int newFloor = currentFloorIdx + (jumping ? 1 : -1);
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

                template = Messages.msgCurrentFloor;
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

            audience.sendActionBar(Messages.floorMessage(template,
                    floorIdx != 0 ? floors.get(floorIdx - 1).name : null,
                    floors.get(floorIdx).name,
                    floorIdx != floors.size() - 1 ? floors.get(floorIdx + 1).name : null
            ));
        }
    }

    void doMove() {
        boolean debug = Config.debug;
        movementTime--;
        long elapsed = world.getGameTime() - movementStartTick;
        boolean doTeleport = elapsed % 10 == 0;
        // sync armor stands
        Location temp = controller.getLocation();
        Vector delta = velocity.clone().multiply(1/20f);
        // Y box should be really large to account for lagging players
        BoundingBox veryLenientBox = cabin.clone().expand(1.5, world.getMaxHeight(), 1.5);
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
            // check if still in cabin
            if (!veryLenientBox.contains(temp.getX(), temp.getY(), temp.getZ())) {
                onLeaveCabin(entity, temp, offset);
                iter.remove();
                continue;
            }

            Vector velocity = entity.getVelocity();
            boolean mustTeleport = !(entity instanceof Player);

            double y = temp.getY();
            double expectedY = cabinMinY + offset;
            temp.setY(expectedY);
            if (doTeleport || mustTeleport) { // hanging entities cannot have velocity (I think)
                // force synchronize location
                if (Math.abs(expectedY - (y + velocity.getY())) > 0.5 || mustTeleport) {
                    if (debug && !mustTeleport) {
                        debug(("Player: %s, offset: %.2f, cabin Y: %.2f, expected Y: %.4f\n" +
                                "actual Y: %.4f (location: %.2f, velocity: %.2f)").formatted(
                                entity.getName(), offset, cabinMinY, expectedY,
                                y, temp.getY(), velocity.getY()
                        ));
                    }
                    if (entity instanceof LivingEntity) { // LivingEntities interpolate their movement over 3 ticks
                        temp.setY(cabinMinY + offset + velocity.getY() * 3);
                    }
                    PaperUtils.teleport(entity, temp);
                }
            }
            entity.setFallDistance(0);
            entity.setGravity(false);
            if (entity instanceof Player player) {
                PlayerUtils.setAllowFlight(player);
                player.setFlying(false);
            }
            velocity.setY(entityYVel);
            entity.setVelocity(velocity);
            if (debug)
                world.spawnParticle(Particle.CRIT, temp, 0, 0, 0, 0, 0);
        }
        cabin.shift(delta);

        if (debug) {
            world.spawnParticle(Particle.COMPOSTER, cabin.getMinX(), cabin.getMinY(), cabin.getMinZ(), 1);
            world.spawnParticle(Particle.COMPOSTER, cabin.getMaxX(), cabin.getMaxY(), cabin.getMaxZ(), 1);
        }

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
                Location hologramLocation;
                if (floor.source != null) {
                    cleanUp.addAll(BlockUtils.createOutline(world, BoundingBox.of(floor.source), floor.source.getBlockData(), player, Color.YELLOW));
                    hologramLocation = floor.source.getLocation().add(0.5, 1.5, 0.5);
                } else {
                    hologramLocation = controllerLoc.clone();
                    hologramLocation.setY(floor.y);
                }
                cleanUp.add(world.spawn(hologramLocation, TextDisplay.class, display -> {
                    display.setSeeThrough(true);
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setDefaultBackground(true);
                    display.setText(BukkitComponentSerializer.legacy().serialize(floor.name));
                }));
            }
        } finally {
            BlockUtils.ensureCleanUp(cleanUp, 10 * 20);
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
        private static final int DATA_VERSION = 2;
        private static final NamespacedKey CABIN_KEY = key("cabin");
        private static final NamespacedKey FLOOR_NAMES_KEY = key("floor_names");
        private static final NamespacedKey MAINTENANCE_KEY = key("maintenance");
        private static final NamespacedKey SPEED_KEY = key("speed");
        private static final NamespacedKey MAX_DISTANCE_KEY = key("max_distance");

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
                complex.floorNameOverrides.forEach((y, nameComponent) ->
                        floorNames.set(key(Integer.toString(y)), STRING, GsonComponentSerializer.gson().serialize(nameComponent)));
                container.set(FLOOR_NAMES_KEY, TAG_CONTAINER, floorNames);
            }
            if (complex.maintenance) {
                container.set(MAINTENANCE_KEY, BOOLEAN, true);
            }
            if (complex.speed != DEFAULT_SPEED) {
                container.set(SPEED_KEY, INTEGER, complex.speed);
            }
            if (complex.maxHeight != 0) {
                container.set(MAX_DISTANCE_KEY, INTEGER, complex.maxHeight);
            }
            return container;
        }

        @NotNull
        @Override
        public ElevatorController fromPrimitive(@NotNull PersistentDataContainer primitive, @NotNull PersistentDataAdapterContext context) {
            Integer dataVersion = primitive.get(DATA_VERSION_KEY, INTEGER);

            return switch (dataVersion) {
                case null -> throw new IllegalArgumentException("Missing data version");
                case 1 -> fromPrimitiveV2(primitive, LegacyComponentSerializer.legacySection());
                case 2 -> fromPrimitiveV2(primitive, GsonComponentSerializer.gson());
                default -> throw new IllegalArgumentException("Invalid data version " + dataVersion);
            };
        }

        private static ElevatorController fromPrimitiveV2(PersistentDataContainer primitive,
                                                          ComponentSerializer<? super Component, ? extends Component, String> serializer) {
            int[] cabinCoords = Objects.requireNonNull(primitive.get(CABIN_KEY, INTEGER_ARRAY));
            BoundingBox box = new BoundingBox(cabinCoords[0], cabinCoords[1], cabinCoords[2], cabinCoords[3], cabinCoords[4], cabinCoords[5]);
            var controller = new ElevatorController(box);

            var floorNames = primitive.get(FLOOR_NAMES_KEY, TAG_CONTAINER);
            if (floorNames != null) {
                for (NamespacedKey key : floorNames.getKeys()) {
                    int y = Integer.parseInt(key.getKey());
                    String legacy = Objects.requireNonNull(floorNames.get(key, STRING));
                    controller.floorNameOverrides.put(y, serializer.deserialize(legacy));
                }
            }
            controller.maintenance = primitive.getOrDefault(MAINTENANCE_KEY, BOOLEAN, false);
            controller.speed = primitive.getOrDefault(SPEED_KEY, INTEGER, DEFAULT_SPEED);
            controller.maxHeight = primitive.getOrDefault(MAX_DISTANCE_KEY, INTEGER, 0);
            return controller;
        }
    }
}
