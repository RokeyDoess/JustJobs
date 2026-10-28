package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Exercises balanced defaults, chat coalescing, and saved cumulative XP. */
public final class ProgressionChecks extends ReleaseChecks {
    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "XP balance starts on Paper");
        world = Bukkit.getWorlds().getFirst(); player = player(MAIN, "ProgressionPlayer"); profile = jobs.profile(player); profile.bossBar = false;
        worker = (ExecutorService) field(jobs, "worker");
        check(jobs.settings().getInt("feedback.level-up-chat-interval-seconds") == 10, "personal level messages default to ten-second spacing");
        if (Files.exists(getDataFolder().toPath().resolve("restart"))) {
            check(profile.xp("cooking") == 128 && jobs.registry().get("cooking").progression().progress(128).level() == 7, "cooking XP and its derived level survive restart");
            check(components.getOrDefault(MAIN, List.of()).isEmpty(), "restart does not replay old level messages");
            check(profile.xp("smithing") == 128 && jobs.registry().get("smithing").progression().progress(128).level() == 7, "ingot XP and its derived level survive restart");
            getLogger().info("RELEASE CHECKS PHASE 2 PASS: " + checks + " progression checks"); Bukkit.shutdown(); return;
        }
        check(jobs.registry().get("mining").progression().required(50) == 1500
                && jobs.registry().get("archaeology").progression().required(50) == 50, "different activity volumes have different plateau requirements");
        jobs.award(player, JobRegistry.Activity.COOK, Material.COOKED_SALMON, null, 10);
        check(texts().equals(List.of("Reached level 2 Cooking")), "the first cooking level announces immediately");
        jobs.award(player, JobRegistry.Activity.COOK, Material.COOKED_SALMON, null, 14);
        jobs.award(player, JobRegistry.Activity.COOK, Material.COOKED_SALMON, null, 104);
        check(texts().size() == 1 && profile.xp("cooking") == 128, "rapid levels do not flood chat or postpone earned XP");
        check(jobs.registry().get("cooking").progression().progress(profile.xp("cooking")).level() == 7, "128 cooked salmon reach level seven from zero");
        check(profile.pending.compareTo(new BigDecimal("0.64")) == 0, "the XP rebalance keeps cooking money unchanged");
        var flush = JustJobsPlugin.class.getDeclaredMethod("flushLevelMessages", org.bukkit.entity.Player.class, long.class); flush.setAccessible(true);
        flush.invoke(jobs, player, System.currentTimeMillis() + 10_000);
        check(texts().equals(List.of("Reached level 2 Cooking", "Reached level 7 Cooking")), "the next notification shows only the latest reached level");
        jobs.award(player, JobRegistry.Activity.SMELT, Material.IRON_INGOT, null, 128);
        check(profile.xp("smithing") == 128 && texts().size() == 2, "different jobs share chat spacing while earning XP independently");
        jobs.toggleNotifications(player); flush.invoke(jobs, player, System.currentTimeMillis() + 20_000);
        check(texts().stream().noneMatch(text -> text.startsWith("Reached level 7 Smithing")), "disabling personal chat discards delayed level messages");
        jobs.toggleNotifications(player);
        var file = jobs.getDataFolder().toPath().resolve("config.yml"); var original = Files.readString(file);
        var config = YamlConfiguration.loadConfiguration(file.toFile()); var working = jobs.registry();
        for (Object invalid : List.of(-1, 1.5, "10", 86401)) {
            config.set("feedback.level-up-chat-interval-seconds", invalid); config.save(file.toFile()); reload();
            check(jobs.registry() == working, "invalid message spacing retains the working configuration: " + invalid);
        }
        Files.writeString(file, original); reload();
        check(profile.xp("cooking") == 128 && profile.xp("smithing") == 128, "reload leaves stored XP intact");
        jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        Files.createDirectories(getDataFolder().toPath()); Files.writeString(getDataFolder().toPath().resolve("restart"), "saved");
        getLogger().info("RELEASE CHECKS PHASE 1 PASS: " + checks + " progression checks"); Bukkit.shutdown();
    }
    List<String> texts() { return components.getOrDefault(MAIN, List.of()).stream().map(PaydayChecks::plain).toList(); }
    void reload() { jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"}); }
}
