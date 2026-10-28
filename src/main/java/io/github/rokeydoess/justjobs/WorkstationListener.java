package io.github.rokeydoess.justjobs;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmithingTrimRecipe;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.Damageable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.rokeydoess.justjobs.JobRegistry.Activity.*;

/** Rewards taken results only after the workstation has consumed its inputs. */
public final class WorkstationListener implements Listener {
    private final JustJobsPlugin plugin;
    private record Attempt(Player player, Inventory inventory, List<ItemStack> inputs, int[] slots,
                           JobRegistry.Activity activity, ItemStack result, String operation, double units) { }
    private final Map<UUID, Attempt> attempts = new HashMap<>();
    public WorkstationListener(JustJobsPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeClick(InventoryClickEvent event) { finish(event.getWhoClicked().getUniqueId()); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeDrag(InventoryDragEvent event) { finish(event.getWhoClicked().getUniqueId()); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void close(InventoryCloseEvent event) { finish(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void take(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !plugin.eligible(player)
                || event.getSlotType() != InventoryType.SlotType.RESULT || event.getRawSlot() >= event.getInventory().getSize()) return;
        switch (event.getAction()) {
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE, MOVE_TO_OTHER_INVENTORY,
                 HOTBAR_SWAP, HOTBAR_MOVE_AND_READD, DROP_ALL_SLOT, DROP_ONE_SLOT -> { }
            default -> { return; }
        }
        var inventory = event.getInventory();
        var result = event.getCurrentItem();
        if (empty(result)) return;
        JobRegistry.Activity activity;
        String operation = null;
        int[] slots;
        double units = result.getAmount();
        switch (inventory.getType()) {
            case STONECUTTER -> { activity = STONECUT; slots = new int[]{0}; }
            case LOOM -> {
                activity = LOOM; slots = new int[]{0, 1};
                var banner = inventory.getItem(0); var dye = inventory.getItem(1);
                if (empty(banner) || empty(dye) || !dye.getType().name().endsWith("_DYE") || banner.getType() != result.getType()
                        || !(banner.getItemMeta() instanceof BannerMeta before) || !(result.getItemMeta() instanceof BannerMeta after)
                        || after.numberOfPatterns() != before.numberOfPatterns() + 1
                        || !after.getPatterns().subList(0, before.numberOfPatterns()).equals(before.getPatterns())) return;
            }
            case SMITHING -> {
                activity = SMITH; slots = new int[]{0, 1, 2};
                var recipe = ((org.bukkit.inventory.SmithingInventory) inventory).getRecipe();
                if (recipe instanceof SmithingTrimRecipe) operation = "trim";
                else if (recipe instanceof SmithingTransformRecipe) operation = "upgrade";
                else return;
            }
            case ANVIL -> {
                activity = ANVIL_REPAIR; slots = new int[]{0, 1};
                units = repaired(inventory.getItem(0), result);
                if (units <= 0) return;
            }
            case CARTOGRAPHY -> {
                activity = CARTOGRAPHY; slots = new int[]{0, 1};
                var reagent = inventory.getItem(1);
                if (empty(reagent) || result.getType() != Material.FILLED_MAP) return;
                operation = switch (reagent.getType()) { case PAPER -> "expand"; case GLASS_PANE -> "lock"; case MAP -> "copy"; default -> null; };
                if (operation == null) return;
                units = 1;
            }
            default -> { return; }
        }
        if (!plugin.registry().rewards(activity, result.getType(), null, operation)) return;
        var inputs = java.util.Arrays.stream(slots).mapToObj(inventory::getItem).map(stack -> stack == null ? null : stack.clone()).toList();
        if (inputs.stream().anyMatch(WorkstationListener::empty)) return;
        var attempt = new Attempt(player, inventory, inputs, slots, activity, result.clone(), operation, units);
        attempts.put(player.getUniqueId(), attempt);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (attempts.get(player.getUniqueId()) == attempt) finish(player.getUniqueId());
        });
    }

    private void finish(UUID id) {
        var attempt = attempts.remove(id);
        if (attempt == null) return;
        int operations = Integer.MAX_VALUE;
        for (int i = 0; i < attempt.slots().length; i++) {
            var before = attempt.inputs().get(i);
            var after = attempt.inventory().getItem(attempt.slots()[i]);
            int remaining = !empty(after) && before.isSimilar(after) ? after.getAmount() : 0;
            operations = Math.min(operations, before.getAmount() - remaining);
        }
        if (operations > 0) plugin.award(attempt.player(), attempt.activity(), attempt.result().getType(), null,
                attempt.units() * operations, attempt.operation());
    }
    public void flush() { for (var id : List.copyOf(attempts.keySet())) finish(id); }

    static double repaired(ItemStack before, ItemStack after) {
        if (empty(before) || empty(after) || before.getType() != after.getType()
                || !(before.getItemMeta() instanceof Damageable old) || !(after.getItemMeta() instanceof Damageable next)) return 0;
        int maximum = old.hasMaxDamage() ? old.getMaxDamage() : before.getType().getMaxDurability();
        return maximum <= 0 ? 0 : Math.clamp((old.getDamage() - next.getDamage()) / (double) maximum, 0, 1);
    }
    private static boolean empty(ItemStack item) { return item == null || item.isEmpty(); }
}
