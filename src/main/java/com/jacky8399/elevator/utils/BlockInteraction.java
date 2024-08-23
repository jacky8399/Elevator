package com.jacky8399.elevator.utils;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.*;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum BlockInteraction {
    DOORS,
    TRAPDOORS,
    GATES,
    LIGHTS,
    NOTE_BLOCKS,
    SIGNS;

    public boolean isApplicableTo(BlockData blockData) {
        return switch (this) {
            case DOORS -> blockData instanceof Door;
            case TRAPDOORS -> blockData instanceof TrapDoor;
            case GATES -> blockData instanceof Gate;
            case LIGHTS -> blockData.getMaterial() == Material.REDSTONE_LAMP || blockData instanceof CopperBulb;
            case NOTE_BLOCKS -> blockData instanceof NoteBlock;
            case SIGNS -> Tag.ALL_SIGNS.isTagged(blockData.getMaterial());
        };
    }

    @Nullable
    public static BlockInteraction findInteraction(BlockData blockData) {
        Material material = blockData.getMaterial();
        return switch (blockData) {
            case Door ignored -> DOORS;
            case TrapDoor ignored -> TRAPDOORS;
            case Gate ignored -> GATES;
            case CopperBulb ignored -> LIGHTS;
            case Lightable ignored when material == Material.REDSTONE_LAMP -> LIGHTS;
            case NoteBlock ignored -> NOTE_BLOCKS;
            case BlockData ignored when Tag.ALL_SIGNS.isTagged(material) -> SIGNS;
            default -> null;
        };
    }

    @Nullable
    public static BlockInteraction apply(Set<BlockInteraction> interactions, Block block, BlockData blockData, boolean isArriving) {
        Material material = blockData.getMaterial();
        switch (blockData) {
            case Door door -> {
                if (interactions.contains(DOORS))
                    BlockUtils.setDoorLikeState(block, door, isArriving);
                return DOORS;
            }
            case TrapDoor trapDoor -> {
                if (interactions.contains(TRAPDOORS))
                    BlockUtils.setDoorLikeState(block, trapDoor, isArriving);
                return TRAPDOORS;
            }
            case Gate gate -> {
                if (interactions.contains(GATES))
                    BlockUtils.setDoorLikeState(block, gate, isArriving);
                return GATES;
            }
            case CopperBulb copperBulb -> {
                if (interactions.contains(LIGHTS))
                    applyLight(block, copperBulb, isArriving);
                return LIGHTS;
            }
            case Lightable lightable when material == Material.REDSTONE_LAMP -> {
                if (interactions.contains(LIGHTS))
                    applyLight(block, lightable, isArriving);
                return LIGHTS;
            }
            case NoteBlock noteBlock -> {
                if (isArriving && interactions.contains(NOTE_BLOCKS))
                    block.getWorld().playNote(block.getLocation().add(0.5, 0.5, 0.5), noteBlock.getInstrument(), noteBlock.getNote());
                return NOTE_BLOCKS;
            }
            case BlockData ignored when Tag.ALL_SIGNS.isTagged(material) -> {
                return SIGNS;
            }
            default -> {
                return null;
            }
        }
    }

    private static void applyLight(Block block, Lightable lightable, boolean isArriving) {
        lightable.setLit(isArriving);
        block.setBlockData(lightable);
    }

    public static Set<BlockInteraction> fromYaml(ConfigurationSection config) {
        var set = EnumSet.noneOf(BlockInteraction.class);
        for (BlockInteraction interaction : values()) {
            if (config.getBoolean(interaction.name().toLowerCase(Locale.ENGLISH))) {
                set.add(interaction);
            }
        }
        return set;
    }
}
