package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.server.BroadcastMessageEvent;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Exercises the real broadcast event and delivery to a second player. */
public final class MilestoneChecks extends ReleaseChecks {
    final List<String> broadcasts = new ArrayList<>();
    Player witness;
    final UUID witnessId = new UUID(9, 1);

    @EventHandler public void broadcast(BroadcastMessageEvent event) {
        String text = PaydayChecks.plain(event.message());
        if (!text.startsWith("MilestonePlayer has reached level ")) return;
        broadcasts.add(text);
        event.getRecipients().add(player);
        event.getRecipients().add(witness);
    }

    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "JustJobs starts with milestone announcements");
        world = Bukkit.getWorlds().getFirst();
        player = player(MAIN, "MilestonePlayer"); witness = player(witnessId, "MilestoneWitness");
        profile = jobs.profile(player);
        worker = (ExecutorService) field(jobs, "worker");
        if (Files.exists(getDataFolder().toPath().resolve("restart"))) {
            restartMilestones(); return;
        }
        check(jobs.settings().getInt("feedback.milestone-level-interval") == 50, "milestones default to every 50 levels");
        profile.notifications = false; profile.bossBar = false;
        var file = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(file);
        config.set("feedback.level-up-chat", false);
        config.set("feedback.milestone-level-interval", null);
        config.save(file); reload();
        check(jobs.settings().getInt("feedback.milestone-level-interval") == 50, "existing configs receive the default without resetting other settings");
        profile.pending = new BigDecimal("100");
        profile.paycheck.earn("mining", profile.pending, System.currentTimeMillis(), jobs.paydayInterval(), true);
        reach(50);
        check(broadcasts.equals(List.of("MilestonePlayer has reached level 50 Mining")), "level 50 broadcasts the exact milestone wording");
        check(components.get(witnessId).stream().map(PaydayChecks::plain).toList().equals(broadcasts), "the announcement is delivered to another player");
        check(components.get(MAIN).stream().map(PaydayChecks::plain).toList().equals(broadcasts), "personal chat being off does not suppress global announcements");
        check(profile.pending.compareTo(new BigDecimal("100")) == 0, "milestones still announce after the job money cap is reached");
        jobs.award(player, JobRegistry.Activity.BLOCK_BREAK, Material.STONE, null, 1);
        check(broadcasts.size() == 1, "ordinary rewards at the same level don't repeat the announcement");
        interval(10);
        check(broadcasts.size() == 1, "changing the interval does not announce old milestones");
        reach(60);
        check(broadcasts.getLast().equals("MilestonePlayer has reached level 60 Mining"), "reload applies a custom milestone interval");
        interval(0); reach(70);
        check(broadcasts.size() == 2, "zero disables milestone announcements");
        for (Object invalid : List.of(-1, 1.5, "50", 2_147_483_648L)) {
            config = YamlConfiguration.loadConfiguration(file);
            config.set("feedback.milestone-level-interval", invalid); config.save(file); reload();
            check(jobs.settings().getInt("feedback.milestone-level-interval") == 0, "invalid interval is rejected without replacing working settings: " + invalid);
        }
        interval(50);
        profile.xp.put("mining", xpAt(99));
        jobs.award(player, JobRegistry.Activity.BLOCK_BREAK, Material.STONE, null, xpAt(225) - xpAt(99));
        check(broadcasts.size() == 3 && broadcasts.getLast().equals("MilestonePlayer has reached level 200 Mining"),
                "a bulk reward crossing multiple milestones broadcasts only the highest one");
        double savedXp = profile.xp("mining");
        profile.xp.put("mining", xpAt(250) - 1);
        var stone = world.getBlockAt(0, 200, 0); stone.setType(Material.STONE, false);
        var cancelled = new org.bukkit.event.block.BlockBreakEvent(stone, player);
        cancelled.setCancelled(true);
        Bukkit.getPluginManager().callEvent(cancelled);
        check(broadcasts.size() == 3 && profile.xp("mining") == xpAt(250) - 1, "a cancelled qualifying action cannot cross or announce a milestone");
        profile.xp.put("mining", savedXp);
        jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        Files.createDirectories(getDataFolder().toPath());
        Files.writeString(getDataFolder().toPath().resolve("restart"), "saved");
        getLogger().info("RELEASE CHECKS PHASE 1 PASS: " + checks + " milestone checks");
        Bukkit.shutdown();
    }

    void restartMilestones() {
        check(broadcasts.isEmpty(), "startup doesn't replay saved milestones");
        check(jobs.registry().get("mining").progression().progress(profile.xp("mining")).level() == 225, "job progress survives the milestone update and restart");
        jobs.award(player, JobRegistry.Activity.BLOCK_BREAK, Material.STONE, null, 1);
        check(broadcasts.isEmpty(), "the next ordinary reward after restart doesn't repeat a milestone");
        reach(250);
        check(broadcasts.equals(List.of("MilestonePlayer has reached level 250 Mining")), "the next milestone announces once after restart");
        check(components.get(witnessId).stream().map(PaydayChecks::plain).toList().equals(broadcasts), "global delivery still works after restart");
        getLogger().info("RELEASE CHECKS PHASE 2 PASS: " + checks + " milestone checks");
        Bukkit.shutdown();
    }
    double xpAt(int level) {
        var curve = jobs.registry().get("mining").progression();
        double total = 0;
        for (int at = 1; at < level; at++) total += curve.required(at);
        return total;
    }
    void reach(int level) {
        profile.xp.put("mining", xpAt(level) - 1);
        jobs.award(player, JobRegistry.Activity.BLOCK_BREAK, Material.STONE, null, 1);
    }
    void interval(int value) throws Exception {
        var file = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(file);
        config.set("feedback.milestone-level-interval", value); config.save(file); reload();
    }
    void reload() { jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"}); }
}
