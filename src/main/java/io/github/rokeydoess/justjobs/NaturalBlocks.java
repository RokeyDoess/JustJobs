package io.github.rokeydoess.justjobs;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.BitSet;
import java.util.Map;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public final class NaturalBlocks implements Listener {
    private final NamespacedKey key;
    private final NamespacedKey placedKey;
    private final NamespacedKey placementRewardsKey;
    private final Map<Chunk, History> cache = new HashMap<>();
    private final Set<Chunk> dirty = new HashSet<>();
    private record History(BitSet modified, BitSet placed, BitSet placementRewards) { }

    public NaturalBlocks(Plugin plugin) {
        key = new NamespacedKey(plugin, "modified_blocks_bits");
        placedKey = new NamespacedKey(plugin, "placed_blocks_bits");
        placementRewardsKey = new NamespacedKey(plugin, "placement_rewards_bits");
    }

    static long position(int x, int y, int z) { return ((long) y << 8) | ((z & 15) << 4) | (x & 15); }
    private History history(Chunk chunk) {
        return cache.computeIfAbsent(chunk, c -> {
            long[] saved = c.getPersistentDataContainer().get(key, PersistentDataType.LONG_ARRAY);
            long[] placed = c.getPersistentDataContainer().get(placedKey, PersistentDataType.LONG_ARRAY);
            long[] rewarded = c.getPersistentDataContainer().get(placementRewardsKey, PersistentDataType.LONG_ARRAY);
            return new History(saved == null ? new BitSet() : BitSet.valueOf(saved),
                    placed == null ? new BitSet() : BitSet.valueOf(placed),
                    rewarded == null ? new BitSet() : BitSet.valueOf(rewarded));
        });
    }
    private int index(Block block) { return (int) position(block.getX(), block.getY() - block.getWorld().getMinHeight(), block.getZ()); }
    public boolean eligible(Block block) { return !history(block.getChunk()).modified().get(index(block)); }
    public boolean playerPlaced(Block block) { return history(block.getChunk()).placed().get(index(block)); }
    public boolean placementRewarded(Block block) { return history(block.getChunk()).placementRewards().get(index(block)); }
    public boolean claimPlacement(Block block) {
        if (placementRewarded(block)) return false;
        history(block.getChunk()).placementRewards().set(index(block));
        dirty.add(block.getChunk());
        return true;
    }
    public void mark(Block block) {
        Chunk chunk = block.getChunk();
        var entries = history(chunk).modified();
        int index = index(block);
        if (!entries.get(index)) {
            entries.set(index);
            dirty.add(chunk);
        }
    }
    public void markPlaced(Block block) {
        mark(block);
        setPlaced(block, true);
    }
    private void setPlaced(Block block, boolean placed) {
        Chunk chunk = block.getChunk();
        var entries = history(chunk).placed();
        int index = index(block);
        if (entries.get(index) == placed) return;
        entries.set(index, placed);
        dirty.add(chunk);
    }

    private void flush(Chunk chunk) {
        if (!dirty.remove(chunk)) return;
        var history = cache.get(chunk);
        var data = chunk.getPersistentDataContainer();
        data.set(key, PersistentDataType.LONG_ARRAY, history.modified().toLongArray());
        if (history.placed().isEmpty()) data.remove(placedKey);
        else data.set(placedKey, PersistentDataType.LONG_ARRAY, history.placed().toLongArray());
        if (history.placementRewards().isEmpty()) data.remove(placementRewardsKey);
        else data.set(placementRewardsKey, PersistentDataType.LONG_ARRAY, history.placementRewards().toLongArray());
    }
    public void flush() { for (var chunk : List.copyOf(dirty)) flush(chunk); }

    private void move(List<Block> blocks, BlockFace direction) {
        // Read all sources before changing any flags: adjacent moved blocks can overlap.
        var placed = blocks.stream().map(this::playerPlaced).toList();
        for (Block block : blocks) {
            mark(block);
            setPlaced(block, false);
        }
        for (int i = 0; i < blocks.size(); i++) {
            Block destination = blocks.get(i).getRelative(direction);
            mark(destination);
            setPlaced(destination, placed.get(i));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) { if (e.canBuild()) markPlaced(e.getBlockPlaced()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void multiPlace(BlockMultiPlaceEvent e) { if (e.canBuild()) e.getReplacedBlockStates().forEach(s -> markPlaced(s.getBlock())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void form(BlockFormEvent e) { mark(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spread(BlockSpreadEvent e) { mark(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void grow(StructureGrowEvent e) {
        e.getBlocks().forEach(s -> {
            mark(s.getBlock());
            setPlaced(s.getBlock(), false);
        });
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityChange(EntityChangeBlockEvent e) {
        // Brushing changes the dust animation several times before the block is excavated.
        if (e.getEntity() instanceof org.bukkit.entity.Player && e.getBlock().getBlockData() instanceof org.bukkit.block.data.Brushable
                && e.getTo() == e.getBlock().getType()) return;
        boolean placed = playerPlaced(e.getBlock()) || e.getEntity().getPersistentDataContainer().has(placedKey, PersistentDataType.BYTE);
        mark(e.getBlock());
        if (e.getTo().isAir()) {
            if (placed) e.getEntity().getPersistentDataContainer().set(placedKey, PersistentDataType.BYTE, (byte) 1);
            setPlaced(e.getBlock(), false);
        } else setPlaced(e.getBlock(), placed);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent e) { move(e.getBlocks(), e.getDirection()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent e) { move(e.getBlocks(), e.getDirection()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unload(ChunkUnloadEvent e) { flush(e.getChunk()); cache.remove(e.getChunk()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void save(WorldSaveEvent e) {
        for (var chunk : List.copyOf(dirty)) if (chunk.getWorld().equals(e.getWorld())) flush(chunk);
    }
}
