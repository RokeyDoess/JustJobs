package checks;

import io.github.rokeydoess.justjobs.*;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Provider-independent payout failures; the disposable server installs Vault only. */
public final class PaymentChecks extends ReleaseChecks {
    static final UUID CANCELLED = new UUID(3, 1), PARTIAL = new UUID(3, 2), UNKNOWN = new UUID(3, 3), BEFORE = new UUID(3, 4), HEALTHY = new UUID(3, 5), NEW_ACCOUNT = new UUID(3, 6);
    final Set<UUID> unreadable = new HashSet<>();
    final Map<UUID, Integer> attempts = new HashMap<>();
    boolean cancel = true, partial = true, loseBalanceRead = true;
    boolean accountExists, allowAccountCreation;
    int accountCreations;

    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "JustJobs runs with Vault as the only required plugin");
        check(Bukkit.getPluginManager().getPlugin("Essentials") == null, "generic-provider tests have no Essentials installation");
        world = Bukkit.getWorlds().getFirst();
        worker = (ExecutorService) field(jobs, "worker"); store = (DataStore) field(jobs, "store");
        installProvider();
        if (Files.exists(getDataFolder().toPath().resolve("restart"))) {
            for (UUID id : List.of(CANCELLED, UNKNOWN, BEFORE, HEALTHY, NEW_ACCOUNT)) check(jobs.players().get(id).pending.compareTo(new BigDecimal("0.5")) == 0, "remainder survives restart for " + id);
            check(jobs.players().get(PARTIAL).pending.compareTo(new BigDecimal("0.25")) == 0, "partial-credit remainder survives restart");
            later(30, () -> {
                check(attempts.isEmpty() && worker.submit(store::unresolved).get().isEmpty(), "settled deposits are not repeated after restart");
                getLogger().info("RELEASE CHECKS PHASE 2 PASS: " + checks + " generic-provider checks"); Bukkit.shutdown();
            }); return;
        }
        var config = YamlConfiguration.loadConfiguration(jobs.getDataFolder().toPath().resolve("config.yml").toFile());
        config.set("economy.payout-interval-seconds", 1); config.set("storage.autosave-seconds", 1);
        config.save(jobs.getDataFolder().toPath().resolve("config.yml").toFile()); reload();
        for (UUID id : List.of(CANCELLED, PARTIAL, UNKNOWN)) award(id);
        later(60, () -> {
            check(attempts.get(UNKNOWN) == 1 && balances.get(UNKNOWN).compareTo(new BigDecimal("12")) == 0, "an unreadable post-deposit balance does not repeat the deposit");
            check(balances.getOrDefault(CANCELLED, BigDecimal.ZERO).signum() == 0, "a canceled success response credits no money");
            check(balances.get(PARTIAL).compareTo(new BigDecimal("0.25")) == 0, "provider can apply only part of a deposit");
            award(HEALTHY);
            reload();
            later(40, () -> {
                check(attempts.get(UNKNOWN) == 1, "reload retains the uncertain deposit without another credit");
                check(attempts.getOrDefault(HEALTHY, 0) == 1 && balances.get(HEALTHY).compareTo(new BigDecimal("12")) == 0
                                && jobs.players().get(HEALTHY).pending.compareTo(new BigDecimal("0.5")) == 0,
                        "an unreadable account does not delay another player's new earnings or saved payment confirmation");
                cancel = false; partial = false; loseBalanceRead = false; unreadable.clear();
                later(80, this::afterRecovery);
            });
        });
    }
    void reload() { jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"}); }
    void award(UUID id) { jobs.award(player(id, "Wallet" + id.getLeastSignificantBits()), JobRegistry.Activity.PLAYER_KILL, null, null, 1.25); }
    void afterRecovery() throws Exception {
        check(attempts.get(UNKNOWN) == 1, "balance recovery settles the original deposit once");
        check(balances.get(CANCELLED).compareTo(new BigDecimal("12")) == 0 && jobs.players().get(CANCELLED).pending.compareTo(new BigDecimal("0.5")) == 0, "canceled payments recover automatically without losing the fraction");
        check(balances.get(PARTIAL).compareTo(new BigDecimal("12.25")) == 0 && jobs.players().get(PARTIAL).pending.compareTo(new BigDecimal("0.25")) == 0, "only the unpaid part of a partial deposit is retried");
        unreadable.add(BEFORE); award(BEFORE);
        later(40, () -> {
            check(attempts.getOrDefault(BEFORE, 0) == 0, "an unreadable starting balance prevents an unverifiable deposit");
            unreadable.remove(BEFORE);
            later(50, () -> {
                check(attempts.get(BEFORE) == 1 && balances.get(BEFORE).compareTo(new BigDecimal("12")) == 0, "payment resumes automatically when the starting balance returns");
                newAccountChecks();
            });
        });
    }
    void newAccountChecks() {
        award(NEW_ACCOUNT);
        later(40, () -> {
            check(accountCreations > 0 && attempts.getOrDefault(NEW_ACCOUNT, 0) == 0, "failed account creation retains the reward without attempting an invalid deposit");
            allowAccountCreation = true;
            later(50, () -> {
                check(accountExists && attempts.getOrDefault(NEW_ACCOUNT, 0) == 1 && balances.get(NEW_ACCOUNT).compareTo(new BigDecimal("12")) == 0,
                        "a provider requiring explicit account creation recovers automatically");
                jobs.save(); worker.submit(() -> {}).get();
                check(worker.submit(store::unresolved).get().isEmpty(), "all failure cases settle without an admin");
                Files.createDirectories(getDataFolder().toPath()); Files.writeString(getDataFolder().toPath().resolve("restart"), "ready");
                getLogger().info("RELEASE CHECKS PHASE 1 PASS: " + checks + " generic-provider checks"); Bukkit.shutdown();
            });
        });
    }
    @Override void installProvider() {
        super.installProvider(); Economy delegate = provider;
        Bukkit.getServicesManager().unregister(Economy.class, delegate);
        provider = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[]{Economy.class}, (proxy, method, args) -> {
            UUID id = args != null && args.length > 0 && args[0] instanceof OfflinePlayer p ? p.getUniqueId() : null;
            if (NEW_ACCOUNT.equals(id) && method.getName().equals("hasAccount")) return accountExists;
            if (NEW_ACCOUNT.equals(id) && method.getName().equals("createPlayerAccount")) {
                accountCreations++; accountExists = allowAccountCreation; return accountExists;
            }
            if (method.getName().equals("getBalance") && unreadable.contains(id)) throw new IllegalStateException("Temporary unavailable balance");
            if (method.getName().equals("depositPlayer")) {
                attempts.merge(id, 1, Integer::sum);
                if (id.equals(CANCELLED) && cancel) return new EconomyResponse((double) args[1], 0, EconomyResponse.ResponseType.SUCCESS, "Canceled credit");
                if (id.equals(PARTIAL) && partial) {
                    if (balances.getOrDefault(id, BigDecimal.ZERO).compareTo(new BigDecimal("0.25")) >= 0)
                        return new EconomyResponse(0, 0.25, EconomyResponse.ResponseType.FAILURE, "Account limit");
                    args[1] = 0.25d;
                }
                try {
                    Object result = method.invoke(delegate, args);
                    if (id.equals(UNKNOWN) && loseBalanceRead) unreadable.add(id);
                    return result;
                } catch (InvocationTargetException e) { throw e.getCause(); }
            }
            try { return method.invoke(delegate, args); } catch (InvocationTargetException e) { throw e.getCause(); }
        });
        Bukkit.getServicesManager().register(Economy.class, provider, this, ServicePriority.Highest);
    }
}
