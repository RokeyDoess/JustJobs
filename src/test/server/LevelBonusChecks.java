package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Milestone deposits, scaling, cap exemption, disabled periods, and saved claims on Paper. */
public final class LevelBonusChecks extends ReleaseChecks {
    final UUID fractionId = new UUID(82, 1);
    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "level bonuses start on Paper");
        world = Bukkit.getWorlds().getFirst(); player = player(MAIN, "LevelBonusPlayer"); profile = jobs.profile(player);
        worker = (ExecutorService) field(jobs, "worker"); store = (DataStore) field(jobs, "store");
        if (Files.exists(getDataFolder().toPath().resolve("restart"))) {
            check(profile.levelBonuses.snapshot().get("milestones") == 100, "claim progress survives restart");
            check(receipts(MAIN).size() == 1, "the confirmed bonus survives in history");
            profile.xp.put("milestones", 48.0); award(player, Material.TORCH, 1);
            check(profile.pending.compareTo(new BigDecimal("0.01")) == 0, "reaching a previously rewarded level cannot pay again after restart");
            finish(2); return;
        }
        check(jobs.settings().getInt("economy.level-bonus-interval") == 50
                        && new BigDecimal(jobs.settings().getString("economy.level-bonus-per-level")).compareTo(new BigDecimal("0.5")) == 0,
                "default milestones pay half the level every fifty levels");
        var settingsFile = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(settingsFile);
        config.set("feedback.level-up-chat", false); config.set("feedback.milestone-level-interval", 0);
        config.set("feedback.boss-bar", false); config.save(settingsFile);
        var jobFile = jobs.getDataFolder().toPath().resolve("jobs.yml").toFile();
        var jobConfig = YamlConfiguration.loadConfiguration(jobFile);
        for (var job : jobs.registry().jobs()) jobConfig.set("jobs." + job.id() + ".enabled", false);
        addJob(jobConfig, "milestones", "Milestones", "craft", "TORCH");
        addJob(jobConfig, "second", "Second Job", "craft", "LANTERN");
        addJob(jobConfig, "kiln", "Kiln", "cook", "COOKED_SALMON");
        jobConfig.save(jobFile); reload();
        profile.pending = new BigDecimal("0.01");
        profile.paycheck.earn("milestones", profile.pending, System.currentTimeMillis(), jobs.paydayInterval(), true);
        profile.xp.put("milestones", 48.0); award(player, Material.TORCH, 1);
        check(profile.pending.compareTo(new BigDecimal("25.01")) == 0
                        && jobs.capEarnings(profile, "milestones", System.currentTimeMillis()).compareTo(new BigDecimal("0.01")) == 0,
                "level fifty pays twenty-five even with announcements off and the normal job cap exhausted");
        award(player, Material.TORCH, 1);
        check(profile.pending.compareTo(new BigDecimal("25.01")) == 0, "ordinary rewards after the milestone do not repeat it");
        var bulk = player(new UUID(82, 2), "BulkLevels");
        jobs.profile(bulk).xp.put("milestones", 48.0); award(bulk, Material.TORCH, 102);
        check(jobs.profile(bulk).pending.compareTo(new BigDecimal("150")) == 0, "one action crossing fifty, one hundred, and one hundred fifty pays all three");
        profile.xp.put("second", 48.0); award(player, Material.LANTERN, 1);
        check(profile.pending.compareTo(new BigDecimal("50.01")) == 0, "each job has independent milestone claims");
        var cook = jobs.profile(player(new UUID(82, 3), "OfflineCook"));
        cook.xp.put("kiln", 48.0); jobs.awardProcessed(cook.id, world, JobRegistry.Activity.COOK, Material.COOKED_SALMON, 1);
        check(cook.pending.compareTo(new BigDecimal("25")) == 0, "offline workstation progress can earn a level bonus");
        multiplier("0.5");
        profile.xp.put("milestones", 98.0); award(player, Material.TORCH, 1);
        check(profile.pending.compareTo(new BigDecimal("75.01")) == 0, "the level one hundred bonus applies the global multiplier exactly once");
        var existing = player(new UUID(82, 4), "ExistingLevels");
        jobs.profile(existing).xp.put("milestones", 224.0); award(existing, Material.TORCH, 1);
        check(jobs.profile(existing).pending.signum() == 0, "installing at a high level does not grant old milestones");
        multiplier("0");
        var paused = player(new UUID(82, 5), "PausedBonuses");
        jobs.profile(paused).xp.put("milestones", 48.0); award(paused, Material.TORCH, 1);
        multiplier("0.5"); jobs.profile(paused).xp.put("milestones", 48.0); award(paused, Material.TORCH, 1);
        check(jobs.profile(paused).pending.signum() == 0, "restoring a zero multiplier cannot replay a consumed milestone");
        setting("economy.level-bonus-interval", 0);
        var disabled = player(new UUID(82, 6), "DisabledBonuses");
        jobs.profile(disabled).xp.put("milestones", 48.0); award(disabled, Material.TORCH, 1);
        setting("economy.level-bonus-interval", 50);
        jobs.profile(disabled).xp.put("milestones", 48.0); award(disabled, Material.TORCH, 1);
        check(jobs.profile(disabled).pending.signum() == 0, "disabled milestones stay consumed when bonuses are enabled again");
        setting("economy.level-bonus-interval", 20); setting("economy.level-bonus-per-level", "0.25");
        var custom = player(new UUID(82, 7), "CustomBonuses");
        jobs.profile(custom).xp.put("milestones", 18.0); award(custom, Material.TORCH, 1);
        check(jobs.profile(custom).pending.compareTo(new BigDecimal("2.5")) == 0, "custom intervals and amounts apply after reload");
        setting("economy.level-bonus-interval", 50); setting("economy.level-bonus-per-level", "0.5");
        multiplier("0.1");
        var fraction = player(fractionId, "FractionalBonus");
        jobs.profile(fraction).xp.put("milestones", 48.0); award(fraction, Material.TORCH, 1);
        var fractional = jobs.profile(fraction);
        check(fractional.paycheck.available(true).compareTo(new BigDecimal("2")) == 0
                        && Paycheck.byJob(fractional.paycheck.snapshot().current()).get("milestones").compareTo(new BigDecimal("0.5")) == 0,
                "whole-number milestone bonuses carry cents into the source job's paycheck");
        multiplier("0.5"); invalidSettings();
        rejected.add(MAIN); installProvider();
        later(35, this::failedDeposit);
    }
    void addJob(YamlConfiguration yaml, String id, String name, String activity, String material) {
        String path = "jobs." + id;
        yaml.set(path + ".name", name);
        yaml.set(path + ".hourly-income-limit", "0.01");
        yaml.set(path + ".progression", Map.of("first-level-xp", 1, "early-level-xp", 1, "plateau-xp", 1));
        yaml.set(path + ".rules", List.of(Map.of("activity", activity, "materials", List.of(material), "xp", 1, "money", "0")));
    }
    void award(Player actor, Material material, double units) { jobs.award(actor, JobRegistry.Activity.CRAFT, material, null, units); }
    void setting(String key, Object value) throws Exception {
        var file = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(file); config.set(key, value); config.save(file); reload();
    }
    void multiplier(String value) throws Exception { setting("economy.payout-multiplier", value); }
    void reload() { jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"}); }
    List<DataStore.Receipt> receipts(UUID id) throws Exception { return worker.submit(() -> store.history(id)).get(10, TimeUnit.SECONDS); }
    void invalidSettings() throws Exception {
        var file = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
        String original = Files.readString(file.toPath());
        for (String key : List.of("economy.level-bonus-interval", "economy.level-bonus-per-level")) {
            var config = YamlConfiguration.loadConfiguration(file); config.set(key, -1); config.save(file);
            var registry = jobs.registry(); reload();
            check(jobs.registry() == registry, "invalid level bonus setting retains the working config: " + key);
            Files.writeString(file.toPath(), original);
        }
        reload();
    }
    void failedDeposit() throws Exception {
        check(receipts(MAIN).isEmpty() && profile.pending.compareTo(new BigDecimal("75.01")) == 0,
                "rejected level bonuses stay saved without a false receipt");
        check(balances.get(fractionId).compareTo(new BigDecimal("2")) == 0
                        && jobs.players().get(fractionId).pending.compareTo(new BigDecimal("0.5")) == 0,
                "confirmed deposits pay only the whole amount and retain the milestone fraction");
        rejected.remove(MAIN);
        var queue = jobs.getClass().getDeclaredMethod("queueUnpaid"); queue.setAccessible(true); queue.invoke(jobs);
        later(35, this::paidDeposit);
    }
    void paidDeposit() throws Exception {
        var receipts = receipts(MAIN);
        check(receipts.size() == 1 && receipts.getFirst().bonus() && receipts.getFirst().amount().compareTo(new BigDecimal("75")) == 0,
                "retry deposits the original scaled amount once and records it as a bonus");
        var receipt = receipts.getFirst();
        jobs.notifyPayment(player, profile, receipt.amount(), receipt.jobs());
        String hover = PaydayChecks.plain((net.kyori.adventure.text.Component)
                components.get(MAIN).getLast().children().getFirst().hoverEvent().value());
        check(hover.contains("Milestones: level 50") && hover.contains("Milestones: level 100") && hover.contains("Second Job: level 50"),
                "bonus message hover names the rewarded job milestones");
        check(profile.pending.compareTo(new BigDecimal("0.01")) == 0, "a milestone bonus leaves the ordinary paycheck untouched");
        jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        Files.createDirectories(getDataFolder().toPath()); Files.writeString(getDataFolder().toPath().resolve("restart"), "done");
        finish(1);
    }
    void finish(int phase) { getLogger().info("RELEASE CHECKS PHASE " + phase + " PASS: " + checks + " level bonus checks"); Bukkit.shutdown(); }
}
