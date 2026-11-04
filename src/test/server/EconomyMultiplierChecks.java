package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Global money scaling, live configuration, existing balances, and deposits on Paper. */
public final class EconomyMultiplierChecks extends ReleaseChecks {
    static final UUID BONUS = new UUID(51, 2);
    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "money multiplier checks start on Paper");
        world = Bukkit.getWorlds().getFirst(); player = player(MAIN, "MultiplierPlayer"); profile = jobs.profile(player);
        worker = (ExecutorService) field(jobs, "worker");
        var marker = getDataFolder().toPath().resolve("restart");
        if (Files.exists(marker)) {
            check(jobs.scaleMoney(BigDecimal.ONE).compareTo(new BigDecimal("2")) == 0, "multiplier survives restart");
            check(profile.pending.compareTo(new BigDecimal("0.625")) == 0, "saved earnings and remainders keep their value through restart");
            check(jobs.players().get(BONUS).pending.compareTo(new BigDecimal("0.25")) == 0, "paid bonus remainder survives without being multiplied again");
            finish(2); return;
        }
        check(jobs.scaleMoney(new BigDecimal("0.0085")).compareTo(new BigDecimal("0.0085")) == 0, "default multiplier keeps reward precision and balance");
        jobs.award(player, JobRegistry.Activity.FISH, Material.COD, null, 4);
        check(profile.pending.compareTo(BigDecimal.ONE) == 0 && profile.xp("fishing") == 20, "default rewards and XP stay unchanged");
        var file = jobs.getDataFolder().toPath().resolve("jobs.yml").toFile();
        var jobConfig = YamlConfiguration.loadConfiguration(file);
        jobConfig.set("jobs.fishing.hourly-income-limit", "3");
        jobConfig.set("jobs.hunting.hourly-income-limit", "0"); jobConfig.save(file);
        var huntingRules = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var raw : jobConfig.getMapList("jobs.hunting.rules")) {
            var rule = new java.util.LinkedHashMap<String, Object>(); raw.forEach((key, value) -> rule.put(key.toString(), value));
            if (java.util.List.of("WITHER").equals(rule.get("entities"))) rule.put("money", "12.5");
            huntingRules.add(rule);
        }
        jobConfig.set("jobs.hunting.rules", huntingRules); jobConfig.save(file);
        configure(null, true);
        check(YamlConfiguration.loadConfiguration(jobs.getDataFolder().toPath().resolve("config.yml").toFile()).getInt("economy.payout-multiplier") == 1,
                "older configs gain a neutral multiplier automatically");
        configure("0.5", true);
        jobs.award(player, JobRegistry.Activity.FISH, Material.COD, null, 1);
        check(profile.pending.compareTo(new BigDecimal("1.125")) == 0 && profile.xp("fishing") == 25, "half multiplier changes new money without changing old earnings or XP");
        check(jobs.hourlyLimit(jobs.registry().get("mining")).compareTo(new BigDecimal("25")) == 0
                        && jobs.hourlyLimit(jobs.registry().get("fishing")).compareTo(new BigDecimal("1.5")) == 0,
                "shared caps and custom job caps scale together");
        check(jobs.hourlyLimit(jobs.registry().get("hunting")).signum() == 0, "an unlimited custom cap remains unlimited");
        var capped = player(new UUID(51, 3), "PayPeriodCap");
        jobs.award(capped, JobRegistry.Activity.FISH, Material.COD, null, 400);
        check(jobs.profile(capped).pending.compareTo(new BigDecimal("1.5")) == 0 && jobs.profile(capped).xp("fishing") == 2000,
                "payday caps use the scaled amount while XP continues");
        var miner = player(new UUID(51, 4), "MiningCap");
        jobs.award(miner, JobRegistry.Activity.BLOCK_BREAK, Material.STONE, null, 10000);
        check(jobs.profile(miner).pending.compareTo(new BigDecimal("25")) == 0, "the scaled shared cap limits real block earnings");
        var menus = new JobsMenu(jobs); menus.open(player, false, 0, null);
        check(loreContains("Earned this paycheck: $1.12 / $1.50"), "the compact menu shows the scaled custom cap");
        menus.open(player, false, 0, "fishing");
        check(loreContains("5.0 XP + $0.125 / successful catch"), "job details show the scaled reward");
        jobs.profile(player(BONUS, "BonusPlayer")); rejected.add(BONUS); installProvider();
        jobs.awardBoss(BONUS, world, EntityType.WITHER);
        var bonus = jobs.players().get(BONUS);
        check(bonus.pending.compareTo(new BigDecimal("6.25")) == 0 && bonus.paycheck.available().compareTo(new BigDecimal("6")) == 0,
                "offline boss bonuses scale once and stay immediately payable");
        check(jobs.capEarnings(bonus, "hunting", System.currentTimeMillis()).signum() == 0 && bonus.xp("hunting") == 50,
                "scaled bonuses keep their cap exemption and full XP");
        var cook = jobs.profile(player(new UUID(51, 5), "ProcessingPlayer"));
        jobs.awardProcessed(cook.id, world, JobRegistry.Activity.COOK, Material.COOKED_SALMON, 10);
        check(cook.pending.compareTo(new BigDecimal("0.025")) == 0 && cook.xp("cooking") == 10,
                "offline processing earnings use the same global multiplier");
        var traveler = player(new UUID(51, 6), "TravelPlayer");
        jobs.award(traveler, JobRegistry.Activity.TRAVEL, null, null, 500);
        jobs.award(traveler, JobRegistry.Activity.SAIL, null, null, 500);
        check(jobs.profile(traveler).pending.compareTo(new BigDecimal("0.3125")) == 0, "walking and sailing rewards scale after their distance threshold");
        configure("0.5", false);
        var rolling = player(new UUID(51, 7), "RollingCap");
        jobs.award(rolling, JobRegistry.Activity.FISH, Material.COD, null, 400);
        check(jobs.profile(rolling).pending.compareTo(new BigDecimal("1.5")) == 0
                        && jobs.capEarnings(jobs.profile(rolling), "fishing", System.currentTimeMillis()).compareTo(new BigDecimal("1.5")) == 0,
                "immediate mode also enforces the scaled rolling cap");
        configure("2", true);
        check(profile.pending.compareTo(new BigDecimal("1.125")) == 0, "raising the multiplier does not rescale held earnings");
        jobs.award(player, JobRegistry.Activity.FISH, Material.COD, null, 1);
        check(profile.pending.compareTo(new BigDecimal("1.625")) == 0 && jobs.hourlyLimit(jobs.registry().get("mining")).compareTo(new BigDecimal("100")) == 0,
                "a multiplier above one raises rewards and caps");
        configure("0.1", true);
        BigDecimal before = profile.pending;
        jobs.award(player, JobRegistry.Activity.FISH, Material.COD, null, 1);
        check(profile.pending.compareTo(before) == 0, "lowering a cap retains earnings already counted and cannot grant a new allowance");
        configure("0", true);
        double xp = profile.xp("fishing");
        jobs.award(player, JobRegistry.Activity.FISH, Material.COD, null, 100);
        jobs.awardBoss(BONUS, world, EntityType.WITHER);
        check(profile.pending.compareTo(new BigDecimal("1.625")) == 0 && profile.xp("fishing") == xp + 500
                        && bonus.pending.compareTo(new BigDecimal("6.25")) == 0 && bonus.xp("hunting") == 100,
                "zero stops ordinary and bonus money without changing saved earnings or XP");
        later(30, () -> {
            check(!balances.containsKey(BONUS) && bonus.pending.compareTo(new BigDecimal("6.25")) == 0, "failed scaled bonuses retain their original amount");
            rejected.remove(BONUS);
            var queue = jobs.getClass().getDeclaredMethod("queueUnpaid"); queue.setAccessible(true); queue.invoke(jobs);
            later(40, () -> {
                check(balances.get(BONUS).compareTo(new BigDecimal("6")) == 0 && bonus.pending.compareTo(new BigDecimal("0.25")) == 0,
                        "zero multiplier still pays existing money with whole-number rounding and an exact remainder");
                configure("2", true); invalidSettings();
                jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
                Files.createDirectories(marker.getParent()); Files.writeString(marker, "done"); finish(1);
            });
        });
    }
    boolean loreContains(String text) {
        for (var item : current.getContents()) if (item != null && item.getItemMeta().hasLore())
            if (item.getItemMeta().lore().stream().anyMatch(line -> PaydayChecks.plain(line).equals(text))) return true;
        return false;
    }
    void configure(Object multiplier, boolean payday) throws Exception {
        var file = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(file);
        config.set("economy.payout-multiplier", multiplier); config.set("economy.payday-enabled", payday);
        config.set("economy.payout-interval-seconds", 1); config.set("feedback.boss-bar", false);
        config.set("feedback.level-up-chat", false); config.set("feedback.milestone-level-interval", 0); config.save(file);
        jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"});
        check(jobs.paydayEnabled() == payday, "reload applies the chosen payout mode");
    }
    void invalidSettings() throws Exception {
        var file = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
        for (Object invalid : new Object[]{-1, "NaN", "invalid", "1000001", "0.123456789", true}) {
            var config = YamlConfiguration.loadConfiguration(file); config.set("economy.payout-multiplier", invalid); config.save(file);
            boolean rejected = false;
            try { jobs.loadSettings(); } catch (IllegalArgumentException e) { rejected = e.getMessage().contains("economy.payout-multiplier"); }
            check(rejected && jobs.scaleMoney(BigDecimal.ONE).compareTo(new BigDecimal("2")) == 0,
                    "invalid multiplier leaves the active settings intact: " + invalid);
        }
        configure("2", true);
    }
    void finish(int phase) { getLogger().info("RELEASE CHECKS PHASE " + phase + " PASS: " + checks + " economy multiplier checks"); Bukkit.shutdown(); }
}
