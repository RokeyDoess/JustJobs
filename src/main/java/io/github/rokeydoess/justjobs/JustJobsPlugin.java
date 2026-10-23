package io.github.rokeydoess.justjobs;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class JustJobsPlugin extends JavaPlugin {
    private YamlConfiguration settings;
    private JobRegistry registry;
    private DataStore store;
    private ExecutorService worker;
    private final Map<UUID, PlayerProfile> players = new HashMap<>();
    private final Map<String, Long> pvpPairs = new HashMap<>();
    private final Map<UUID, Display> bars = new HashMap<>();
    private final Set<UUID> paying = new HashSet<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final Map<String, Long> dirtyPairs = new HashMap<>();
    private final Set<UUID> payoutQueue = new java.util.LinkedHashSet<>();
    private final Set<UUID> unpaid = new HashSet<>();
    private final List<BukkitTask> scheduled = new ArrayList<>();
    private volatile boolean storageFailed;
    private JobsMenu menus;
    private VaultPayments economy;
    private Boolean economyAvailable;
    private NaturalBlocks natural;
    private ActivityListener activities;
    private long lastPrune;
    private boolean closing;
    private boolean paymentBatchInFlight;
    private record Display(BossBar bar, String job, long shown, long expires) { }

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            if (!new File(getDataFolder(), "jobs.yml").exists()) saveResource("jobs.yml", false);
            loadSettings();
            store = new DataStore(getDataFolder().toPath().resolve("jobs.db"));
            players.putAll(store.load());
            pvpPairs.putAll(store.loadPairs());
            int recovered = store.recoverInterrupted(players);
            players.values().stream().filter(p -> p.pending.signum() > 0).forEach(p -> unpaid.add(p.id));
            if (recovered > 0) getLogger().info("Automatically restored " + recovered + " interrupted payout(s) for retry.");
            worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("JustJobs-storage").factory());
            natural = new NaturalBlocks(this);
            menus = new JobsMenu(this);
            activities = new ActivityListener(this, natural);
            getServer().getPluginManager().registerEvents(natural, this);
            getServer().getPluginManager().registerEvents(activities, this);
            getServer().getPluginManager().registerEvents(new CraftingListener(this), this);
            getServer().getPluginManager().registerEvents(menus, this);
            refreshEconomy();
            scheduleTasks();
            queueUnpaid();
            getLogger().info("Enabled " + registry.jobs().size() + " automatic jobs.");
        } catch (Exception | LinkageError e) {
            getLogger().log(Level.SEVERE, "Could not start JustJobs: " + e.getMessage(), e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    public void loadSettings() throws Exception {
        ConfigFiles.recover(getDataFolder().toPath());
        var next = new YamlConfiguration();
        next.options().parseComments(true);
        next.load(new File(getDataFolder(), "config.yml"));
        for (String section : List.of("economy", "feedback", "storage", "progression", "rules"))
            if (next.contains(section) && !next.isConfigurationSection(section)) throw new IllegalArgumentException(section + " must be a section");
        ConfigUpgrade.settings(next);
        YamlConfiguration defaults;
        try (var reader = new java.io.InputStreamReader(getResource("config.yml"), java.nio.charset.StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        }
        for (String key : defaults.getKeys(true)) if (!defaults.isConfigurationSection(key) && !next.contains(key)) next.set(key, defaults.get(key));
        ConfigValues.keys(next, Set.of("config-version", "economy", "feedback", "storage", "progression", "rules"));
        for (String section : List.of("economy", "feedback", "storage", "progression", "rules")) {
            if (!next.isConfigurationSection(section)) throw new IllegalArgumentException(section + " must be a section");
            ConfigValues.keys(next.getConfigurationSection(section), defaults.getConfigurationSection(section).getKeys(false));
        }
        for (String key : List.of("storage.autosave-seconds", "economy.payout-interval-seconds", "feedback.boss-bar-seconds", "rules.travel-sample-ticks", "rules.pvp-pair-cooldown-seconds"))
            ConfigValues.integer(next, key, defaults.getLong(key), 1, 86400);
        for (String key : List.of("economy.whole-number-payouts", "economy.payout-chat", "feedback.level-up-chat", "feedback.level-up-chat-default", "feedback.boss-bar", "rules.reward-spawner-mobs"))
            ConfigValues.bool(next, key, defaults.getBoolean(key));
        ConfigValues.money(next, "economy.hourly-income-limit-per-job", new BigDecimal("100"));
        ConfigValues.text(next, "economy.currency-symbol", "$", 16);
        ConfigValues.integer(next, "feedback.leaderboard-preview-size", 5, 1, 10);
        ConfigValues.strings(next, "rules.excluded-worlds");
        for (String key : List.of("rules.travel-minimum-displacement", "rules.maximum-walk-blocks-per-sample", "rules.maximum-sail-blocks-per-sample")) {
            double value = ConfigValues.number(next, key, defaults.getDouble(key));
            if (!Double.isFinite(value) || value <= 0 || value > 1000) throw new IllegalArgumentException(key + " must be positive and at most 1000");
        }
        var curve = new Progression(ConfigValues.number(next, "progression.first-level-xp", 25), ConfigValues.number(next, "progression.early-level-xp", 150),
                ConfigValues.number(next, "progression.plateau-xp", 2500), (int) ConfigValues.integer(next, "progression.early-level", 10, 2, 9999),
                (int) ConfigValues.integer(next, "progression.plateau-level", 50, 3, 10000), 1);
        var loadedJobs = JobRegistry.prepare(new File(getDataFolder(), "jobs.yml"), curve);
        String jobsText = loadedJobs.upgraded() ? loadedJobs.configuration().saveToString()
                : java.nio.file.Files.readString(getDataFolder().toPath().resolve("jobs.yml"));
        ConfigFiles.save(getDataFolder().toPath(), next.saveToString(), jobsText);
        settings = next;
        registry = loadedJobs.registry();
    }

    private void refreshEconomy() {
        if (economy == null && getServer().getPluginManager().isPluginEnabled("Vault")) economy = new VaultPayments(this);
        boolean available = economy != null && economy.available();
        if (!Boolean.valueOf(available).equals(economyAvailable)) {
            economyAvailable = available;
            if (available) getLogger().info("Vault economy available; payouts are enabled.");
            else getLogger().warning("No active Vault economy provider. Job earnings will be saved until one becomes available.");
        }
    }
    private void scheduleTasks() {
        scheduled.forEach(BukkitTask::cancel);
        scheduled.clear();
        scheduled.add(Bukkit.getScheduler().runTaskTimer(this, this::save,
                settings.getLong("storage.autosave-seconds") * 20, settings.getLong("storage.autosave-seconds") * 20));
        scheduled.add(Bukkit.getScheduler().runTaskTimer(this, () -> { refreshEconomy(); queueUnpaid(); },
                settings.getLong("economy.payout-interval-seconds") * 20, settings.getLong("economy.payout-interval-seconds") * 20));
        scheduled.add(Bukkit.getScheduler().runTaskTimer(this, this::flushPayouts, 1, 1));
        scheduled.add(Bukkit.getScheduler().runTaskTimer(this, this::expireBars, 10, 10));
        scheduled.add(Bukkit.getScheduler().runTaskTimer(this, natural::flush, 100, 100));
        scheduled.add(Bukkit.getScheduler().runTaskTimer(this, activities::sampleTravel,
                settings.getLong("rules.travel-sample-ticks"), settings.getLong("rules.travel-sample-ticks")));
        activities.resetTravel();
    }

    public YamlConfiguration settings() { return settings; }
    public JobRegistry registry() { return registry; }
    public Map<UUID, PlayerProfile> players() { return players; }
    public PlayerProfile profile(Player player) {
        var profile = players.get(player.getUniqueId());
        if (profile == null) {
            profile = new PlayerProfile(player.getUniqueId(), player.getName());
            profile.notifications = settings.getBoolean("feedback.level-up-chat-default", true);
            players.put(profile.id, profile); dirty.add(profile.id);
        } else if (!profile.name.equals(player.getName())) { profile.name = player.getName(); dirty.add(profile.id); }
        return profile;
    }
    public BigDecimal hourlyLimit(JobRegistry.Job job) {
        return job.hourlyLimit() == null ? new BigDecimal(settings.getString("economy.hourly-income-limit-per-job", "100")) : job.hourlyLimit();
    }
    public boolean eligible(Player player) {
        return !storageFailed && player.hasPermission("justjobs.earn") && !player.isDead()
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                && !settings.getStringList("rules.excluded-worlds").contains(player.getWorld().getName());
    }

    public void award(Player player, JobRegistry.Activity activity, Material material, EntityType entity, double units) {
        award(player, activity, material, entity, units, true);
    }

    public void award(Player player, JobRegistry.Activity activity, Material material, EntityType entity, double units, boolean naturalBlock) {
        if (!eligible(player) || !Double.isFinite(units) || units <= 0) return;
        var profile = profile(player);
        long now = System.currentTimeMillis();
        for (var job : registry.jobs()) {
            if (activity == JobRegistry.Activity.BLOCK_BREAK && job.naturalOnly() && !naturalBlock) continue;
            double rewardUnits = units;
            if ((activity == JobRegistry.Activity.TRAVEL || activity == JobRegistry.Activity.SAIL) && job.rules().stream().anyMatch(r -> r.activity() == activity)) {
                rewardUnits = profile.travel.add(job.id(), units, job.distancePerReward());
                dirty.add(profile.id);
                if (rewardUnits == 0) continue;
            }
            double xp = 0;
            BigDecimal money = BigDecimal.ZERO;
            for (var rule : job.rules()) if (rule.matches(activity, material, entity)) {
                xp += rule.xp() * rewardUnits;
                money = money.add(rule.money().multiply(BigDecimal.valueOf(rewardUnits)));
            }
            if (xp == 0 && money.signum() == 0) continue;
            double before = profile.xp(job.id());
            double after = before + xp;
            if (!Double.isFinite(after)) continue;
            profile.xp.put(job.id(), after);
            BigDecimal credited = profile.earnings.credit(job.id(), money, hourlyLimit(job), now);
            profile.pending = profile.pending.add(credited);
            dirty.add(profile.id);
            if (credited.signum() > 0) { payoutQueue.add(profile.id); unpaid.add(profile.id); }
            var progress = job.progression().progress(after);
            if (settings.getBoolean("feedback.level-up-chat", true) && profile.notifications && progress.level() > job.progression().progress(before).level())
                player.sendMessage(Component.text("Reached level " + progress.level() + " " + job.name(), NamedTextColor.GREEN));
            if (settings.getBoolean("feedback.boss-bar", true) && profile.bossBar) {
                var display = bars.get(player.getUniqueId());
                if (display != null && !display.job().equals(job.id()) && now - display.shown() < 2000) continue;
                var bar = display == null ? BossBar.bossBar(Component.empty(), 0, job.color(), BossBar.Overlay.PROGRESS) : display.bar();
                boolean capped = hourlyLimit(job).signum() > 0 && profile.earnings.total(job.id(), now).compareTo(hourlyLimit(job)) >= 0;
                bar.name(Component.text(job.name() + " • Level " + progress.level() + " • " + format(progress.xp()) + " / " + format(progress.required()) + " XP"
                        + (capped ? " • Hourly money limit reached" : "")));
                bar.progress((float) progress.fraction()); bar.color(job.color());
                if (display == null) player.showBossBar(bar);
                long shown = display == null || !display.job().equals(job.id()) ? now : display.shown();
                bars.put(player.getUniqueId(), new Display(bar, job.id(), shown, now + settings.getLong("feedback.boss-bar-seconds", 6) * 1000));
            }
        }
    }

    public static String format(double value) { return String.format(java.util.Locale.ROOT, "%,.1f", value); }
    public boolean pvpAllowed(UUID first, UUID second) {
        String a = first.toString(), b = second.toString();
        String key = a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
        long now = System.currentTimeMillis();
        if (now - pvpPairs.getOrDefault(key, 0L) < settings.getLong("rules.pvp-pair-cooldown-seconds", 60) * 1000) return false;
        pvpPairs.put(key, now);
        dirtyPairs.put(key, now);
        return true;
    }

    private void expireBars() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(bars.keySet())) if (bars.get(id).expires() <= now || Bukkit.getPlayer(id) == null) hideBar(id);
    }
    public void hideBar(UUID id) {
        var display = bars.remove(id);
        var player = Bukkit.getPlayer(id);
        if (display != null && player != null) player.hideBossBar(display.bar());
    }

    @FunctionalInterface interface StorageWork { void run() throws Exception; }
    void submit(StorageWork action) {
        if (worker == null || worker.isShutdown() || storageFailed) return;
        worker.submit(() -> {
            try { action.run(); }
            catch (Exception e) {
                storageFailed = true;
                getLogger().log(Level.SEVERE, "Storage failed; rewards have been stopped to protect saved balances.", e);
                main(() -> getServer().getPluginManager().disablePlugin(this));
            }
        });
    }
    void main(Runnable action) { if (isEnabled()) getServer().getScheduler().runTask(this, action); }
    public void save() {
        if (natural != null) natural.flush();
        var snapshots = dirty.stream().map(players::get).map(PlayerProfile::snapshot).toList();
        var pairs = Map.copyOf(dirtyPairs);
        dirty.clear(); dirtyPairs.clear();
        long now = System.currentTimeMillis();
        boolean prune = now - lastPrune >= 3_600_000;
        if (prune) { lastPrune = now; pvpPairs.entrySet().removeIf(e -> now - e.getValue() > 86_400_000); }
        if (!snapshots.isEmpty() || !pairs.isEmpty() || prune) submit(() -> {
            if (!snapshots.isEmpty() || !pairs.isEmpty()) store.save(snapshots, pairs);
            if (prune) store.prune(now);
        });
    }

    private void queueUnpaid() {
        payoutQueue.addAll(unpaid);
    }
    private void flushPayouts() {
        if (economy == null || storageFailed || paymentBatchInFlight) return;
        int budget = 25;
        var reservations = new ArrayList<DataStore.Reservation>();
        var candidates = payoutQueue.iterator();
        while (candidates.hasNext() && budget-- > 0) {
            var id = candidates.next(); candidates.remove();
            var profile = players.get(id);
            if (paying.contains(profile.id)) continue;
            BigDecimal amount = economy.payable(profile.pending);
            if (amount.signum() <= 0) continue;
            paying.add(profile.id);
            profile.pending = profile.pending.subtract(amount);
            dirty.add(profile.id);
            var payment = new DataStore.Payment(UUID.randomUUID().toString(), profile.id, profile.name, amount);
            reservations.add(new DataStore.Reservation(profile.snapshot(), payment));
        }
        if (reservations.isEmpty()) return;
        paymentBatchInFlight = true;
        submit(() -> {
            store.reserve(reservations);
            main(() -> executePayments(reservations));
        });
    }

    private void executePayments(List<DataStore.Reservation> reservations) {
        executePayments(reservations, true);
    }
    private void executePayments(List<DataStore.Reservation> reservations, boolean ownsBatch) {
        if (closing) return; // The durable reservation is recovered automatically on the next start.
        var results = new HashMap<String, PaymentDelivery.Result>();
        var completed = new ArrayList<DataStore.Reservation>();
        var waiting = new ArrayList<DataStore.Reservation>();
        for (var reservation : reservations) {
            var payment = reservation.payment();
            var result = economy == null ? new PaymentDelivery.Result(BigDecimal.ZERO, null, true) : economy.deposit(payment);
            if (result.complete()) { completed.add(reservation); results.put(payment.id(), result); }
            else waiting.add(reservation);
        }
        if (!waiting.isEmpty()) Bukkit.getScheduler().runTaskLater(this, () -> executePayments(waiting, false), 20);
        if (completed.isEmpty()) {
            if (ownsBatch) paymentBatchInFlight = false;
            return;
        }
        var settlements = new ArrayList<DataStore.Settlement>();
        var succeeded = new HashSet<UUID>();
        for (var reservation : completed) {
            var payment = reservation.payment();
            var profile = players.get(payment.player());
            BigDecimal credited = results.get(payment.id()).credited();
            profile.pending = profile.pending.add(payment.amount().subtract(credited));
            if (credited.signum() > 0) succeeded.add(profile.id);
            dirty.add(profile.id);
            settlements.add(new DataStore.Settlement(payment.id(), credited.compareTo(payment.amount()) == 0 ? "PAID" : credited.signum() > 0 ? "PARTIAL" : "REFUNDED", profile.snapshot()));
        }
        submit(() -> {
            store.settle(settlements);
            main(() -> {
                for (var settlement : settlements) {
                    var profile = players.get(settlement.player().id());
                    paying.remove(profile.id);
                    if (profile.pending.signum() == 0) unpaid.remove(profile.id);
                    if (succeeded.contains(profile.id) && profile.pending.signum() > 0) payoutQueue.add(profile.id);
                }
                if (settings.getBoolean("economy.payout-chat", false)) for (var reservation : completed) {
                    var payment = reservation.payment(); var credited = results.get(payment.id()).credited();
                    var player = Bukkit.getPlayer(payment.player());
                    if (player != null && credited.signum() > 0) player.sendMessage(Component.text("Jobs paid " + currencyRate(credited) + ".", NamedTextColor.GOLD));
                }
                if (ownsBatch) paymentBatchInFlight = false;
            });
        });
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        if (List.of("reload", "earnings", "backup").contains(sub)) {
            if (!sender.hasPermission("justjobs.admin")) { sender.sendMessage("You don't have permission to do that."); return true; }
            if (sub.equals("backup")) backup(sender);
            else if (sub.equals("reload")) {
                try {
                    loadSettings();
                    refreshEconomy();
                    scheduleTasks();
                    queueUnpaid();
                    menus.clearRankings();
                    for (var player : Bukkit.getOnlinePlayers()) if (player.getOpenInventory().getTopInventory().getHolder() instanceof JobsMenu.Page) player.closeInventory();
                    for (var id : new ArrayList<>(bars.keySet())) hideBar(id);
                    sender.sendMessage("Reloaded " + registry.jobs().size() + " jobs and all settings.");
                } catch (Exception e) { sender.sendMessage("Reload rejected; previous settings retained: " + e.getMessage()); }
            } else {
                String name = args.length > 1 ? args[1] : sender.getName();
                var profile = players.values().stream().filter(p -> p.name.equalsIgnoreCase(name)).findFirst().orElse(null);
                if (profile == null) { sender.sendMessage("No saved job progress for " + name + ". Usage: /jobs earnings <player>"); return true; }
                sender.sendMessage(profile.name + " — earnings in the rolling hour:");
                long now = System.currentTimeMillis();
                for (var job : registry.jobs()) sender.sendMessage(job.name() + ": " + currency(profile.earnings.total(job.id(), now))
                        + " / " + (hourlyLimit(job).signum() == 0 ? "unlimited" : currency(hourlyLimit(job))));
                sender.sendMessage("Unpaid earnings: " + currency(profile.pending));
            }
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("Open jobs in-game, or use /jobs reload, /jobs backup or /jobs earnings <player>."); return true; }
        if (sub.equals("notifications")) {
            toggleNotifications(player);
        } else if (sub.equals("bossbar")) toggleBossBar(player);
        else if (sub.equals("leaderboard")) menus.open(player, true, 0, null);
        else if (sub.isEmpty()) menus.open(player, false, 0, null);
        else player.sendMessage("Usage: /jobs [leaderboard|notifications|bossbar]");
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        var choices = new ArrayList<>(List.of("leaderboard", "notifications", "bossbar"));
        if (sender.hasPermission("justjobs.admin")) choices.addAll(List.of("reload", "earnings", "backup"));
        return choices.stream().filter(s -> s.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
    }

    private void backup(CommandSender sender) {
        save();
        submit(() -> {
            if (storageFailed) { main(() -> sender.sendMessage("Backup stopped because storage is unavailable.")); return; }
            try {
                var folder = getDataFolder().toPath().resolve("backups");
                java.nio.file.Files.createDirectories(folder);
                String stamp = java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS")
                        .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
                var target = folder.resolve("jobs-" + stamp + ".db");
                store.backup(target);
                main(() -> sender.sendMessage("Saved job data to JustJobs/backups/" + target.getFileName() + ". Back up worlds and economy data separately."));
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Could not back up job data", e);
                main(() -> sender.sendMessage("Could not save the job backup. Check the server log."));
            }
        });
    }

    @Override public void onDisable() {
        closing = true;
        scheduled.forEach(BukkitTask::cancel);
        if (activities != null) activities.resetTravel();
        for (UUID id : new ArrayList<>(bars.keySet())) hideBar(id);
        for (var player : Bukkit.getOnlinePlayers()) if (player.getOpenInventory().getTopInventory().getHolder() instanceof JobsMenu.Page) player.closeInventory();
        if (worker != null) {
            if (!storageFailed) save();
            worker.shutdown();
            try {
                if (!worker.awaitTermination(30, TimeUnit.SECONDS)) { getLogger().severe("Storage did not finish within 30 seconds; preserving the open database for the worker."); return; }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        if (store != null) try { store.close(); } catch (Exception e) { getLogger().log(Level.SEVERE, "Could not close storage", e); }
    }

    public void toggleBossBar(Player player) {
        var profile = profile(player);
        profile.bossBar = !profile.bossBar;
        dirty.add(profile.id);
        if (!profile.bossBar) hideBar(profile.id);
        player.sendMessage("Job boss bar " + (profile.bossBar ? "enabled." : "disabled."));
        save();
    }
    public void toggleNotifications(Player player) {
        var profile = profile(player);
        profile.notifications = !profile.notifications;
        dirty.add(profile.id);
        player.sendMessage("Job level-up messages " + (profile.notifications ? "enabled." : "disabled."));
        save();
    }
    public String currencyRate(BigDecimal value) {
        return settings.getString("economy.currency-symbol", "$") + value.stripTrailingZeros().toPlainString();
    }
    public String currency(BigDecimal value) {
        String symbol = settings.getString("economy.currency-symbol", "$");
        return value.signum() > 0 && value.compareTo(new BigDecimal("0.01")) < 0 ? "<" + symbol + "0.01" : symbol + money(value);
    }
    public static String money(BigDecimal value) {
        if (value.signum() > 0 && value.compareTo(new BigDecimal("0.01")) < 0) return "<0.01";
        return value.setScale(2, java.math.RoundingMode.DOWN).toPlainString();
    }
}
