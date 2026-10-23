package checks;

import io.github.rokeydoess.justjobs.*;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;

/** Optional real-provider checks, installed only in the disposable test server. */
public final class EconomyChecks extends ReleaseChecks {
    org.bukkit.OfflinePlayer normal;
    org.bukkit.OfflinePlayer limited;
    PlayerProfile limitedProfile;
    BigDecimal maximum;
    BigDecimal cappedCredit;

    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "JustJobs starts with a real Vault economy");
        world = Bukkit.getWorlds().getFirst();
        provider = Bukkit.getServicesManager().getRegistration(Economy.class).getProvider();
        check(provider.getClass().getName().contains("essentials"), "compatibility run uses the real EssentialsX Vault provider");
        worker = (ExecutorService) field(jobs, "worker");
        store = (DataStore) field(jobs, "store");
        normal = Bukkit.getOfflinePlayer("WalletNormal");
        limited = Bukkit.getOfflinePlayer("WalletLimit");
        player = player(normal.getUniqueId(), normal.getName());
        profile = jobs.profile(player);
        limitedProfile = jobs.profile(player(limited.getUniqueId(), limited.getName()));
        var essentials = Bukkit.getPluginManager().getPlugin("Essentials");
        Object settings = essentials.getClass().getMethod("getSettings").invoke(essentials);
        maximum = (BigDecimal) settings.getClass().getMethod("getMaxMoney").invoke(settings);
        if (Files.exists(getDataFolder().toPath().resolve("restart"))) {
            check(balance(normal).compareTo(new BigDecimal("112")) == 0, "real provider keeps the paid balance after restart");
            check(profile.pending.compareTo(new BigDecimal("0.5")) == 0, "fractional remainder survives restart");
            cappedCredit = new BigDecimal(Files.readString(getDataFolder().toPath().resolve("restart")));
            check(balance(limited).compareTo(maximum.subtract(new BigDecimal("8.25")).add(cappedCredit)) == 0, "balance-limited payout stays correct after restart");
            check(limitedProfile.pending.compareTo(new BigDecimal("0.5").subtract(cappedCredit)) == 0, "balance-limited remainder survives restart");
            getLogger().info("RELEASE CHECKS PHASE 2 PASS: " + checks + " real-provider checks");
            Bukkit.shutdown(); return;
        }
        check(provider.createPlayerAccount(normal), "real provider creates an offline account");
        check(provider.createPlayerAccount(limited), "real provider creates the second account");
        setBalance(normal, new BigDecimal("100"));
        setBalance(limited, maximum.subtract(new BigDecimal("0.25")));
        var config = jobs.settings();
        config.set("economy.payout-interval-seconds", 1);
        config.set("storage.autosave-seconds", 1);
        config.save(jobs.getDataFolder().toPath().resolve("config.yml").toFile());
        jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"});
        jobs.award(player, JobRegistry.Activity.PLAYER_KILL, null, null, 1.25);
        jobs.award(player(limited.getUniqueId(), limited.getName()), JobRegistry.Activity.PLAYER_KILL, null, null, 1.25);
        later(80, () -> {
            Bukkit.getServicesManager().unregister(Economy.class, provider);
            later(5, this::checkLimitedBalance);
        });
    }
    void checkLimitedBalance() throws Exception {
            check(balance(normal).compareTo(new BigDecimal("112")) == 0, "whole-number payout arrives through the real provider");
            check(profile.pending.compareTo(new BigDecimal("0.5")) == 0, "only the unpaid fraction is retained");
            cappedCredit = balance(limited).subtract(maximum.subtract(new BigDecimal("0.25")));
            check(cappedCredit.signum() >= 0 && cappedCredit.compareTo(new BigDecimal("0.25")) <= 0, "real provider respects its maximum balance");
            check(limitedProfile.pending.compareTo(new BigDecimal("12.5").subtract(cappedCredit)) == 0, "money not credited by the balance-limited provider is retained (pending=" + limitedProfile.pending + ", credited=" + cappedCredit + ")");
            provider.withdrawPlayer(limited, 20);
            Bukkit.getServicesManager().register(Economy.class, provider, this, org.bukkit.plugin.ServicePriority.Normal);
            later(80, () -> {
                check(balance(limited).compareTo(maximum.subtract(new BigDecimal("8.25")).add(cappedCredit)) == 0, "retained money pays automatically when the account has room");
                check(limitedProfile.pending.compareTo(new BigDecimal("0.5").subtract(cappedCredit)) == 0, "payout retries preserve the exact fraction");
                jobs.save();
                worker.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
                Files.createDirectories(getDataFolder().toPath());
                Files.writeString(getDataFolder().toPath().resolve("restart"), cappedCredit.toPlainString());
                getLogger().info("RELEASE CHECKS PHASE 1 PASS: " + checks + " real-provider checks");
                Bukkit.shutdown();
            });
    }
    BigDecimal balance(org.bukkit.OfflinePlayer account) { return BigDecimal.valueOf(provider.getBalance(account)).stripTrailingZeros(); }
    void setBalance(org.bukkit.OfflinePlayer account, BigDecimal target) {
        BigDecimal delta = target.subtract(balance(account));
        var result = delta.signum() < 0 ? provider.withdrawPlayer(account, delta.negate().doubleValue()) : provider.depositPlayer(account, delta.doubleValue());
        check(result.transactionSuccess() && balance(account).compareTo(target) == 0, "test account starts at its requested balance");
    }
}
