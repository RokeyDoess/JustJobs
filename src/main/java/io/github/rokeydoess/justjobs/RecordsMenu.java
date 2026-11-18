package io.github.rokeydoess.justjobs;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read-only views of confirmed deposits and server economy totals. */
public final class RecordsMenu implements Listener {
    private enum View { HISTORY, ECONOMY }
    private final JustJobsPlugin plugin;
    private final JobsMenu jobs;
    static final class Page implements InventoryHolder {
        final View view;
        final int page;
        final JobsMenu.Page origin;
        final List<ItemStack> entries;
        final ItemStack summary;
        final String title;
        Inventory inventory;
        Page(View view, int page, JobsMenu.Page origin, List<ItemStack> entries, ItemStack summary, String title) {
            this.view = view; this.page = page; this.origin = origin;
            this.entries = List.copyOf(entries); this.summary = summary; this.title = title;
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private record Totals(BigDecimal earned, BigDecimal paid, BigDecimal capped, long capHits, int players) {
        static Totals empty() { return new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0); }
        Totals add(JobEconomy.Snapshot stats) {
            return new Totals(earned.add(stats.earned()), paid.add(stats.paid()), capped.add(stats.capped()), capHits + stats.capHits(), players + 1);
        }
        Totals add(Totals other) {
            return new Totals(earned.add(other.earned()), paid.add(other.paid()), capped.add(other.capped()), capHits + other.capHits(), 0);
        }
    }
    public RecordsMenu(JustJobsPlugin plugin, JobsMenu jobs) { this.plugin = plugin; this.jobs = jobs; }

    public void openHistory(Player player, int requestedPage) {
        openHistory(player, requestedPage, null);
    }
    void openHistory(Player player, int requestedPage, JobsMenu.Page origin) {
        var loading = show(player, new Page(View.HISTORY, requestedPage, origin, List.of(),
                JobsMenu.item(Material.CLOCK, "Loading payments..."), "Paycheck history"));
        plugin.history(player.getUniqueId(), receipts -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != loading) return;
            var entries = new ArrayList<ItemStack>();
            if (receipts != null) for (var receipt : receipts) {
                var lore = new ArrayList<String>();
                lore.add("Deposited: " + plugin.currency(receipt.amount()));
                new TreeMap<>(Paycheck.byJob(receipt.jobs())).forEach((job, amount) -> lore.add(jobName(job) + ": " + plugin.currency(amount)));
                entries.add(JobsMenu.item(receipt.bonus() ? Material.GOLD_INGOT : Material.PAPER,
                        (receipt.bonus() ? "Bonus • " : "Paycheck • ") + dateTime(plugin.profile(player), receipt.paidAt()), lore.toArray(String[]::new)));
            }
            String message = receipts == null ? "Could not load history. Reopen to try again."
                    : receipts.isEmpty() ? "No deposits recorded yet." : "Newest deposits first.";
            show(player, new Page(View.HISTORY, requestedPage, origin, entries,
                    JobsMenu.item(Material.WRITABLE_BOOK, "Paycheck history", message, "Your latest 30 confirmed deposits, including bonuses.",
                            "Recording since " + dateTime(plugin.profile(player), plugin.economySince())), "Paycheck history"));
        });
    }

    private Map<String, Totals> totals() {
        var totals = new TreeMap<String, Totals>();
        for (var profile : plugin.players().values()) profile.economy.forEach((job, stats) ->
                totals.put(job, totals.getOrDefault(job, Totals.empty()).add(stats.snapshot())));
        return totals;
    }

    public void openEconomy(Player player, int requestedPage) {
        if (!player.hasPermission("justjobs.admin")) { player.sendMessage("You don't have permission to do that."); return; }
        var totals = totals();
        var entries = new ArrayList<ItemStack>();
        Totals all = Totals.empty();
        for (var entry : totals.entrySet()) {
            var job = plugin.registry().get(entry.getKey());
            var stats = entry.getValue(); all = all.add(stats);
            entries.add(JobsMenu.item(job == null ? Material.PAPER : job.icon(), jobName(entry.getKey()),
                    "Earned: " + plugin.currency(stats.earned()), "Deposited: " + plugin.currency(stats.paid()),
                    "Money above cap: " + plugin.currency(stats.capped()), "Cap reached: " + stats.capHits() + " times",
                    "Players recorded: " + stats.players()));
        }
        var summary = JobsMenu.item(Material.GOLD_BLOCK, "Server economy",
                "Earned: " + plugin.currency(all.earned()), "Deposited: " + plugin.currency(all.paid()),
                "Unpaid balance: " + plugin.currency(unpaid()), "Money above caps: " + plugin.currency(all.capped()),
                "Caps reached: " + all.capHits() + " times",
                "Recording since " + dateTime(plugin.profile(player), plugin.economySince()));
        show(player, new Page(View.ECONOMY, requestedPage, null, entries, summary, "Job economy"));
    }

    public void economyText(CommandSender sender) {
        if (!sender.hasPermission("justjobs.admin")) { sender.sendMessage("You don't have permission to do that."); return; }
        var totals = totals();
        Totals all = Totals.empty();
        sender.sendMessage("Job economy since " + DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(plugin.economySince())));
        for (var entry : totals.entrySet()) {
            var stats = entry.getValue(); all = all.add(stats);
            sender.sendMessage(jobName(entry.getKey()) + " — earned " + plugin.currency(stats.earned()) + ", deposited "
                    + plugin.currency(stats.paid()) + ", above cap " + plugin.currency(stats.capped()) + ", cap reached " + stats.capHits() + " times");
        }
        sender.sendMessage("Total earned " + plugin.currency(all.earned()) + ", deposited " + plugin.currency(all.paid()) + ", unpaid " + plugin.currency(unpaid()));
    }

    private BigDecimal unpaid() {
        BigDecimal unpaid = BigDecimal.ZERO;
        for (var profile : plugin.players().values()) unpaid = unpaid.add(plugin.unpaidEarnings(profile));
        return unpaid;
    }

    private String jobName(String id) {
        var job = plugin.registry().get(id);
        return job == null ? id.equals(Paycheck.PREVIOUS) ? "Previous earnings" : id : job.name();
    }
    private String dateTime(PlayerProfile profile, long at) {
        return DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at)) + " " + plugin.formatTime(profile, at);
    }
    private Page show(Player player, Page data) {
        int pageCount = Math.max(1, (data.entries.size() + 26) / 27);
        int index = Math.clamp(data.page, 0, pageCount - 1);
        var page = new Page(data.view, index, data.origin, data.entries, data.summary, data.title);
        page.inventory = Bukkit.createInventory(page, MenuLayout.SIZE, Component.text(data.title));
        var inv = page.inventory;
        for (int slot = 0; slot < inv.getSize(); slot++) if (slot < 9 || slot >= 36)
            inv.setItem(slot, JobsMenu.item(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(4, data.summary);
        int end = Math.min(data.entries.size(), (index + 1) * 27);
        for (int i = index * 27; i < end; i++) inv.setItem(9 + i % 27, data.entries.get(i));
        if (data.entries.isEmpty()) inv.setItem(22, JobsMenu.item(Material.PAPER, "No records yet"));
        if (index > 0) inv.setItem(37, JobsMenu.item(Material.ARROW, "Previous page"));
        inv.setItem(40, JobsMenu.item(Material.ARROW, "Back", "Page " + (index + 1) + " / " + pageCount));
        if (index + 1 < pageCount) inv.setItem(43, JobsMenu.item(Material.ARROW, "Next page"));
        inv.setItem(44, JobsMenu.item(Material.OAK_DOOR, "Close"));
        player.openInventory(inv);
        return page;
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Page page)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != page) return;
            if (page.view == View.ECONOMY && !player.hasPermission("justjobs.admin")) { player.closeInventory(); return; }
            if (slot == 44) player.closeInventory();
            else if (slot == 40) {
                if (page.origin == null) jobs.open(player, false, 0, null);
                else jobs.reopen(player, page.origin, page.origin.page);
            } else if (slot == 37 && page.page > 0) show(player, new Page(page.view, page.page - 1, page.origin, page.entries, page.summary, page.title));
            else if (slot == 43 && (page.page + 1) * 27 < page.entries.size())
                show(player, new Page(page.view, page.page + 1, page.origin, page.entries, page.summary, page.title));
        });
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Page) event.setCancelled(true);
    }
}
