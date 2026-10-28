package io.github.rokeydoess.justjobs;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Campfire;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Attribution belongs to manually inserted input quantities, never the workstation or its fuel. */
public final class ProcessingListener implements Listener {
    private final JustJobsPlugin plugin;
    private final NamespacedKey key;
    private final Map<Block, Runnable> pending = new HashMap<>();
    private record Target(Block block, Material type, List<ItemStack> inputs) { }
    private record Stock(ItemStack item, InputBatch batch) { }

    public ProcessingListener(JustJobsPlugin plugin) {
        this.plugin = plugin; key = new NamespacedKey(plugin, "processing_inputs_v1");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeClick(InventoryClickEvent event) { settle(block(event.getInventory())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        ItemStack source = event.getCursor();
        int replace = -1;
        if (event.getRawSlot() >= event.getInventory().getSize() && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY)
            source = event.getCurrentItem();
        else if (event.getAction() == InventoryAction.HOTBAR_SWAP || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD) {
            source = event.getHotbarButton() >= 0 ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton())
                    : event.getWhoClicked().getInventory().getItemInOffHand();
            replace = event.getRawSlot();
        } else if (event.getAction() == InventoryAction.SWAP_WITH_CURSOR) replace = event.getRawSlot();
        change(block(event.getInventory()), event.getWhoClicked() instanceof Player p ? p : null, source, replace, () -> !event.isCancelled());
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeDrag(InventoryDragEvent event) { settle(block(event.getInventory())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        change(block(event.getInventory()), event.getWhoClicked() instanceof Player p ? p : null, event.getOldCursor(), -1, () -> !event.isCancelled());
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void close(InventoryCloseEvent event) { settle(block(event.getInventory())); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeTransfer(InventoryMoveItemEvent event) {
        settle(block(event.getSource())); settle(block(event.getDestination()));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void transfer(InventoryMoveItemEvent event) {
        var source = block(event.getSource()); var destination = block(event.getDestination());
        change(source, null, null, -1, () -> !event.isCancelled());
        if (!Objects.equals(source, destination)) change(destination, null, null, -1, () -> !event.isCancelled());
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeInteract(PlayerInteractEvent event) { if (event.getClickedBlock() != null) settle(event.getClickedBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || !(event.getClickedBlock().getState(false) instanceof Campfire)) return;
        change(event.getClickedBlock(), event.getPlayer(), event.getItem(), -1,
                () -> event.useInteractedBlock() != org.bukkit.event.Event.Result.DENY && event.useItemInHand() != org.bukkit.event.Event.Result.DENY);
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeBreak(BlockBreakEvent event) { settle(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) { clear(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void placed(BlockPlaceEvent event) { clear(event.getBlockPlaced()); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void unload(ChunkUnloadEvent event) {
        for (var block : List.copyOf(pending.keySet()))
            if (block.getWorld().equals(event.getWorld()) && block.getX() >> 4 == event.getChunk().getX() && block.getZ() >> 4 == event.getChunk().getZ()) settle(block);
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void save(WorldSaveEvent event) {
        for (var block : List.copyOf(pending.keySet())) if (block.getWorld().equals(event.getWorld())) settle(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void cook(BlockCookEvent event) {
        settle(event.getBlock());
        var before = target(event.getBlock());
        if (before == null || empty(event.getResult())) return;
        int slot = 0;
        if (event.getBlock().getState(false) instanceof Campfire campfire) {
            slot = -1;
            for (int i = 0; i < before.inputs().size(); i++)
                if (similar(before.inputs().get(i), event.getSource()) && campfire.getCookTime(i) >= campfire.getCookTimeTotal(i)) { slot = i; break; }
            if (slot < 0) return;
        }
        var stocks = read(before);
        write(before, stocks);
        if (!owned(stocks.get(slot).batch())) return;
        int selected = slot;
        later(before.block(), () -> {
            var after = target(before.block());
            if (after == null || after.type() != before.type()) return;
            boolean consumed = !event.isCancelled() && removed(before.inputs().get(selected), after.inputs().get(selected)) == 1;
            UUID owner = consumed ? stocks.get(selected).batch().consume() : null;
            if (!consumed && !event.isCancelled()) stocks.set(selected, new Stock(null, new InputBatch()));
            align(stocks, after, null, null, -1); write(after, stocks);
            var result = event.getResult();
            if (owner != null && !empty(result)) plugin.awardProcessed(owner, after.block().getWorld(), result.getType().isEdible()
                    ? JobRegistry.Activity.COOK : JobRegistry.Activity.SMELT, result.getType(), result.getAmount());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void brew(BrewEvent event) {
        settle(event.getBlock());
        var before = target(event.getBlock());
        if (before == null || empty(before.inputs().getFirst())) return;
        var stocks = read(before);
        write(before, stocks);
        if (!owned(stocks.getFirst().batch())) return;
        var bottles = new ArrayList<ItemStack>();
        for (int i = 0; i < 3; i++) bottles.add(copy(event.getContents().getItem(i)));
        later(before.block(), () -> {
            var after = target(before.block());
            if (after == null || after.type() != before.type()) return;
            boolean changed = false;
            for (int i = 0; i < 3; i++) {
                var result = i < event.getResults().size() ? event.getResults().get(i) : null;
                if (!empty(result) && !similar(result, bottles.get(i))) changed = true;
            }
            boolean consumed = !event.isCancelled() && changed && removed(before.inputs().getFirst(), after.inputs().getFirst()) == 1;
            UUID owner = consumed ? stocks.getFirst().batch().consume() : null;
            if (!consumed && !event.isCancelled()) stocks.set(0, new Stock(null, new InputBatch()));
            align(stocks, after, null, null, -1); write(after, stocks);
            if (owner != null) plugin.awardProcessed(owner, after.block().getWorld(), JobRegistry.Activity.BREW, before.inputs().getFirst().getType(), 1);
        });
    }

    private void change(Block block, Player player, ItemStack source, int replace, BooleanSupplier valid) {
        if (block == null) return;
        settle(block);
        var before = target(block);
        if (before == null) return;
        var stocks = read(before);
        UUID owner = player != null && plugin.eligible(player) ? plugin.profile(player).id : null;
        var incoming = copy(source);
        int replaced = before.type() == Material.BREWING_STAND ? (replace == 3 ? 0 : -1) : replace == 0 ? 0 : -1;
        later(block, () -> {
            var after = target(block);
            if (after == null || after.type() != before.type()) return;
            boolean accepted = valid.getAsBoolean();
            align(stocks, after, accepted ? owner : null, accepted ? incoming : null, accepted ? replaced : -1);
            write(after, stocks);
        });
    }
    private void later(Block block, Runnable action) {
        pending.put(block, action);
        Bukkit.getScheduler().runTask(plugin, () -> { if (pending.get(block) == action) settle(block); });
    }
    private void settle(Block block) { var action = block == null ? null : pending.remove(block); if (action != null) action.run(); }
    public void flush() { for (var block : List.copyOf(pending.keySet())) settle(block); }

    private Block block(Inventory inventory) {
        return inventory.getHolder(false) instanceof org.bukkit.block.BlockState state && switch (inventory.getType()) {
            case FURNACE, SMOKER, BLAST_FURNACE, BREWING -> true;
            default -> false;
        } ? state.getBlock() : null;
    }
    private Target target(Block block) {
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return null;
        var state = block.getState(false);
        var inputs = new ArrayList<ItemStack>();
        if (state instanceof Campfire campfire) for (int i = 0; i < campfire.getSize(); i++) inputs.add(copy(campfire.getItem(i)));
        else if (state instanceof org.bukkit.block.Furnace furnace) inputs.add(copy(furnace.getInventory().getSmelting()));
        else if (state instanceof org.bukkit.block.BrewingStand stand) inputs.add(copy(stand.getInventory().getIngredient()));
        else return null;
        return new Target(block, block.getType(), inputs);
    }
    private List<Stock> read(Target target) {
        var stocks = new ArrayList<Stock>();
        byte[] encoded = ((TileState) target.block().getState(false)).getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY);
        if (encoded != null) try (var input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != 1 || !input.readUTF().equals(target.type().name()) || input.readInt() != target.inputs().size()) throw new IOException("Unknown input attribution format");
            for (int i = 0; i < target.inputs().size(); i++) {
                int length = input.readInt();
                if (length < 0 || length > 1_048_576 || length > input.available()) throw new IOException("Invalid input item");
                var item = length == 0 ? null : ItemStack.deserializeBytes(input.readNBytes(length));
                var batch = new InputBatch();
                int parts = input.readInt();
                if (parts < 0 || parts > 4096) throw new IOException("Invalid input batch");
                for (int p = 0; p < parts; p++) {
                    UUID owner = input.readBoolean() ? new UUID(input.readLong(), input.readLong()) : null;
                    int amount = input.readInt();
                    if (amount <= 0 || amount > 1_000_000 || batch.size() > 1_000_000 - amount) throw new IOException("Invalid input quantity");
                    batch.add(owner, amount);
                }
                stocks.add(new Stock(item, batch));
            }
            if (input.available() != 0) throw new IOException("Trailing input attribution data");
        } catch (IOException | RuntimeException failure) {
            stocks.clear();
            plugin.getLogger().warning("Discarded unreadable processing attribution at " + target.block().getLocation() + ": " + failure.getMessage());
        }
        if (stocks.isEmpty()) for (int i = 0; i < target.inputs().size(); i++) stocks.add(new Stock(null, new InputBatch()));
        align(stocks, target, null, null, -1);
        return stocks;
    }
    private void align(List<Stock> stocks, Target target, UUID owner, ItemStack incoming, int replaced) {
        int available = owner == null ? 0 : count(incoming);
        for (int i = 0; i < stocks.size(); i++) {
            var current = target.inputs().get(i); var previous = stocks.get(i);
            UUID inserted = similar(current, incoming) ? owner : null;
            if (i == replaced && similar(current, incoming) || !similar(previous.item(), current)) {
                var batch = new InputBatch();
                int credited = inserted == null ? 0 : Math.min(count(current), available);
                batch.add(inserted, credited); batch.add(null, count(current) - credited); available -= credited;
                stocks.set(i, new Stock(copy(current), batch));
            } else {
                int added = count(current) - previous.batch().size();
                int credited = inserted == null ? 0 : Math.min(Math.max(0, added), available);
                previous.batch().add(inserted, credited); available -= credited;
                previous.batch().resize(count(current), null);
            }
        }
    }
    private void write(Target target, List<Stock> stocks) {
        // A live state preserves the inventory objects vanilla is currently consuming.
        if (!(target.block().getState(false) instanceof TileState state) || state.getType() != target.type()) return;
        var data = state.getPersistentDataContainer();
        if (stocks.stream().noneMatch(stock -> owned(stock.batch()))) {
            if (data.has(key)) { data.remove(key); state.update(false, false); }
            return;
        }
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            output.writeInt(1); output.writeUTF(target.type().name()); output.writeInt(stocks.size());
            for (var stock : stocks) {
                var item = copy(stock.item()); if (!empty(item)) item.setAmount(1);
                byte[] serialized = empty(item) ? new byte[0] : item.serializeAsBytes();
                output.writeInt(serialized.length); output.write(serialized); output.writeInt(stock.batch().parts().size());
                for (var part : stock.batch().parts()) {
                    output.writeBoolean(part.owner() != null);
                    if (part.owner() != null) { output.writeLong(part.owner().getMostSignificantBits()); output.writeLong(part.owner().getLeastSignificantBits()); }
                    output.writeInt(part.amount());
                }
            }
            output.flush(); data.set(key, PersistentDataType.BYTE_ARRAY, bytes.toByteArray()); state.update(false, false);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private void clear(Block block) {
        settle(block);
        if (block.getState(false) instanceof TileState state && state.getPersistentDataContainer().has(key)) {
            state.getPersistentDataContainer().remove(key); state.update(false, false);
        }
    }
    private static boolean owned(InputBatch batch) { return batch.parts().stream().anyMatch(part -> part.owner() != null); }
    private static int removed(ItemStack before, ItemStack after) { return count(before) - (similar(before, after) ? count(after) : 0); }
    private static boolean empty(ItemStack item) { return item == null || item.isEmpty(); }
    private static int count(ItemStack item) { return empty(item) ? 0 : item.getAmount(); }
    private static boolean similar(ItemStack first, ItemStack second) { return empty(first) ? empty(second) : !empty(second) && first.isSimilar(second); }
    private static ItemStack copy(ItemStack item) { return empty(item) ? null : item.clone(); }
}
