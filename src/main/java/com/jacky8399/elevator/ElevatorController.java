package com.jacky8399.elevator;

import com.jacky8399.elevator.utils.BlockUtils;
import com.jacky8399.elevator.utils.PaperUtils;
import com.jacky8399.elevator.utils.PlayerUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
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

import java.util.*;
import java.util.logging.Level;

public class ElevatorController {
    public static final Material MATERIAL = Material.DROPPER;
    public static final double OMG_MAGICAL_DRAG_CONSTANT = 0.9800000190734863D;

    @NotNull
    World world;

    Block controller;

    @NotNull
    BoundingBox cabin;

    boolean maintenance;
    boolean moving;
    List<ElevatorBlock> movingBlocks;

    List<BlockDisplay> chains = new ArrayList<>();

    /** cabin entities and their Y offset relative to the cabin */
    Map<LivingEntity, Double> cabinEntities;

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
        return world.getNearbyEntities(cabin, e -> {
            if (!(e instanceof LivingEntity))
                return false;
            // shrink entity hitbox for leniency
            BoundingBox box = e.getBoundingBox().expand(-0.05, -0.05, -0.05);
            // ensure that the half of the bounding box is inside the cabin
            return cabin.contains(box.getCenter()) && (cabin.contains(box.getMin()) || cabin.contains(box.getMax()));
        });
    }

    private Collection<Player> scanCabinPlayers() {
        // noinspection unchecked,rawtypes
        return (Collection) world.getNearbyEntities(cabin, e -> {
            if (!(e instanceof Player))
                return false;
            // shrink entity hitbox for leniency
            BoundingBox box = e.getBoundingBox().expand(-0.05, -0.05, -0.05);
            // ensure that the full bounding box is inside the cabin
            return cabin.contains(box.getCenter()) && (cabin.contains(box.getMin()) || cabin.contains(box.getMax()));
        });
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
            entity.getLocation(temp);
            // try to move players to the ground
            if (entity instanceof Player) {
                var rayTrace = world.rayTraceBlocks(temp, new Vector(0, -1, 0), 2, FluidCollisionMode.ALWAYS, true);
                if (rayTrace != null) {
                    temp.setY(rayTrace.getHitPosition().getY());
                    PaperUtils.teleport(entity, temp);
                }
            }

            double deltaY = temp.getY() - cabin.getMinY();
            this.cabinEntities.put((LivingEntity) entity, deltaY);
        }

        int length = maxX - minX;
        int width = maxZ - minZ;
        int height = maxY - minY;

        int noOfBlocks = length * width * height + 2 * length * width;
        movingBlocks = new ArrayList<>(noOfBlocks);
        var toBreak = new ArrayList<Block>();
        var toBreakNonSolid = new ArrayList<Block>();

        // offset Y by 2 tick since interpolation also begins in 2 tick
        Block baseBlock = world.getBlockAt((int) cabin.getMinX(), (int) Math.round(cabin.getMinY()), (int) cabin.getMinZ());
        for (int j = minY; j < maxY; j++) {
            boolean isFloor = j == minY;
            boolean isCeiling = j == maxY - 1;
            for (int i = minX; i < maxX; i++) {
                for (int k = minZ; k < maxZ; k++) {
                    Block block = world.getBlockAt(i, j, k);
                    Material type = block.getType();
                    if (block.equals(controller) || type == Material.AIR || block.getState() instanceof TileState)
                        continue;

                    ElevatorBlock elevatorBlock = ElevatorBlock.spawnFor(world, baseBlock, block, block.getLocation().add(0, (float) (velocity.getY() / 20 * 2), 0));
                    movingBlocks.add(elevatorBlock);

                    if (false) {
                        // special treatment for floors and ceilings
                        if ((isFloor || isCeiling) && !type.isOccluding()) {
                            Location location = block.getLocation();
                            if (isFloor)
                                location.add(0.5, BlockUtils.getHighestPoint(block) - 1, 0.5);
                            else
                                location.add(0.5, BlockUtils.getLowestPoint(block), 0.5);
//                            movingBlocks.add(ElevatorBlock.spawnBorder(world, location));
                        }
                    }

                    if (type.isOccluding())
                        toBreak.add(block);
                    else
                        toBreakNonSolid.add(block);
                }
            }
        }
        Bukkit.getScheduler().runTask(Elevator.INSTANCE, () -> {
            for (Block block : toBreakNonSolid) {
                block.setType(Material.AIR, false);
            }
            for (Block block : toBreak) {
                block.setType(Material.AIR);
            }
        });

        int breakpoints = 30;
        Transformation movingDisplayTransformation =
                new Transformation(new Vector3f(0, (float) (velocity.getY() / 20 * (movementTime - 2)), 0),
                        new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf());

        Bukkit.getScheduler().runTaskLater(Elevator.INSTANCE, () -> {
            for (ElevatorBlock elevatorBlock : movingBlocks) {
                var display = elevatorBlock.display();
                if (display != null) {
                    display.setInterpolationDelay(0);
                    display.setInterpolationDuration(movementTime);
                    display.setTransformation(movingDisplayTransformation);
                }
            }
        }, 2);

        moving = true;
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
                baseBlock.getLocation(location);
                location.add(block.pos().x(), block.pos().y(), block.pos().z());
                // I love floating point errors
                location.setY(Math.round(location.getY()));
                Block solidBlock = location.getBlock();
                BlockData toPlace = block.display().getBlock();
                if (solidBlock.getType() == Material.AIR) {
                    solidBlock.setBlockData(toPlace, true);
                } else {
                    world.playEffect(location, Effect.STEP_SOUND, toPlace.getMaterial());
                    world.dropItemNaturally(location, new ItemStack(toPlace.getPlacementMaterial()));
                }
            }
            // defer removal
        }
        movingBlocks.clear();
        Elevator.mustCleanupList.add(oldMovingBlocks);
        Bukkit.getScheduler().runTaskLater(Elevator.INSTANCE, () -> {
            for (ElevatorBlock block : oldMovingBlocks) {
                block.remove();
            }
            Elevator.mustCleanupList.remove(oldMovingBlocks);
            if (Config.debug)
                debug("Cleaned up " + oldMovingBlocks.size() + " entities");
        }, 2);


        double cabinMinY = cabin.getMinY();

        for (var entry : cabinEntities.entrySet()) {
            LivingEntity entity = entry.getKey();
            double offset = entry.getValue();

            entity.setGravity(true);
            entity.setFallDistance(0);
            if (entity instanceof Player player) {
                PlayerUtils.unsetAllowFlight(player);
            }
            // prevent glitching through blocks
            entity.getLocation(location).setY(Math.round(cabinMinY + offset) + 0.1);
            PaperUtils.teleport(entity, location);
        }
        cabinEntities.clear();
        setNearbyDoors(true);

        velocity = null;
        moving = false;
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
        var stale = new ArrayList<>(managedDoors);
        stale.removeAll(visited);
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
            return controller;
        } catch (Exception ex) {
            Elevator.LOGGER.severe("Failed to load ElevatorController: " + ex);
            ex.printStackTrace();
            return null;
        }
    }

    public void scanFloors() {
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
    private static final int TIME_MULTIPLIER = 20 / DEFAULT_SPEED;
    private void moveTo(int y) {
        int yDiff = y - (int) cabin.getMinY();
        if (yDiff == 0 || moving)
            return;
        if (Config.debug)
            debug("Moving to " + y + " (" + yDiff + " blocks)");
        velocity = new Vector(0, yDiff > 0 ? DEFAULT_SPEED : -DEFAULT_SPEED, 0);
        movementStartTick = world.getGameTime();
        movementTime = Math.abs(yDiff) * TIME_MULTIPLIER;
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
            immobilize();
            ElevatorManager.removeElevator(this); // don't save
            return;
        }

        if (maintenance) {
            if ((int) world.getGameTime() % 5 == 0) {
                for (Player player : scanCabinPlayers()) {
                    player.sendActionBar(Config.msgMaintenance);
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
                for (Player player : scanCabinPlayers()) {
                    player.sendActionBar(cooldownMsg);
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
                player.sendActionBar(Config.msgNoFloors);
                continue;
            }

            if (player.isFlying() || player.isGliding())
                continue;

            boolean jumping = player.getVelocity().getY() > 0;
            var cache = ElevatorManager.playerElevatorCache.get(player);

            // check for scrolling
            int floorIdx = currentFloorIdx;

            String rawMessage;
            if (cache == null || cache.floorIdx() == currentFloorIdx) {
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

                rawMessage = Config.msgCurrentFloor;
            } else {
                floorIdx = cache.floorIdx();

                ElevatorFloor floor = floorIdx >= 0 && floorIdx < floors.size() ? floors.get(floorIdx) : null;
                if (floor != null && (jumping || player.isSneaking())) {
                    // reset the selection
                    ElevatorManager.playerElevatorCache.remove(player);

                    currentFloorIdx = floorIdx;
                    moveTo(floor.y);
                }

                rawMessage = Config.msgFloor;
            }

            String message = Config.getFloorMessage(rawMessage,
                    floorIdx != floors.size() - 1 ? floors.get(floorIdx + 1).name : null,
                    floors.get(floorIdx).name,
                    floorIdx != 0 ? floors.get(floorIdx - 1).name : null
            );

            player.sendActionBar(message);
        }
    }

    void doMove() {
        movementTime--;
        long elapsed = world.getGameTime() - movementStartTick;
        boolean doTeleport = elapsed % 10 == 0;
        // sync armor stands
        Location temp = controller.getLocation();
        Vector delta = velocity.clone().multiply(1/20f);
        Bukkit.getScheduler().runTask(Elevator.INSTANCE, () -> {

//        if (doTeleport) {
            double deltaY = velocity.getY() / 20d;
//            for (ElevatorBlock block : movingBlocks) {
//                PaperUtils.teleport(block.stand(), block.stand().getLocation(temp).add(0, deltaY, 0));
//            }
//        }

            double cabinMinY = cabin.getMinY();

            double entityYVel = delta.getY();
            for (var iter = cabinEntities.entrySet().iterator(); iter.hasNext(); ) {
                var entry = iter.next();
                LivingEntity entity = entry.getKey();
                double offset = entry.getValue();

                if (entity.isDead()) {
                    iter.remove();
                    continue;
                }

                if (doTeleport) {
                    // force synchronize location
                    entity.getLocation(temp);
                    if (Config.debug) {
                        debug("Player: %s, offset: %.2f, expected Y: %.4f, actual Y: %.4f".formatted(entity.getName(), offset, cabinMinY + offset, temp.getY()));
                    }
                    if (Math.abs(cabinMinY + offset - temp.getY()) > 0.5) {
                        temp.setY(cabinMinY + offset);
                        PaperUtils.teleport(entity, temp);
                    }
                }
                entity.setGravity(false);
                if (entity instanceof Player player) {
                    PlayerUtils.setAllowFlight(player);
                    player.setFlying(false);
                }
                Vector velocity = entity.getVelocity();
                velocity.setY(entityYVel / OMG_MAGICAL_DRAG_CONSTANT);
                entity.setVelocity(velocity);
            }
        });
        cabin.shift(delta);
        world.spawnParticle(Particle.COMPOSTER, cabin.getMinX(), cabin.getMinY(), cabin.getMinZ(), 1);
        world.spawnParticle(Particle.COMPOSTER, cabin.getMaxX(), cabin.getMaxY(), cabin.getMaxZ(), 1);
    }

    void refreshChains() {

    }

    void showOutline(Collection<Player> players) {
        BlockDisplay cabinOutline = BlockUtils.createOutline(world, cabin, Material.GLASS.createBlockData(), players);
        BlockDisplay controllerOutline = BlockUtils.createOutline(world, BoundingBox.of(controller), MATERIAL.createBlockData(), players);
        Elevator.mustCleanup.add(cabinOutline);
        Elevator.mustCleanup.add(controllerOutline);
        Bukkit.getScheduler().runTaskLater(Elevator.INSTANCE, () -> {
            cabinOutline.remove();
            controllerOutline.remove();
        }, 10 * 20);
    }
    
    private void debug(String message) {
        if (Config.debug) {
            String realMessage = "[Elevator DEBUG] " + message;
            for (Player player : scanCabinPlayers()) {
                player.sendMessage(realMessage);
            }
        }
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
