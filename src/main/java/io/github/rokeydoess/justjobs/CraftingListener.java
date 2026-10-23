package io.github.rokeydoess.justjobs;

import io.papermc.paper.event.inventory.ItemCraftedEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Counts completed player crafts, including each recipe consumed by a bulk craft. */
public final class CraftingListener implements Listener {
    private final JustJobsPlugin plugin;
    private record Attempt(CraftingInventory inventory, Set<List<ItemStack>> consumed) { }
    private final Map<UUID, Attempt> attempts = new HashMap<>();

    public CraftingListener(JustJobsPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void start(CraftItemEvent event) {
        UUID id = event.getWhoClicked().getUniqueId();
        attempts.remove(id);
        if (event.isCancelled() || !(event.getWhoClicked() instanceof org.bukkit.entity.Player player)
                || !plugin.eligible(player)) return;
        var attempt = new Attempt(event.getInventory(), new HashSet<>());
        attempts.put(id, attempt);
        Bukkit.getScheduler().runTask(plugin, () -> attempts.remove(id, attempt));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void completed(ItemCraftedEvent event) {
        var attempt = attempts.get(event.getPlayer().getUniqueId());
        var item = event.getCraftedItem();
        if (attempt == null || item.isEmpty()) return;
        var matrix = Arrays.stream(attempt.inventory().getMatrix()).map(stack -> stack == null ? null : stack.clone()).toList();
        // Paper can report both the moved stack and its dropped remainder on a partial shift-click.
        // Both reports precede ingredient consumption; only a changed grid represents another craft.
        if (!attempt.consumed().add(matrix)) return;
        plugin.award(event.getPlayer(), JobRegistry.Activity.CRAFT, item.getType(), null, item.getAmount());
    }
}
