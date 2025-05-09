package com.jacky8399.elevator;

import com.jacky8399.elevator.animation.ElevatorAnimation;
import com.jacky8399.elevator.animation.PacketTransformationAnimation;
import com.jacky8399.elevator.animation.TransformationAnimation;
import com.jacky8399.elevator.utils.*;
import net.kyori.adventure.platform.bukkit.BukkitComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.ComponentSerializer;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static com.jacky8399.elevator.Elevator.ADVNTR;
import static com.jacky8399.elevator.Elevator.LOGGER;

public class ElevatorController {
    public static final Material MATERIAL = Material.DROPPER;
    public static final double OMG_MAGICAL_DRAG_CONSTANT = 0.9800000190734863D;

    @NotNull
    final World world;
    final Block controller;

    // array of chunk keys required to be loaded for this elevator to be considered active
    long[] chunksRequired;
    // whether a floor scan is scheduled when this elevator is next active
    boolean floorScanScheduled;

    @NotNull
    private BoundingBox cabin;

    boolean maintenance;
    boolean moving;
    List<ElevatorBlock> movingBlocks;

    int ropeLength = 0;

    /** cabin entities and their Y offset relative to the cabin */
    Map<Entity, Double> cabinEntities;

    /** The floors available, in ascending Y order */
    List<ElevatorFloor> floors = new ArrayList<>();
    int currentFloorIdx = 0;
    boolean signStale = true; // used by updateSign()

    /**
     * An elevator floor
     * @param name The floor name
     * @param y Where the cabin would be at
     * @param source The source block of the floor
     */
    public record ElevatorFloor(@NotNull Component name, int y, @Nullable Block source) {
        public List<Display> createDisplay(ElevatorController controller, Player player, Color color) {
            var cleanUp = new ArrayList<Display>();
            World world = player.getWorld();
            Location hologramLocation;
            if (source != null) {
                cleanUp.addAll(BlockUtils.createOutline(world, BoundingBox.of(source), source.getBlockData(), player, color));
                hologramLocation = source.getLocation().add(0.5, 1.5, 0.5);
            } else {
                hologramLocation = controller.getController().getLocation();
                hologramLocation.setY(y);
            }
            cleanUp.add(world.spawn(hologramLocation, TextDisplay.class, display -> {
                display.setSeeThrough(true);
                display.setBillboard(Display.Billboard.CENTER);
                display.setText(BukkitComponentSerializer.legacy().serialize(name));
                display.setGlowing(true);
                display.setGlowColorOverride(color);
            }));
            return cleanUp;
        }
    }

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

    ElevatorAnimation animation;

    Set<Block> managedDoors = new HashSet<>();
    Set<Block> managedSigns = new LinkedHashSet<>();

    public ElevatorController(@NotNull World world, @NotNull Block controller, @NotNull BoundingBox cabin) {
        this.world = world;
        this.controller = controller;
        this.cabin = cabin.clone();
        chunksRequired = calcChunksRequired(this.cabin);
    }

    // constructor for deserialization
    private ElevatorController(@NotNull BoundingBox cabin) {
        this.world = loadingBlock.getWorld();
        this.controller = loadingBlock;
        this.cabin = cabin.clone();
        chunksRequired = calcChunksRequired(this.cabin);
        if (Config.debug) {
            LOGGER.info("Elevator at (%d,%d,%d) requires [%s] to be loaded".formatted(
                    controller.getX(), controller.getY(), controller.getZ(),
                    ChunkPos.toString(chunksRequired)
            ));
        }
    }

    public Block getController() {
        return controller;
    }

    @NotNull
    public BoundingBox getCabin() {
        return cabin.clone();
    }

    @NotNull
    public BoundingBox getScanCabin() {
        return cabin.clone().expand(0.1, 0.1, 0.1, 0.1, -0.1, 0.1);
    }

    @NotNull
    public BoundingBox resizeCabin(double x1, double y1, double z1, double x2, double y2, double z2) {
        BoundingBox boundingBox = cabin.resize(x1, y1, z1, x2, y2, z2);
        chunksRequired = calcChunksRequired(boundingBox);
        return boundingBox;
    }

    private static long[] calcChunksRequired(BoundingBox boundingBox) {
        return ChunkPos.fromArea((((int) boundingBox.getMinX()) - 1) >> 4, (((int) boundingBox.getMaxX()) + 1) >> 4,
                (((int) boundingBox.getMinZ()) - 1) >> 4, (((int) boundingBox.getMaxZ()) + 1) >> 4);
    }

    public boolean isActive() {
        for (long chunkKey : chunksRequired) {
            if (!ChunkPos.isLoaded(world, chunkKey))
                return false;
        }
        return true;
    }

    private Collection<Entity> scanCabinEntities() {
        BoundingBox lenientBox = getScanCabin();
        return world.getNearbyEntities(lenientBox, e -> {
            if (ElevatorBlock.excludedEntities.contains(e))
                return false;

            if (e instanceof Player player && player.getGameMode() == GameMode.SPECTATOR)
                return false;
            if (e instanceof LivingEntity livingEntity) {
                Location location = livingEntity.getLocation();
                Location eyeLocation = livingEntity.getEyeLocation();
                // ensure that they aren't merely touching the elevator
                return lenientBox.contains(location.getX(), location.getY(), location.getZ()) ||
                        lenientBox.contains(eyeLocation.getX(), eyeLocation.getY(), eyeLocation.getZ());
            }
            // these entities might be safe to teleport
            return e instanceof Vehicle || e instanceof Item;
        });
    }

    private Collection<Player> scanCabinPlayers() {
        BoundingBox lenientBox = getScanCabin();
        var players = new ArrayList<Player>();
        for (var player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) continue;
            Location location = player.getLocation();
            if (lenientBox.contains(location.getX(), location.getY(), location.getZ())) {
                players.add(player);
            }
        }
        return players;
    }

    private void mobilize() {
        if (moving)
            return;

        try {
            applyInteractions(cabin, false);

            int minX = (int) cabin.getMinX();
            int minY = (int) cabin.getMinY();
            int minZ = (int) cabin.getMinZ();
            int maxX = (int) cabin.getMaxX();
            int maxY = (int) cabin.getMaxY();
            int maxZ = (int) cabin.getMaxZ();


            var blockDestroyer = virtualizeCabin(maxX, minX, maxZ, minZ, maxY, minY);

            // I love mutability
            animation = Elevator.SCHEDULER.mobilize(this, List.copyOf(movingBlocks), movementTime, speed, velocity.clone());

            // scan cabin entities
            var entities = scanCabinEntities();
            this.cabinEntities = new HashMap<>();
            Location temp = controller.getLocation();
            for (var entity : entities) {
                double deltaY = onEnterMovingCabin(entity, temp);
                this.cabinEntities.put(entity, deltaY);
            }

            blockDestroyer.run();
            moving = true;
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Failed to mobilize elevator at (%d, %d, %d)".formatted(controller.getX(), controller.getY(), controller.getZ()), ex);
            immobilize();
        }
    }

    /**
     * Returns a Runnable that actually removes the blocks from the world
     */
    private Runnable virtualizeCabin(int maxX, int minX, int maxZ, int minZ, int maxY, int minY) {
        int length = maxX - minX;
        int width = maxZ - minZ;
        int height = maxY - minY;

        int noOfBlocks = length * width * height + 2 * length * width + 1 /* rope */;
        movingBlocks = new ArrayList<>(noOfBlocks);
        var toBreak = new ArrayList<Block>(noOfBlocks);
        var toBreakNonSolid = new ArrayList<Block>(noOfBlocks);

        // offset Y by 2 tick since interpolation also begins in 2 tick
        float yPadding = (float) (velocity.getY() / 20 * TransformationAnimation.TRANSFORMATION_PADDING_TICKS);
        Vector padding = new Vector(0, yPadding, 0);
        Block baseBlock = world.getBlockAt((int) cabin.getMinX(), (int) Math.round(cabin.getMinY()), (int) cabin.getMinZ());
        Set<Integer> noCollisionYs = Set.of(minY, maxY - 1); // don't spawn shulkers at the top and bottom of cabins to avoid faulty collision
        for (int j = minY; j < maxY; j++) {
            for (int i = minX; i < maxX; i++) {
                for (int k = minZ; k < maxZ; k++) {
                    Block block = world.getBlockAt(i, j, k);
                    Material type = block.getType();
                    if (block.equals(controller) || type == Material.AIR ||
                            Tag.DRAGON_IMMUNE.isTagged(type) || Tag.WITHER_IMMUNE.isTagged(type))
                        continue;

                    ElevatorBlock elevatorBlock = ElevatorBlock.spawnFor(world, baseBlock, noCollisionYs, block, padding);
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

        return () -> {
            for (Block block : toBreakNonSolid) {
                block.setType(Material.AIR, false);
            }
            for (Block block : toBreak) {
                block.setType(Material.AIR);
            }
        };
    }

    public void immobilize() {
        if (!moving)
            return;
        // round cabin
        cabin.resize(Math.round(cabin.getMinX()), Math.round(cabin.getMinY()), Math.round(cabin.getMinZ()),
                Math.round(cabin.getMaxX()), Math.round(cabin.getMaxY()), Math.round(cabin.getMaxZ()));
        if (Config.debug)
            debug("New cabin location: " + cabin);

        Location location = controller.getLocation();

        for (var entry : cabinEntities.entrySet()) {
            Entity entity = entry.getKey();
            double offset = entry.getValue();

            try {
                onLeaveMovingCabin(entity, location, offset);
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, "Failed to handle entity leaving the cabin\nEntity: " + entity, ex);
            }
        }
        cabinEntities.clear();

        try {
            if (animation != null)
                animation.immobilize(this);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Elevator scheduler " + animation + " failed to clean up at (%d, %d, %d)"
                    .formatted(controller.getX(), controller.getY(), controller.getZ()), ex);
        }

        // solidify
        var oldMovingBlocks = List.copyOf(movingBlocks);
        Block baseBlock = world.getBlockAt((int) cabin.getMinX(), (int) Math.round(cabin.getMinY()), (int) cabin.getMinZ());
        for (ElevatorBlock block : movingBlocks) {
            if (block.display() != null) {
//                block.stand().getLocation(location);
//                block.display().setTransformation(MathUtils.DEFAULT_TRANSFORMATION);

                if (block.blockState() == null) // don't place if virtual
                    continue;
                baseBlock.getLocation(location);
                location.add(block.pos().x(), block.pos().y(), block.pos().z());
                location.setY(Math.round(location.getY()));
                Block solidBlock = location.getBlock();
                BlockState state = block.blockState();
                if (solidBlock.getType() == Material.AIR) {
                    state.copy(solidBlock.getLocation()).update(true);
                } else {
                    BlockUtils.dropItems(world, location, state);
                }
            }
        }
        // defer removal
        movingBlocks.clear();
        Elevator.mustCleanupList.add(oldMovingBlocks);
        Runnable runnable = () -> {
            for (ElevatorBlock block : oldMovingBlocks) {
                block.remove();
            }
            Elevator.mustCleanupList.remove(oldMovingBlocks);
        };
        if (Elevator.disabling) { // remove now if shutting down
            runnable.run();
        } else {
            Bukkit.getScheduler().runTaskLater(Elevator.INSTANCE, runnable, 2);
        }

        try {
            applyInteractions(cabin, true);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Failed to toggle doors", ex);
        }

        velocity = null;
        moving = false;

        updateSigns();

        if (!Elevator.disabling)
            refreshRope();
    }

    /**
     * @return The Y offset of the entity
     */
    private double onEnterMovingCabin(Entity entity, Location location) {
        entity.getLocation(location);
        if (!moving) {
            // try to move players to the ground
            if (entity instanceof LivingEntity) {
                var rayTrace = world.rayTraceBlocks(location, new Vector(0, -1, 0), 2, FluidCollisionMode.ALWAYS, true);
                if (rayTrace != null) {
                    location.setY(rayTrace.getHitPosition().getY());
                    PaperUtils.teleport(entity, location);
                }
            } else if (entity instanceof ItemFrame itemFrame) {
                // turns out you can't make paintings fixed. too bad!
                itemFrame.setFixed(true);
            }
        }
        animation.onEnterCabin(this, entity);

        if (entity instanceof Player player) {
            ElevatorManager.playerMovingElevator.put(player, new ElevatorManager.PlayerMovingElevator(this));
        }

        return location.getY() - cabin.getMinY();
    }

    public void onLeaveMovingCabin(Entity entity, Location temp, double offset) {
        entity.setGravity(true);
        entity.setFallDistance(0);
        // undo special treatment of players and hangables
        if (entity instanceof Player player) {
            PlayerUtils.unsetAllowFlight(player);
        } else if (entity instanceof ItemFrame itemFrame) {
            itemFrame.setFixed(false);
        }
        // prevent glitching through blocks
        entity.getLocation(temp).setY(Math.round(cabin.getMinY() + offset) + 0.1);
        PaperUtils.teleport(entity, temp);

        animation.onLeaveCabin(this, entity);

        if (entity instanceof Player player) {
            ElevatorManager.playerMovingElevator.remove(player);
        }
    }

    public void cleanUp() {
        immobilize();
        removeRope();
    }

    public FloorScan scanFloors() {
        long startTime = System.nanoTime();
        if (!isActive()) {
            floorScanScheduled = true;
            if (Config.debug) {
                String unloaded = Arrays.stream(chunksRequired)
                        .filter(l -> !ChunkPos.isLoaded(world, l))
                        .mapToObj(ChunkPos::toString)
                        .collect(Collectors.joining(", "));
                LOGGER.info("Floor scanning for %d,%d,%d (in chunk %d,%d) deferred due to unloaded chunks %s".formatted(
                        controller.getX(), controller.getY(), controller.getZ(),
                        controller.getX() >> 4, controller.getZ() >> 4,
                        unloaded
                ));
            }
            return FloorScan.Deferred.INSTANCE;
        }
        removeRope();
        // clear previous floors from the global cache
        for (ElevatorFloor floor : floors) {
            if (floor.source != null)
                ElevatorManager.managedFloors.remove(floor.source);
        }
        for (Block managedDoor : managedDoors) {
            ElevatorManager.managedDoors.remove(managedDoor);
        }
        managedDoors.clear();
        managedSigns.clear();

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
                if (BlockUtils.unloadCatcher(world, i, k))
                    continue;

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

        BoundingBox scanBox = new BoundingBox(minX, shaftBottom, minY, maxX, shaftTop, maxY);

        // 3. scan for floors
        floors.clear();
        if (scanners.isEmpty()) {
            // 3a. No scanners: floors will be the top and bottom of the shaft
            // fail fast if disallowed
            if (!Config.elevatorScannerAllowScannerless) {
                return FloorScan.NoScannerDisallowed.INSTANCE;
            }
            currentFloorIdx = 0;
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
                    debug("Scanner at (%d,%d,%d) will scan from %d to %d in directions: %s"
                            .formatted(scanner.block.getX(), scanner.block.getY(), scanner.block.getZ(),
                                    start, end, scanner.faces));
                    if (CommandElevator.scanner != null) {
                        List<BlockDisplay> cleanup = BlockUtils.createLargeOutline(world, new BoundingBox(
                                scanner.block.getX(), start, scanner.block.getZ(),
                                scanner.block.getX() + 1, end + 1, scanner.block.getZ() + 1
                        ), CommandElevator.scanner, Color.ORANGE);
                        BlockUtils.ensureCleanUp(cleanup, 10 * 20);
                    }
                }
                for (BlockFace face : scanner.faces) {
                    Location location = scanner.block.getLocation().add(face.getModX(), 0, face.getModZ());
                    if (BlockUtils.unloadCatcher(world, location.getBlockX(), location.getBlockZ()))
                        continue;
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
//        var floorYs = new HashSet<Integer>();
        for (ElevatorFloor floor : floors) {
//            floorYs.add(floor.y);
            // add new floor sources to the global cache
            if (floor.source != null)
                ElevatorManager.managedFloors.put(floor.source, this);
            // manage nearby doors
            int shiftY = floor.y - (int) cabin.getMinY();
            BoundingBox expectedCabin = cabin.clone().shift(0, shiftY, 0);
            applyInteractions(expectedCabin, shiftY == 0); // open for current floor
        }
//        // remove unused floor overrides
//        floorNameOverrides.keySet().retainAll(floorYs);
        refreshRope();
        if (Config.debug) {
            debug("Floor scanning took " + (System.nanoTime() - startTime) / 1000000 + "ms");
        }
        return scanners.isEmpty() ?
                new FloorScan.NoScanner(scanBox, List.copyOf(floors), currentFloorIdx) :
                new FloorScan.Scanner(scanBox, List.copyOf(floors), currentFloorIdx);
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
            this.currentFloorIdx++;
            ElevatorFloor upperFloor = floors.get(this.currentFloorIdx);
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
            this.currentFloorIdx--;
            ElevatorFloor lowerFloor = floors.get(this.currentFloorIdx);
            moveTo(lowerFloor.y);
        }
    }


    private static final int DEFAULT_SPEED = 5;
    private void moveTo(int y) {
        if (!isActive())
            return;

        if (maintenance)
            return;
        int originalY = (int) cabin.getMinY();
        int yDiff = y - originalY;
        if (yDiff == 0 || moving)
            return;
        velocity = new Vector(0, yDiff > 0 ? speed : -speed, 0);
        movementStartTick = world.getGameTime();
        movementTime = Math.round(Math.abs(yDiff) * 20f / speed);
        movementEndTick = movementStartTick + movementTime;

        mobilize();
        if (Config.debug) {
            debug("Moving %d display entities from Y = %d to Y = %d (%d blocks) at velocity (%.2f, %.2f, %.2f) for %d ticks"
                    .formatted(movingBlocks.size(), originalY, y, yDiff, velocity.getX(), velocity.getY(), velocity.getZ(), movementTime));
        }
        doMovementTick();
    }

    public void tick() {
        if (!world.isChunkLoaded(controller.getX() >> 4, controller.getZ() >> 4)) {
            LOGGER.warning("Elevator (%d,%d,%d) is in an unloaded chunk, pretty scary stuff!".formatted(controller.getX(), controller.getY(), controller.getZ()));
            ElevatorManager.removeElevator(this); // don't save
            return;
        }
        if (controller.getType() != MATERIAL) {
            cleanUp();
            ElevatorManager.removeElevator(this); // don't save
            return;
        }

        if (!isActive())
            return;

        if (floorScanScheduled) {
            floorScanScheduled = false;
            scanFloors();
            if (Config.debug) {
                LOGGER.info("Deferred scanning for %d,%d,%d (in chunk %d,%d) executed".formatted(
                        controller.getX(), controller.getY(), controller.getZ(),
                        controller.getX() >> 4, controller.getZ() >> 4
                ));
            }
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
                doMovementTick();
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
                        // check if chunk is loaded first
                        if (!world.isChunkLoaded(floor.source.getX() >> 4, floor.source.getZ() >> 4))
                            throw new RuntimeException(floor.source + " chunk should be loaded");
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
            doIdleTick();
        }
    }

    private void doIdleTick() {
        var players = scanCabinPlayers();
        for (Player player : players) {
            var audience = ADVNTR.player(player);
            if (currentFloorIdx == -1 || floors.isEmpty()) {
                ElevatorManager.playerElevatorCache.put(player, new ElevatorManager.PlayerElevator(this, 0));
                audience.sendActionBar(Messages.msgNoFloors);
                continue;
            }

            if (player.isFlying() || player.isGliding())
                continue;

            boolean jumping = player.getVelocity().getY() > 0;
            var cache = ElevatorManager.playerElevatorCache.get(player);

            // check for scrolling
            int floorIdx = currentFloorIdx;

            Component template;
            // TODO redo floor selection
            if (cache == null || cache.controller() != this // previous selection doesn't apply
                    || cache.floorDiff() == 0) {
                if (jumping || player.isSneaking()) {
                    // move up or down a floor
                    int newFloor = currentFloorIdx + (jumping ? 1 : -1);
                    if (newFloor >= 0 && newFloor < floors.size()) {
                        // reset all player selections
                        for (Player cabinPlayer : scanCabinPlayers()) {
                            ElevatorManager.playerElevatorCache.remove(cabinPlayer);
                        }

                        currentFloorIdx = newFloor;
                        moveTo(floors.get(newFloor).y);
                        return;
                    }
                }
                ElevatorManager.playerElevatorCache.put(player,
                        new ElevatorManager.PlayerElevator(this, 0));

                template = Messages.msgCurrentFloor;
            } else {
                int floorDiff = cache.floorDiff();
                floorIdx = Math.floorMod(currentFloorIdx + floorDiff, floors.size());
                ElevatorFloor floor = floors.get(floorIdx);
                if (floor != null && (jumping || player.isSneaking())) {
                    // reset all player selections
                    for (Player cabinPlayer : scanCabinPlayers()) {
                        ElevatorManager.playerElevatorCache.remove(cabinPlayer);
                    }

                    currentFloorIdx = floorIdx;
                    moveTo(floor.y);
                    return;
                }
                if (floorIdx == currentFloorIdx) {
                    // wrap back to 0
                    ElevatorManager.playerElevatorCache.put(player,
                            new ElevatorManager.PlayerElevator(this, 0));
                    template = Messages.msgCurrentFloor;
                } else {
                    template = Messages.msgFloor;
                }
            }

            audience.sendActionBar(Messages.floorMessage(template,
                    floorIdx != 0 ? floors.get(floorIdx - 1).name : null,
                    floors.get(floorIdx).name,
                    floorIdx != floors.size() - 1 ? floors.get(floorIdx + 1).name : null
            ));
        }
    }

    private void doMovementTick() {
        boolean debug = Config.debug;
        movementTime--;

        try {
            animation.tick(this);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Failed to animate elevator at %d,%d,%d".formatted(controller.getX(), controller.getY(), controller.getZ()), ex);
            if (animation instanceof PacketTransformationAnimation && !Config.debug) {
                LOGGER.log(Level.SEVERE, "Falling back to Bukkit animation.");
                Elevator.SCHEDULER = TransformationAnimation.FACTORY;
            }
        }

        Location temp = controller.getLocation();

        // add new cabin entities
        for (Entity entity : scanCabinEntities()) {
            if (!cabinEntities.containsKey(entity)) {
                double offset = onEnterMovingCabin(entity, temp);
                cabinEntities.put(entity, offset);
            }
        }

        long elapsed = world.getGameTime() - movementStartTick;
        boolean doTeleport = elapsed % 10 == 0;
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
            if (!veryLenientBox.contains(temp.getX(), temp.getY(), temp.getZ()) ||
                    (entity instanceof Player player && player.getGameMode() == GameMode.SPECTATOR)) {
                onLeaveMovingCabin(entity, temp, offset);
                iter.remove();
                continue;
            }

            Vector velocity = entity.getVelocity();
            // correct location if player tries to fly
            boolean mustTeleport = !(entity instanceof Player player) || player.isFlying() || player.isGliding();

            double y = temp.getY();
            double expectedY = cabinMinY + offset;
            if (doTeleport) { // hanging entities cannot have velocity (I think)
                // force synchronize location
                if (Math.abs(expectedY - (y + velocity.getY())) > 0.5) {
                    if (debug && !mustTeleport) {
                        debug(("Entity: %s, offset: %.2f, cabin Y: %.2f, expected Y: %.4f\n" +
                                "actual Y: %.4f (location: %.2f, velocity: %.2f)").formatted(
                                entity.getName(), offset, cabinMinY, expectedY,
                                y, temp.getY(), velocity.getY()
                        ));
                    }
                } else {
                    doTeleport = false;
                }
            }
            temp.setY(expectedY);
            animation.entityTick(this, entity, expectedY, doTeleport || mustTeleport);

            entity.setFallDistance(0);
            entity.setGravity(false);
            if (entity instanceof Player player) {
                PlayerUtils.setAllowFlight(player);
                player.setFlying(false);
                player.setGliding(false);
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
        updateSigns();
    }

    // Block Interactions

    private void applyInteractions(BoundingBox cabin, boolean isArriving) {
        applyInteractions(cabin, null, isArriving, Config.interactionsCabin);
        applyInteractions(cabin.clone().expand(1, 1, 1), cabin, isArriving, Config.interactionsExterior);
        updateSigns();
    }

    private void applyInteractions(BoundingBox box, @Nullable BoundingBox exclude, boolean isArriving, Set<BlockInteraction> interactions) {
        Consumer<Block> consumer = block -> visitBlock(exclude, block, isArriving, interactions);
        if (exclude == null) {
            BlockUtils.forEachBlock(world, box, consumer);
        } else {
            BlockUtils.forEachBlockExcluding(world, box, exclude, consumer);
        }
    }

    private void visitBlock(@Nullable BoundingBox exclude, Block block, boolean isArriving, Set<BlockInteraction> interactions) {
        BlockData data = block.getBlockData();
        BlockInteraction interaction = BlockInteraction.apply(interactions, block, data, isArriving);
        boolean checkSigns = interactions.contains(BlockInteraction.SIGNS);
        if (checkSigns && (interaction == BlockInteraction.LIGHTS || interaction == BlockInteraction.NOTE_BLOCKS)) {
            // look for attached signs
            for (BlockFace cardinal : BlockUtils.CARDINALS) {
                Block relative = block.getRelative(cardinal);
                if (exclude != null && exclude.contains(relative.getX(), relative.getY(), relative.getZ()))
                    continue;
                if (!BlockUtils.isSignAttached(block, cardinal))
                    continue;
                managedSigns.add(relative);
            }
        }
        if (checkSigns && interaction == BlockInteraction.SIGNS) {
            managedSigns.add(block);
        } else {
            if (managedDoors.add(block)) {
                ElevatorManager.managedDoors.put(block, this);
            }
        }
    }

    // signs are updated whenever the floor indices change, or every 5 ticks
    private void updateSigns() {
        if (!signStale && !(moving && world.getGameTime() % 3 == 0))
            return;
        Component currentFloor;
        if (floors.isEmpty())
            return;
        if (!moving) {
            currentFloor = floors.get(currentFloorIdx).name;
            signStale = false;
        } else {
            int y = (int) Math.round(cabin.getMinY());
            // find the closest floor for display
            currentFloor = Collections.min(floors, Comparator.comparingInt(floor -> Math.abs(floor.y - y))).name;
            signStale = true;
        }
        List<Component> signLines = Arrays.asList(Component.empty(), currentFloor, Component.empty(), Component.empty());
        List<Component> hangingSignLines = Arrays.asList(Component.empty(), currentFloor, Component.empty(), Component.empty());
        // replace one with an arrow if moving
        if (moving) {
            boolean up = velocity.getY() > 0;
            Component arrow = Component.text((up ? "▲" : "▼"), up ? NamedTextColor.DARK_GREEN : NamedTextColor.RED);
            int line = (int) (world.getGameTime() / 3 % 4);
            if (up) line = 3 - line;
            signLines.set(line, TextUtils.toCentered(TextUtils.SIGN_MAX_WIDTH, arrow, signLines.get(line), arrow));
            hangingSignLines.set(line, TextUtils.toCentered(TextUtils.HANGING_SIGN_MAX_WIDTH, arrow, hangingSignLines.get(line), arrow));
        }

        managedSigns.removeIf(block -> {
            BlockState state = block.getState();
            if (!(state instanceof Sign sign))
                return true;
            var lines = state instanceof HangingSign ? hangingSignLines : signLines;
            TextUtils.setLines(sign.getSide(Side.FRONT), lines);
            TextUtils.setLines(sign.getSide(Side.BACK), lines);
            sign.update();
            return false;
        });

        // turns out changing the text negatively affects the interpolation of other properties
        // thank you Mojang, very cool
        if (false && movingBlocks != null) {
            for (ElevatorBlock movingBlock : movingBlocks) {
                if (movingBlock.textDisplays() != null && movingBlock.flags().contains(ElevatorBlock.Flag.UPDATE_TEXT)) {
                    var lines = movingBlock.blockState() instanceof HangingSign ? hangingSignLines : signLines;
                    String legacy = BukkitComponentSerializer.legacy().serialize(Component.join(JoinConfiguration.newlines(), lines));
                    for (TextDisplay textDisplay : movingBlock.textDisplays()) {
                        textDisplay.setText(legacy);
                    }
                }
            }
        }
    }

    public static final @NotNull BlockData AIR = Material.AIR.createBlockData();
    void refreshRope() {
        if (!isActive())
            return;

        BlockData ropeMaterial = Config.elevatorRopeBlock;

        int expectedLength = Math.max(0, (int) Math.floor(controller.getY() - cabin.getMaxY() +
                (moving ? (velocity.getY() > 0 ? 0.2 : -0.2) : 0) // physical rope must lag behind attached rope
        ));
        int currentLength = ropeLength;
        if (expectedLength > currentLength) {
            // place more
            for (int i = currentLength; i < expectedLength; i++) {
                Block block = controller.getRelative(0, -i - 1, 0);
                block.setBlockData(ropeMaterial, false);
            }
        } else if (expectedLength < currentLength) {
            // remove excess
            for (int i = expectedLength; i < currentLength; i++) {
                Block block = controller.getRelative(0, -i - 1, 0);
                block.setBlockData(AIR, false);
            }
        }
        ropeLength = expectedLength;
    }

    void removeRope() {
        for (int i = 1; i <= ropeLength; i++) {
            Block block = controller.getRelative(0, -i, 0);
            if (block.getBlockData().matches(Config.elevatorRopeBlock)) {
                block.setBlockData(AIR, false);
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
                cleanUp.addAll(floor.createDisplay(this, player, Color.YELLOW));
            }
        } finally {
            BlockUtils.ensureCleanUp(cleanUp, 10 * 20);
        }
    }
    
    public void debug(String message) {
        String realMessage = "[Elevator] " + message;
        for (Player player : scanCabinPlayers()) {
            player.sendMessage(realMessage);
        }
//        Bukkit.getConsoleSender().sendMessage(realMessage);
    }

    private static Block loadingBlock;
    @Nullable
    public static ElevatorController load(Block block, TileState state) {
        if (block.getType() != MATERIAL)
            return null;
        try {
            loadingBlock = block;
            ElevatorController controller = state.getPersistentDataContainer().get(ELEVATOR_CONTROLLER, STORAGE);
            if (controller == null)
                return null;
            if (Config.debug) {
                LOGGER.log(Level.INFO, "Loading " + block);
            }
            Bukkit.getScheduler().runTaskLater(Elevator.INSTANCE, controller::scanFloors, 1);
            loadingBlock = null;
            return controller;
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Failed to load ElevatorController at (%d, %d, %d)".formatted(block.getX(), block.getY(), block.getZ()), ex);
            return null;
        }
    }

    public void save() {
        if (Config.debug) {
            LOGGER.log(Level.INFO, "Saving " + controller);
        }
        TileState state = (TileState) controller.getState();
        state.getPersistentDataContainer().set(ELEVATOR_CONTROLLER, STORAGE, this);
        state.update();
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
