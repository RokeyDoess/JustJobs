package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.BlockBreakEvent;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Checks popup timing without delaying rewards, plus natural decorated-pot history. */
public final class FeedbackChecks extends ReleaseChecks {
    java.nio.file.Path jobsFile, settingsFile;
    String originalJobs, originalSettings;

    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "feedback build starts on Paper");
        world = Bukkit.getWorlds().getFirst(); world.getChunkAt(10, 10).addPluginChunkTicket(this);
        player = player(MAIN, "FeedbackPlayer"); profile = jobs.profile(player);
        worker = (ExecutorService) field(jobs, "worker");
        history = (NaturalBlocks) field(jobs, "natural");
        check(jobs.registry().get("cooking").bossBarCooldownSeconds() == 30
                && jobs.registry().get("smithing").bossBarCooldownSeconds() == 30, "Cooking and Smithing default to 30-second popup cooldowns");
        check(jobs.registry().get("digging").bossBarCooldownSeconds() == 0, "other jobs retain unrestricted feedback by default");
        if (Files.exists(getDataFolder().toPath().resolve("restart"))) { restartFeedback(); return; }
        potChecks();
        jobsFile = jobs.getDataFolder().toPath().resolve("jobs.yml"); settingsFile = jobs.getDataFolder().toPath().resolve("config.yml");
        originalJobs = Files.readString(jobsFile); originalSettings = Files.readString(settingsFile);
        var config = YamlConfiguration.loadConfiguration(jobsFile.toFile());
        config.set("jobs.cooking.boss-bar-cooldown-seconds", 3);
        config.set("jobs.smithing.boss-bar-cooldown-seconds", 3); config.save(jobsFile.toFile());
        var settings = YamlConfiguration.loadConfiguration(settingsFile.toFile());
        settings.set("feedback.boss-bar-seconds", 1); settings.save(settingsFile.toFile()); reload();
        check(jobs.registry().get("cooking").bossBarCooldownSeconds() == 3, "reload applies per-job cooldown changes");
        jobs.forgetFeedback(MAIN); barsShown.clear();
        cooking(1);
        long expires = (long) field(bars().get(MAIN), "expires");
        cooking(1);
        check(barsShown.get(MAIN) == 1, "repeated cooking updates one visible bar");
        check((long) field(bars().get(MAIN), "expires") == expires, "continuous processing does not extend a cooldown job's visible time");
        double smithBefore = profile.xp("smithing");
        jobs.award(player, JobRegistry.Activity.SMELT, Material.IRON_INGOT, null, 1);
        check(barsShown.get(MAIN) == 1 && profile.xp("smithing") == smithBefore + 1, "cross-job priority suppresses the popup without suppressing XP");
        jobs.hideBar(MAIN);
        jobs.award(player, JobRegistry.Activity.SMELT, Material.IRON_INGOT, null, 1);
        check(barsShown.get(MAIN) == 2, "a priority-suppressed job has not spent its own cooldown");
        jobs.hideBar(MAIN); cooking(1);
        check(barsShown.get(MAIN) == 2 && !bars().containsKey(MAIN), "hiding or switching a bar does not bypass its cooldown");
        var other = player(new UUID(11, 1), "OtherCook"); jobs.award(other, JobRegistry.Activity.COOK, Material.COOKED_BEEF, null, 1);
        check(barsShown.get(other.getUniqueId()) == 1, "another player's cooking popup has its own timer");
        jobs.forgetFeedback(other.getUniqueId());
        later(25, this::quietAwards);
    }

    void potChecks() {
        var pot = world.getBlockAt(160, 200, 160); pot.setType(Material.DECORATED_POT, false);
        BigDecimal before = profile.pending; double xp = profile.xp("archaeology");
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(pot, player));
        check(profile.xp("archaeology") == xp + 2 && profile.pending.subtract(before).compareTo(new BigDecimal("0.05")) == 0,
                "a natural decorated pot pays 2 XP and 0.05");
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(pot, player));
        check(profile.xp("archaeology") == xp + 2, "repeated breaks at the same pot location do not pay twice");
        var placed = world.getBlockAt(161, 200, 160); placed.setType(Material.DECORATED_POT, false); history.markPlaced(placed);
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(placed, player));
        check(profile.xp("archaeology") == xp + 2, "player-placed decorated pots do not pay");
        var modified = world.getBlockAt(162, 200, 160); modified.setType(Material.DECORATED_POT, false); history.mark(modified);
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(modified, player));
        check(profile.xp("archaeology") == xp + 2, "modified or moved pots do not pass the natural filter");
        var protectedPot = world.getBlockAt(163, 200, 160); protectedPot.setType(Material.DECORATED_POT, false);
        var event = new BlockBreakEvent(protectedPot, player); event.setCancelled(true); Bukkit.getPluginManager().callEvent(event);
        check(profile.xp("archaeology") == xp + 2 && history.eligible(protectedPot), "cancelled breaks preserve the pot's eligibility without paying");
        event.setCancelled(false); Bukkit.getPluginManager().callEvent(event);
        check(profile.xp("archaeology") == xp + 4, "a later permitted break pays normally");
    }

    void quietAwards() throws Exception {
        cooking(50);
        check(!bars().containsKey(MAIN) && barsShown.get(MAIN) == 2, "cooking stays quiet between the display timeout and cooldown expiry");
        check(profile.xp("cooking") == 53, "every cooking reward still contributes XP during the quiet period");
        var earned = profile.paycheck.snapshot();
        check(earned.current().getOrDefault("cooking", BigDecimal.ZERO).add(earned.due().getOrDefault("cooking", BigDecimal.ZERO)).compareTo(new BigDecimal("0.265")) == 0,
                "every cooking reward still contributes money during the quiet period");
        check(components.get(MAIN).stream().map(PaydayChecks::plain).anyMatch(text -> text.equals("Reached level 4 Cooking")), "level-up chat is unaffected by the popup cooldown");
        reload(); cooking(1);
        check(!bars().containsKey(MAIN) && barsShown.get(MAIN) == 2, "reload retains an existing quiet period");
        later(45, this::afterCooldown);
    }

    void afterCooldown() throws Exception {
        cooking(1);
        check(barsShown.get(MAIN) == 3 && bars().containsKey(MAIN), "a later reward opens the bar again after the configured cooldown");
        var config = YamlConfiguration.loadConfiguration(jobsFile.toFile());
        config.set("jobs.cooking.boss-bar-cooldown-seconds", 0); config.save(jobsFile.toFile()); reload();
        cooking(1); long expires = (long) field(bars().get(MAIN), "expires");
        later(2, () -> {
            cooking(1);
            check((long) field(bars().get(MAIN), "expires") > expires, "zero restores continuous bar refreshes");
            var working = jobs.registry();
            for (Object invalid : List.of(-1, 1.5, "30", 86401)) {
                config.set("jobs.cooking.boss-bar-cooldown-seconds", invalid); config.save(jobsFile.toFile()); reload();
                check(jobs.registry() == working, "invalid cooldown leaves the working configuration running: " + invalid);
            }
            Files.writeString(jobsFile, originalJobs); Files.writeString(settingsFile, originalSettings); reload();
            jobs.forgetFeedback(MAIN); cooking(1);
            check(bars().containsKey(MAIN), "disconnect cleanup permits fresh feedback in a new session");
            jobs.save(); history.flush(); world.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            Files.createDirectories(getDataFolder().toPath()); Files.writeString(getDataFolder().toPath().resolve("restart"), "saved");
            getLogger().info("RELEASE CHECKS PHASE 1 PASS: " + checks + " feedback checks"); Bukkit.shutdown();
        });
    }

    void restartFeedback() {
        double before = profile.xp("archaeology");
        check(before == 4, "decorated-pot XP survives restart");
        check(!history.eligible(world.getBlockAt(160, 200, 160)) && history.playerPlaced(world.getBlockAt(161, 200, 160)), "natural and placed pot history survives restart");
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(world.getBlockAt(160, 200, 160), player));
        check(profile.xp("archaeology") == before, "a previously rewarded pot cannot pay again after restart");
        cooking(1);
        check(barsShown.get(MAIN) == 1, "the first cooking action after restart can show feedback");
        getLogger().info("RELEASE CHECKS PHASE 2 PASS: " + checks + " feedback checks"); Bukkit.shutdown();
    }

    void cooking(double count) { jobs.award(player, JobRegistry.Activity.COOK, Material.COOKED_BEEF, null, count); }
    @SuppressWarnings("unchecked") Map<UUID, Object> bars() throws Exception { return (Map<UUID, Object>) field(jobs, "bars"); }
    void reload() { jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"}); }
}
