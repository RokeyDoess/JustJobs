package io.github.rokeydoess.justjobs;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class JobsMenu implements Listener {
    private enum Action { SETTINGS, BACK, BOSS_BAR, CHAT, PAYDAY_CHAT, TIME_FORMAT, PAYCHECK, HOME, ALL_RANKINGS, PREVIOUS, NEXT }
    private final JustJobsPlugin plugin;
    private final Map<String, Ranking> rankings = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final Set<UUID> refreshing = new HashSet<>();
    private long generation;
    private record Entry(UUID id, String name, double xp) { }
    private record Ranking(List<Entry> entries, Map<UUID, Integer> positions, long expires) { }
    public static final class Page implements InventoryHolder {
        final boolean leaderboard;
        final boolean settings;
        final int page;
        final String job;
        final Map<Integer, String> jobSlots = new HashMap<>();
        final Map<Integer, Action> controls = new HashMap<>();
        private Inventory inventory;
        Page(boolean leaderboard, int page, String job) { this(leaderboard, page, job, false); }
        Page(boolean leaderboard, int page, String job, boolean settings) {
            this.leaderboard = leaderboard; this.page = page; this.job = job; this.settings = settings;
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    public JobsMenu(JustJobsPlugin plugin) { this.plugin = plugin; }
    public void clearRankings() { rankings.clear(); loading.clear(); generation++; }
    private Ranking ranking(String job) {
        var cached = rankings.get(job);
        if ((cached == null || cached.expires() <= System.currentTimeMillis()) && loading.add(job)) {
            long requestedGeneration = generation;
            var snapshot = plugin.players().values().stream().filter(p -> p.xp(job) > 0)
                    .map(p -> new Entry(p.id, p.name, p.xp(job))).toList();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                var sorted = snapshot.stream().sorted(Comparator.comparingDouble(Entry::xp).reversed()
                        .thenComparing(Entry::name).thenComparing(Entry::id)).toList();
                var positions = new HashMap<UUID, Integer>();
                for (int i = 0; i < sorted.size(); i++) positions.put(sorted.get(i).id(), i + 1);
                plugin.main(() -> {
                    if (generation != requestedGeneration) return;
                    rankings.put(job, new Ranking(sorted, Map.copyOf(positions), System.currentTimeMillis() + 10_000));
                    loading.remove(job);
                    for (Player player : Bukkit.getOnlinePlayers())
                        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Page page
                                && page.leaderboard && (page.job == null || job.equals(page.job))) refresh(player, page);
                });
            });
        }
        return cached;
    }
    private void refresh(Player player, Page page) {
        if (page.settings) return;
        if (!refreshing.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            refreshing.remove(player.getUniqueId());
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == page)
                open(player, page.leaderboard, page.page, page.job);
        });
    }

    public void open(Player player, boolean leaderboard, int requestedPage, String selectedJob) {
        var jobs = plugin.registry().jobs();
        var selected = selectedJob == null ? null : plugin.registry().get(selectedJob);
        if (selectedJob != null && selected == null) { open(player, true, 0, null); return; }
        var ranking = selected == null ? null : ranking(selectedJob);
        int count = selectedJob == null ? jobs.size() : ranking == null ? 0 : ranking.entries().size();
        int capacity = MenuLayout.CAPACITY;
        int page = Math.clamp(requestedPage, 0, Math.max(0, (count - 1) / capacity));
        var holder = new Page(leaderboard, page, selectedJob);
        String title = selected != null ? selected.name() + " leaderboard" : leaderboard ? "Job leaderboards" : "Your jobs";
        holder.inventory = Bukkit.createInventory(holder, MenuLayout.SIZE, Component.text(title));
        Inventory inv = holder.inventory;
        var profile = plugin.profile(player);
        var border = leaderboard ? Material.PURPLE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
        for (int slot = 0; slot < inv.getSize(); slot++)
            if (slot < 9 || slot >= 36) inv.setItem(slot, item(border, " "));
        int visible = Math.min(capacity, Math.max(0, count - page * capacity));
        var slots = selected == null ? MenuLayout.entries(visible) : MenuLayout.ranks(visible);
        if (selected != null) {
            var entries = ranking == null ? List.<Entry>of() : ranking.entries();
            for (int index = page * capacity; index < Math.min(entries.size(), (page + 1) * capacity); index++) {
                var entry = entries.get(index);
                var progress = selected.progression().progress(entry.xp());
                var head = item(Material.PLAYER_HEAD, "#" + (index + 1) + " " + entry.name(),
                        "Level " + progress.level(), JustJobsPlugin.format(entry.xp()) + " total XP");
                var online = Bukkit.getPlayer(entry.id());
                // Known skins are included directly; other skins resolve on the client without blocking a server tick.
                head.setData(DataComponentTypes.PROFILE, online == null
                        ? ResolvableProfile.resolvableProfile().uuid(entry.id()).build()
                        : ResolvableProfile.resolvableProfile(online.getPlayerProfile()));
                inv.setItem(slots.get(index % capacity), head);
            }
            String rank = ranking != null && ranking.positions().containsKey(profile.id) ? "#" + ranking.positions().get(profile.id) : "Unranked";
            inv.setItem(MenuLayout.INFO, item(selected.icon(), selected.name() + " leaderboard", "Ranked by total XP", "Your rank: " + rank,
                    "Page " + (page + 1) + " / " + Math.max(1, (count + capacity - 1) / capacity), "Reopen to refresh; cached for up to 10 seconds."));
        } else {
            inv.setItem(MenuLayout.INFO, leaderboard ? item(Material.GOLDEN_HELMET, "Job leaderboards", "The best in each job.", "Choose a job to see the full rankings.")
                    : item(Material.BOOK, player.getName() + "'s jobs", "Every job tracks automatically.", "Unpaid earnings: " + plugin.currency(plugin.unpaidEarnings(profile)),
                    "Page " + (page + 1) + " / " + Math.max(1, (count + capacity - 1) / capacity)));
            for (int index = page * capacity; index < Math.min(jobs.size(), (page + 1) * capacity); index++) {
                var job = jobs.get(index);
                var lore = leaderboard ? preview(job) : progress(job, profile);
                int slot = slots.get(index % capacity);
                inv.setItem(slot, item(job.icon(), job.name(), lore.toArray(String[]::new)));
                holder.jobSlots.put(slot, job.id());
            }
        }
        if (count == 0) inv.setItem(22, item(selected != null && ranking == null ? Material.CLOCK : Material.BARRIER,
                selected == null ? "No jobs enabled" : ranking == null ? "Loading rankings..." : "No progress recorded yet"));
        var controls = new ArrayList<Action>();
        if (page > 0) controls.add(Action.PREVIOUS);
        if (!leaderboard) controls.add(Action.PAYCHECK);
        controls.add(Action.HOME);
        if (selected != null) controls.add(Action.ALL_RANKINGS);
        if ((page + 1) * capacity < count) controls.add(Action.NEXT);
        var controlSlots = MenuLayout.controls(controls.size());
        for (int i = 0; i < controls.size(); i++) {
            Action action = controls.get(i);
            int slot = controlSlots.get(i);
            holder.controls.put(slot, action);
            inv.setItem(slot, control(action, profile, leaderboard));
        }
        holder.controls.put(MenuLayout.SETTINGS, Action.SETTINGS);
        inv.setItem(MenuLayout.SETTINGS, control(Action.SETTINGS, profile, leaderboard));
        inv.setItem(MenuLayout.CLOSE, item(Material.OAK_DOOR, "Close"));
        player.openInventory(inv);
    }

    private void openSettings(Player player, Page previous) {
        var holder = new Page(previous.leaderboard, previous.page, previous.job, true);
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("Your settings"));
        var inv = holder.inventory;
        var border = previous.leaderboard ? Material.PURPLE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
        for (int slot = 0; slot < inv.getSize(); slot++)
            if (slot < 9 || slot >= 18) inv.setItem(slot, item(border, " "));
        inv.setItem(4, item(Material.COMPARATOR, "Your settings", "These preferences apply to you.", "Click an option to change it."));
        var profile = plugin.profile(player);
        var actions = List.of(Action.BOSS_BAR, Action.CHAT, Action.PAYDAY_CHAT, Action.TIME_FORMAT);
        for (int i = 0; i < actions.size(); i++) {
            int slot = 10 + i * 2;
            holder.controls.put(slot, actions.get(i));
            inv.setItem(slot, control(actions.get(i), profile, previous.leaderboard));
        }
        holder.controls.put(22, Action.BACK);
        inv.setItem(22, control(Action.BACK, profile, previous.leaderboard));
        inv.setItem(inv.getSize() - 1, item(Material.OAK_DOOR, "Close"));
        player.openInventory(inv);
    }

    private ItemStack control(Action action, PlayerProfile profile, boolean leaderboard) {
        return switch (action) {
            case SETTINGS -> item(Material.COMPARATOR, "Settings", "Change your boss bar, chat and clock preferences.");
            case BACK -> item(Material.ARROW, "Back", leaderboard ? "Return to your leaderboard page." : "Return to your jobs.");
            case PREVIOUS -> item(Material.ARROW, "Previous page");
            case NEXT -> item(Material.ARROW, "Next page");
            case BOSS_BAR -> item(Material.LEVER, "Boss bar: " + (profile.bossBar ? "On" : "Off"), "Click to toggle your progress bar.");
            case CHAT -> item(Material.PAPER, "Level-up chat: " + (profile.notifications ? "On" : "Off"), "Click to toggle your level-up messages.");
            case PAYDAY_CHAT -> item(Material.GOLD_NUGGET, "Payday chat: " + (profile.paydayChat ? "On" : "Off"), "Click to toggle your payday messages.");
            case TIME_FORMAT -> item(Material.CLOCK, "Time format: " + plugin.timeFormat(profile) + "-hour",
                    "Click to switch to a " + (plugin.timeFormat(profile) == 12 ? 24 : 12) + "-hour clock.");
            case PAYCHECK -> paycheck(profile);
            case HOME -> item(Material.BOOK, leaderboard ? "Back to your jobs" : "Leaderboards");
            case ALL_RANKINGS -> item(Material.COMPASS, "All leaderboards");
        };
    }

    private ItemStack paycheck(PlayerProfile profile) {
        String when = plugin.paydayEnabled() ? plugin.formatTime(profile, plugin.nextPayday()) : "As earnings become payable";
        long seconds = Math.max(0, (plugin.nextPayday() - System.currentTimeMillis()) / 1000);
        var lore = new ArrayList<>(List.of("Distribution: " + when, "Earned: " + plugin.currencyRate(plugin.unpaidEarnings(profile))));
        if (plugin.paydayEnabled()) lore.add("Next payday in " + seconds / 60 + "m " + seconds % 60 + "s");
        lore.add("Reopen to refresh.");
        return item(Material.CLOCK, "Next paycheck", lore.toArray(String[]::new));
    }

    private ArrayList<String> preview(JobRegistry.Job job) {
        var lore = new ArrayList<String>();
        var ranking = ranking(job.id());
        if (ranking == null) lore.add("Loading rankings...");
        else if (ranking.entries().isEmpty()) lore.add("No progress recorded yet.");
        else {
            int limit = plugin.settings().getInt("feedback.leaderboard-preview-size", 5);
            for (int i = 0; i < Math.min(limit, ranking.entries().size()); i++) {
                var entry = ranking.entries().get(i);
                lore.add("#" + (i + 1) + " " + entry.name() + " • Level " + job.progression().progress(entry.xp()).level());
            }
        }
        lore.add(""); lore.add("Click for full rankings");
        return lore;
    }
    private ArrayList<String> progress(JobRegistry.Job job, PlayerProfile profile) {
        var progress = job.progression().progress(profile.xp(job.id()));
        int filled = (int) Math.floor(progress.fraction() * 10);
        var lore = new ArrayList<>(List.of(job.description(), "", "Level " + progress.level(),
                "■".repeat(filled) + "□".repeat(10 - filled) + "  " + Math.round(progress.fraction() * 100) + "%",
                JustJobsPlugin.format(progress.xp()) + " / " + JustJobsPlugin.format(progress.required()) + " XP", ""));
        for (var rule : job.rules()) lore.add(reward(job, rule));
        if (job.rules().stream().anyMatch(r -> r.activity() == JobRegistry.Activity.TRAVEL || r.activity() == JobRegistry.Activity.SAIL))
            lore.add("Next travel reward: " + Math.round(profile.travel.distance(job.id())) + " / " + Math.round(job.distancePerReward()) + " blocks");
        var limit = plugin.hourlyLimit(job);
        if (limit.signum() > 0) lore.add((plugin.paydayEnabled() ? "Earned this pay period: " : "Earned this hour: ")
                + plugin.currency(plugin.capEarnings(profile, job.id(), System.currentTimeMillis())) + " / " + plugin.currency(limit));
        return lore;
    }
    private String reward(JobRegistry.Job job, JobRegistry.Rule rule) {
        boolean travel = rule.activity() == JobRegistry.Activity.TRAVEL || rule.activity() == JobRegistry.Activity.SAIL;
        double units = travel ? job.distancePerReward() : 1;
        String unit = switch (rule.activity()) {
            case BLOCK_BREAK -> "eligible block";
            case BLOCK_PLACE -> "new block placement";
            case CRAFT -> "item crafted";
            case CROP_HARVEST -> "harvest";
            case BREED -> "animal bred";
            case FISH -> "catch";
            case MOB_KILL, PLAYER_KILL -> "kill";
            case TRADE -> "emerald spent";
            case TRAVEL -> "blocks walked";
            case SAIL -> "blocks sailed";
            case ENCHANT -> "level spent";
            case SHEAR -> "animal sheared";
            case BEEHIVE_HARVEST -> "hive harvest";
            case TAME -> "animal tamed";
            case STONECUT -> "item stonecut";
            case LOOM -> "banner pattern applied";
            case BRUSH -> "completed excavation";
            case SMITH -> "smithing operation";
            case ANVIL_REPAIR -> "full item durability repaired";
            case CURE_VILLAGER -> "first villager cure";
            case CARTOGRAPHY -> "map operation";
            case BIOME_DISCOVER -> "new biome discovered";
            case SMELT -> "item smelted";
            case COOK -> "food cooked";
            case BREW -> "brewing ingredient consumed";
        };
        if (!rule.description().isBlank()) unit = rule.description();
        else {
            if (!rule.any()) {
                var names = !rule.biomes().isEmpty() ? rule.biomes().stream().map(name -> name.replace("minecraft:", "")).sorted().toList()
                        : !rule.materials().isEmpty() ? rule.materials().stream().map(Enum::name).sorted().toList()
                        : rule.entities().stream().map(Enum::name).sorted().toList();
                if (!names.isEmpty()) unit = names.stream().limit(3).map(name -> name.toLowerCase(java.util.Locale.ROOT).replace('_', ' '))
                        .collect(java.util.stream.Collectors.joining(", ")) + (names.size() > 3 ? " + " + (names.size() - 3) + " more" : "");
            }
            if (!rule.operations().isEmpty()) unit += " (" + String.join(", ", rule.operations().stream().sorted().toList()) + ")";
        }
        if (travel) unit = Math.round(units) + " " + unit;
        return JustJobsPlugin.format(rule.xp() * units) + " XP + " + plugin.currencyRate(rule.money().multiply(java.math.BigDecimal.valueOf(units))) + " / " + unit;
    }
    private ItemStack item(Material material, String name, String... lore) {
        var item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.Arrays.stream(lore).map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().addHiddenComponents(
                DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.ENCHANTMENTS, DataComponentTypes.STORED_ENCHANTMENTS,
                DataComponentTypes.UNBREAKABLE, DataComponentTypes.DAMAGE, DataComponentTypes.MAX_DAMAGE, DataComponentTypes.TOOL,
                DataComponentTypes.WEAPON, DataComponentTypes.TRIM, DataComponentTypes.DYED_COLOR).build());
        return item;
    }
    @EventHandler public void click(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Page page)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= page.inventory.getSize()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != page) return;
            if (slot == page.inventory.getSize() - 1) player.closeInventory();
            else if (page.controls.containsKey(slot)) switch (page.controls.get(slot)) {
                case SETTINGS -> openSettings(player, page);
                case BACK -> open(player, page.leaderboard, page.page, page.job);
                case BOSS_BAR -> { plugin.toggleBossBar(player); openSettings(player, page); }
                case CHAT -> { plugin.toggleNotifications(player); openSettings(player, page); }
                case PAYDAY_CHAT -> { plugin.togglePaydayChat(player); openSettings(player, page); }
                case TIME_FORMAT -> { plugin.toggleTimeFormat(player); openSettings(player, page); }
                case PAYCHECK -> open(player, page.leaderboard, page.page, page.job);
                case HOME -> open(player, !page.leaderboard, 0, null);
                case ALL_RANKINGS -> open(player, true, 0, null);
                case PREVIOUS -> open(player, page.leaderboard, page.page - 1, page.job);
                case NEXT -> open(player, page.leaderboard, page.page + 1, page.job);
            }
            else if (page.leaderboard && page.job == null && page.jobSlots.containsKey(slot)) open(player, true, 0, page.jobSlots.get(slot));
        });
    }
    @EventHandler public void drag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Page) e.setCancelled(true);
    }
}
