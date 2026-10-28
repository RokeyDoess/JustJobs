package checks;

import io.github.rokeydoess.justjobs.*;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.plugin.ServicePriority;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Real scheduler and Vault checks with simulated clock boundaries, including offline accounts. */
public final class PaydayChecks extends ReleaseChecks {
    static final UUID OFFLINE = new UUID(8, 1), PARTIAL = new UUID(8, 2), CAPPED = new UUID(8, 3);
    boolean allowPartialRemainder;
    PlayerProfile partial;
    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled() && jobs.paydayEnabled(), "hourly paydays are enabled by default");
        if (!Files.exists(getDataFolder().toPath().resolve("wallets.yml")))
            check(jobs.paydayInterval() == 3_600_000, "default paydays use a one-hour period");
        check(jobs.nextPayday() % jobs.paydayInterval() == 0, "paydays share the configured real-time clock boundary");
        world = Bukkit.getWorlds().getFirst();
        player = player(MAIN, "PaydayChecks"); profile = jobs.profile(player);
        worker = (ExecutorService) field(jobs, "worker"); store = (DataStore) field(jobs, "store");
        installProvider(); installPartialProvider();
        var walletFile = getDataFolder().toPath().resolve("wallets.yml");
        if (Files.exists(walletFile)) {
            var wallets = YamlConfiguration.loadConfiguration(walletFile.toFile());
            for (String id : wallets.getKeys(false)) balances.put(UUID.fromString(id), new BigDecimal(wallets.getString(id)));
            later(40, this::afterRestart); return;
        }
        var file = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(file);
        config.set("economy.payout-interval-seconds", 1); config.set("storage.autosave-seconds", 1);
        config.set("development.verbose-logging", true); config.set("development.summary-interval-seconds", 10);
        // Fixed amounts make retry, partial-credit and period-boundary assertions independent of default balancing.
        config.set("economy.hourly-income-limit-per-job", "100");
        var jobFile = jobs.getDataFolder().toPath().resolve("jobs.yml").toFile();
        var jobConfig = YamlConfiguration.loadConfiguration(jobFile);
        var duelRule = new java.util.LinkedHashMap<String, Object>();
        jobConfig.getMapList("jobs.dueling.rules").getFirst().forEach((key, value) -> duelRule.put(key.toString(), value));
        duelRule.put("money", "5"); jobConfig.set("jobs.dueling.rules", List.of(duelRule)); jobConfig.save(jobFile);
        config.save(file); reload();
        check(profile.paydayChat, "payday chat starts enabled");
        jobs.award(player, JobRegistry.Activity.PLAYER_KILL, null, null, 1);
        jobs.award(player, JobRegistry.Activity.FISH, Material.COD, null, 5);
        jobs.award(player(OFFLINE, "OfflineWallet"), JobRegistry.Activity.PLAYER_KILL, null, null, 2);
        jobs.award(player(PARTIAL, "PartialWallet"), JobRegistry.Activity.PLAYER_KILL, null, null, 1);
        partial = jobs.players().get(PARTIAL);
        jobs.award(player(CAPPED, "PeriodCap"), JobRegistry.Activity.PLAYER_KILL, null, null, 30);
        var capped = jobs.players().get(CAPPED);
        check(capped.pending.compareTo(new BigDecimal("100")) == 0 && capped.xp("dueling") == 300,
                "payday mode caps money without stopping job XP");
        reload();
        jobs.award(player(CAPPED, "PeriodCap"), JobRegistry.Activity.PLAYER_KILL, null, null, 1);
        check(capped.pending.compareTo(new BigDecimal("100")) == 0 && capped.xp("dueling") == 310,
                "reload cannot reset the current paycheck's cap");
        later(30, this::beforePayday);
    }
    void reload() { jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"}); }
    void queue() throws Exception { var method = jobs.getClass().getDeclaredMethod("queueUnpaid"); method.setAccessible(true); method.invoke(jobs); }
    void beforePayday() throws Exception {
        check(balances.isEmpty() && profile.pending.compareTo(new BigDecimal("6.25")) == 0, "whole earnings wait for payday instead of paying immediately");
        var menus = new JobsMenu(jobs); menus.open(player, false, 0, null);
        check(current.getItem(39).getType() == Material.CLOCK && current.getItem(39).getItemMeta().lore().stream().anyMatch(c -> c.toString().contains("6.25")),
                "Next paycheck shows the distribution time and earned amount");
        check(current.getItem(23).getItemMeta().lore().stream().anyMatch(c -> plain(c).equals("Earned this pay period: $5.00 / $100.00")),
                "the jobs menu describes the active paycheck cap period");
        menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 36, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        later(2, () -> {
            check(current.getSize() == 27, "the jobs page opens the separate settings menu");
            menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 14, ClickType.LEFT, InventoryAction.PICKUP_ALL));
            later(2, () -> {
                check(!profile.paydayChat && current.getSize() == 27, "payday chat toggles from settings without leaving the menu");
                jobs.togglePaydayChat(player); check(profile.paydayChat, "payday chat can be enabled again");
                for (var p : List.of(profile, jobs.players().get(OFFLINE), partial)) p.paycheck.restoreNext(System.currentTimeMillis() - 1);
                var capped = jobs.players().get(CAPPED);
                capped.paycheck.restoreNext(System.currentTimeMillis() - 1);
                check(jobs.capEarnings(capped, "dueling", System.currentTimeMillis()).signum() == 0,
                        "the displayed cap resets at the scheduled cutoff before new activity");
                jobs.award(player(CAPPED, "PeriodCap"), JobRegistry.Activity.PLAYER_KILL, null, null, 30);
                check(capped.pending.compareTo(new BigDecimal("200")) == 0
                                && jobs.capEarnings(capped, "dueling", System.currentTimeMillis()).compareTo(new BigDecimal("100")) == 0,
                        "a new paycheck grants a full allowance despite the previous rolling-hour history");
                jobs.award(player, JobRegistry.Activity.PLAYER_KILL, null, null, 1);
                jobs.award(player(PARTIAL, "PartialWallet"), JobRegistry.Activity.PLAYER_KILL, null, null, 1);
                queue(); later(45, this::afterPayday);
            });
        });
    }
    void afterPayday() throws Exception {
        check(balances.get(CAPPED).compareTo(new BigDecimal("100")) == 0 && jobs.players().get(CAPPED).pending.compareTo(new BigDecimal("100")) == 0,
                "the old capped paycheck pays while the new capped earnings wait for their own payday");
        check(balances.get(MAIN).compareTo(new BigDecimal("6")) == 0 && profile.pending.compareTo(new BigDecimal("5.25")) == 0,
                "payday deposits whole money, keeps the fraction and holds the next period's earnings");
        check(profile.paycheck.available().compareTo(new BigDecimal("0.25")) == 0 && profile.paycheck.snapshot().current().get("dueling").compareTo(new BigDecimal("5")) == 0,
                "new earnings are separated from the released paycheck");
        check(balances.get(OFFLINE).compareTo(new BigDecimal("10")) == 0 && jobs.players().get(OFFLINE).pending.signum() == 0,
                "offline players receive their paycheck without joining");
        check(balances.get(PARTIAL).compareTo(new BigDecimal("2")) == 0 && partial.pending.compareTo(new BigDecimal("8")) == 0,
                "a partial payday credit preserves both its unpaid part and the new period");
        var breakdown = Map.of("dueling", new BigDecimal("5"), "fishing", BigDecimal.ONE, "mining", BigDecimal.ZERO);
        jobs.notifyPayday(player, profile, new BigDecimal("6"), breakdown);
        var message = components.get(MAIN).getLast();
        check(plain(message).equals("Payday! $6 has been deposited into your account."), "payday message states the amount deposited");
        var payday = message.children().getFirst();
        check(payday.hoverEvent() != null && plain((net.kyori.adventure.text.Component) payday.hoverEvent().value()).contains("Dueling: $5")
                        && plain((net.kyori.adventure.text.Component) payday.hoverEvent().value()).contains("Fishing: $1")
                        && !plain((net.kyori.adventure.text.Component) payday.hoverEvent().value()).contains("Mining")
                        && message.hoverEvent() == null && message.children().getLast().hoverEvent() == null,
                "only Payday has the nonzero per-job breakdown hover");
        jobs.togglePaydayChat(player); int count = components.get(MAIN).size();
        jobs.notifyPayday(player, profile, BigDecimal.ONE, breakdown);
        check(components.get(MAIN).size() == count, "the saved preference suppresses payday messages");
        allowPartialRemainder = true;
        later(50, this::afterRetry);
    }
    void afterRetry() throws Exception {
        check(balances.get(PARTIAL).compareTo(new BigDecimal("5")) == 0 && partial.pending.compareTo(new BigDecimal("5")) == 0,
                "partial payments retry automatically without paying the new period early");
        check(worker.submit(store::unresolved).get().isEmpty(), "payday reservations settle without admin intervention");
        var file = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(file); config.set("economy.payday-interval-minutes", 120); config.save(file); reload();
        check(profile.pending.compareTo(new BigDecimal("5.25")) == 0 && profile.paycheck.snapshot().next() == jobs.nextPayday(), "reload changes the period without losing earnings");
        profile.paycheck.restoreNext(System.currentTimeMillis() - 1);
        jobs.save(); worker.submit(() -> {}).get();
        Files.createDirectories(getDataFolder().toPath());
        var wallets = new YamlConfiguration(); balances.forEach((id, amount) -> wallets.set(id.toString(), amount.toPlainString()));
        wallets.save(getDataFolder().toPath().resolve("wallets.yml").toFile());
        getLogger().info("RELEASE CHECKS PHASE 1 PASS: " + checks + " payday checks"); Bukkit.shutdown();
    }
    void afterRestart() throws Exception {
        var capped = jobs.players().get(CAPPED);
        jobs.award(player(CAPPED, "PeriodCap"), JobRegistry.Activity.PLAYER_KILL, null, null, 1);
        check(jobs.capEarnings(capped, "dueling", System.currentTimeMillis()).compareTo(new BigDecimal("100")) == 0
                        && capped.pending.compareTo(new BigDecimal("100")) == 0,
                "the paycheck cap survives restart and a custom two-hour schedule");
        check(!profile.paydayChat, "payday chat preference survives restart");
        check(balances.get(MAIN).compareTo(new BigDecimal("11")) == 0 && profile.pending.compareTo(new BigDecimal("0.25")) == 0,
                "a payday missed during downtime is settled after startup with its fraction retained");
        check(balances.get(OFFLINE).compareTo(new BigDecimal("10")) == 0, "settled offline paychecks are not repeated after restart");
        check(partial == null && jobs.players().get(PARTIAL).pending.compareTo(new BigDecimal("5")) == 0, "future-period earnings stay held across restart");
        check(worker.submit(store::unresolved).get().isEmpty(), "restart leaves no unresolved payday payment");
        getLogger().info("RELEASE CHECKS PHASE 2 PASS: " + checks + " payday checks"); Bukkit.shutdown();
    }
    static String plain(net.kyori.adventure.text.Component c) {
        String result = c instanceof net.kyori.adventure.text.TextComponent t ? t.content() : "";
        for (var child : c.children()) result += plain(child);
        return result;
    }
    void installPartialProvider() {
        Economy delegate = provider; Bukkit.getServicesManager().unregister(Economy.class, delegate);
        provider = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[]{Economy.class}, (p, method, args) -> {
            if (method.getName().equals("depositPlayer") && ((OfflinePlayer) args[0]).getUniqueId().equals(PARTIAL) && !allowPartialRemainder) {
                if (balances.getOrDefault(PARTIAL, BigDecimal.ZERO).signum() > 0) return new EconomyResponse(0, 2, EconomyResponse.ResponseType.FAILURE, "Temporary account limit");
                args[1] = 2d;
            }
            try { return method.invoke(delegate, args); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        Bukkit.getServicesManager().register(Economy.class, provider, this, ServicePriority.Highest);
    }
}
