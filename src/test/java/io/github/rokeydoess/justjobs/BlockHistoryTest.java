package io.github.rokeydoess.justjobs;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockHistoryTest {
    private final Plugin plugin = proxy(Plugin.class, (p, method, args) -> {
        if (method.getName().equals("getName")) return "JustJobs";
        if (method.getName().equals("namespace")) return "justjobs";
        throw new UnsupportedOperationException(method.getName());
    });
    private final Map<String, Block> blocks = new HashMap<>();
    private final Map<String, Chunk> chunks = new HashMap<>();
    private int writes;
    private final World world = proxy(World.class, (p, method, args) -> {
        if (method.getName().equals("getMinHeight")) return -64;
        throw new UnsupportedOperationException(method.getName());
    });

    @Test void handPlacedFlagSurvivesReloadWithTheChunk() {
        var history = new NaturalBlocks(plugin);
        Block log = block(0, 70, 0);
        history.markPlaced(log);
        history.flush();
        var reloaded = new NaturalBlocks(plugin);
        assertTrue(reloaded.playerPlaced(log));
        assertFalse(reloaded.eligible(log));
    }
    @Test void placementRewardsAreIndependentOfNaturalHistoryAndSurviveReload() {
        var history = new NaturalBlocks(plugin);
        Block spot = block(15, -63, 15), adjacent = block(16, -63, 15);
        history.markPlaced(spot);
        assertTrue(history.claimPlacement(spot));
        assertFalse(history.claimPlacement(spot));
        assertTrue(history.claimPlacement(adjacent));
        history.flush();
        var reloaded = new NaturalBlocks(plugin);
        assertFalse(reloaded.claimPlacement(spot));
        assertFalse(reloaded.claimPlacement(adjacent));
        assertTrue(reloaded.claimPlacement(block(15, -62, 15)));
    }
    @Test void pistonsAndRegrowthNeverClearOrMoveAClaimedPlacementLocation() {
        var history = new NaturalBlocks(plugin);
        Block original = block(15, 70, 0), destination = block(16, 70, 0);
        history.claimPlacement(original);
        history.extend(new BlockPistonExtendEvent(block(14, 70, 0), List.of(original), BlockFace.EAST));
        assertTrue(history.placementRewarded(original));
        assertFalse(history.placementRewarded(destination));
        history.grow(new StructureGrowEvent(new Location(world, 15, 70, 0), TreeType.TREE, true, null, List.of(state(original))));
        assertTrue(history.placementRewarded(original));
    }

    @Test void regrowingAtAnOldPlacementReplacesThePlacementFlagButStaysNonNatural() {
        var history = new NaturalBlocks(plugin);
        Block log = block(0, 70, 0);
        history.markPlaced(log);
        history.grow(new StructureGrowEvent(new Location(world, 0, 70, 0), TreeType.TREE, true, null, List.of(state(log))));
        assertFalse(history.playerPlaced(log));
        assertFalse(history.eligible(log));
        history.flush();
        var reloaded = new NaturalBlocks(plugin);
        assertFalse(reloaded.playerPlaced(log));
        assertFalse(reloaded.eligible(log));
    }

    @Test void aLegacyModifiedLogIsNotMistakenForANewPlacement() {
        var history = new NaturalBlocks(plugin);
        Block log = block(0, 70, 0);
        history.mark(log);
        history.flush();
        var reloaded = new NaturalBlocks(plugin);
        assertFalse(reloaded.eligible(log));
        assertFalse(reloaded.playerPlaced(log));
    }

    @Test void pistonTrainPreservesWhichLogsWerePlacedAcrossChunkBoundaries() {
        var history = new NaturalBlocks(plugin);
        Block placed = block(15, 70, 0), grown = block(16, 70, 0), destination = block(17, 70, 0);
        history.markPlaced(placed);
        history.mark(grown);
        history.extend(new BlockPistonExtendEvent(block(14, 70, 0), List.of(placed, grown), BlockFace.EAST));
        assertFalse(history.playerPlaced(placed));
        assertTrue(history.playerPlaced(grown));
        assertFalse(history.playerPlaced(destination));
        assertFalse(history.eligible(destination));
        history.flush();
        assertTrue(new NaturalBlocks(plugin).playerPlaced(grown));
    }

    @Test void retractionCannotMakeAPlacedLogEligible() {
        var history = new NaturalBlocks(plugin);
        Block placed = block(2, 70, 0), destination = block(1, 70, 0);
        history.markPlaced(placed);
        history.retract(new BlockPistonRetractEvent(block(0, 70, 0), List.of(placed), BlockFace.WEST));
        assertTrue(history.playerPlaced(destination));
        assertFalse(history.playerPlaced(placed));
        assertFalse(history.eligible(destination));
    }

    @Test void manyEditsToOneChunkAreBatchedAndUnloadFlushesThem() {
        var history = new NaturalBlocks(plugin);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) history.markPlaced(block(x, 70, z));
        assertEquals(0, writes);
        history.unload(new ChunkUnloadEvent(chunk(0, 0), true));
        assertEquals(2, writes);
        var restored = new NaturalBlocks(plugin);
        assertTrue(restored.playerPlaced(block(15, 70, 15)));
        assertFalse(restored.eligible(block(15, 70, 15)));
    }

    private BlockState state(Block block) {
        return proxy(BlockState.class, (p, method, args) -> {
            if (method.getName().equals("getBlock")) return block;
            throw new UnsupportedOperationException(method.getName());
        });
    }

    private Block block(int x, int y, int z) {
        return blocks.computeIfAbsent(x + ":" + y + ":" + z, id -> proxy(Block.class, (p, method, args) -> switch (method.getName()) {
            case "getX" -> x;
            case "getY" -> y;
            case "getZ" -> z;
            case "getWorld" -> world;
            case "getChunk" -> chunk(x >> 4, z >> 4);
            case "getRelative" -> {
                var direction = (BlockFace) args[0];
                yield block(x + direction.getModX(), y + direction.getModY(), z + direction.getModZ());
            }
            default -> throw new UnsupportedOperationException(method.getName());
        }));
    }

    private Chunk chunk(int x, int z) {
        return chunks.computeIfAbsent(x + ":" + z, id -> {
            var values = new HashMap<NamespacedKey, Object>();
            var data = proxy(PersistentDataContainer.class, (p, method, args) -> switch (method.getName()) {
                case "get" -> values.containsKey(args[0]) ? ((long[]) values.get(args[0])).clone() : null;
                case "set" -> { writes++; values.put((NamespacedKey) args[0], ((long[]) args[2]).clone()); yield null; }
                case "remove" -> { values.remove(args[0]); yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            });
            return proxy(Chunk.class, (p, method, args) -> switch (method.getName()) {
                case "getPersistentDataContainer" -> data;
                case "getWorld" -> world;
                case "hashCode" -> id.hashCode();
                case "equals" -> p == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
