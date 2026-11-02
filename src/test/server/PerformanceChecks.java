package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Repeatable four-player workloads. Timings are diagnostic; correctness has fixed assertions. */
public final class PerformanceChecks extends ReleaseChecks {
    long permissionChecks;
    boolean allowed = true;
    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "performance checks start on Paper");
        world = Bukkit.getWorlds().getFirst(); base = 256;
        worker = (ExecutorService) field(jobs, "worker");
        var marker = getDataFolder().toPath().resolve("restart");
        if (Files.exists(marker)) {
            check(jobs.players().values().stream().mapToDouble(p -> p.xp("mining")).sum() == 62_000,
                    "benchmark mining progress survives restart");
            finish(2); return;
        }
        world.getChunkAt(16, 16).addPluginChunkTicket(this);
        activities = new ActivityListener(jobs, new NaturalBlocks(jobs));
        var additional = new AdditionalActivities(jobs, new NaturalBlocks(jobs));
        jobs.settings().set("feedback.boss-bar", false);
        jobs.settings().set("feedback.level-up-chat", false);
        jobs.settings().set("feedback.milestone-level-interval", 0);
        jobs.settings().set("economy.hourly-income-limit-per-job", "0");
        var actors = new ArrayList<Player>();
        var moves = new ArrayList<PlayerMoveEvent>(); var biomes = new ArrayList<PlayerMoveEvent>();
        for (int i = 0; i < 4; i++) {
            Player delegate = player(new UUID(32, i), "LoadPlayer" + i);
            Player actor = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
                if (method.getName().equals("hasPermission")) { permissionChecks++; return allowed; }
                try { return method.invoke(delegate, args); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            });
            actors.add(actor); jobs.profile(actor).biomes.add("minecraft:plains");
            moves.add(new PlayerMoveEvent(actor, new Location(world, 256.1, 100, 256.1), new Location(world, 256.10001, 100, 256.1)));
            biomes.add(new PlayerMoveEvent(actor, new Location(world, 256.1, 100, 256.1), new Location(world, 257.1, 100, 256.1)));
        }
        var flush = jobs.getClass().getDeclaredMethod("flushPayouts"); flush.setAccessible(true);
        benchmark("movement", count -> { for (int i = 0; i < count; i++) activities.move(moves.get(i % 4)); });
        benchmark("known-biome", count -> { for (int i = 0; i < count; i++) additional.biome(biomes.get(i % 4)); });
        benchmark("land-sailing-check", count -> { for (int i = 0; i < count; i++) activities.sampleSailing(actors.get(i % 4)); });
        benchmark("rewards-and-payout-poll", count -> {
            for (int i = 0; i < count; i++) {
                jobs.award(actors.get(i % 4), JobRegistry.Activity.BLOCK_BREAK, Material.STONE, null, 1);
                if (i % 4 == 3) flush.invoke(jobs);
            }
        });
        check(actors.stream().allMatch(actor -> jobs.profile(actor).xp("mining") == 15_500), "all benchmark block actions keep their XP");
        check(actors.stream().allMatch(actor -> jobs.profile(actor).pending.compareTo(new BigDecimal("93")) == 0),
                "all benchmark earnings remain exact and held for payday");
        check(actors.stream().allMatch(actor -> jobs.profile(actor).xp("exploring") == 0
                        && Math.abs(jobs.profile(actor).travel.distance("exploring") - .155) < 1e-6),
                "movement buffering and known-biome history keep their original behavior");
        allowed = false;
        var actor = actors.getFirst(); double distance = jobs.profile(actor).travel.distance("exploring");
        activities.move(moves.getFirst()); jobs.award(actor, JobRegistry.Activity.BLOCK_BREAK, Material.STONE, null, 1);
        check(jobs.profile(actor).xp("mining") == 15_500 && jobs.profile(actor).travel.distance("exploring") == distance,
                "permission revocation applies to the next action without waiting for a cache");
        jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        Files.createDirectories(getDataFolder().toPath()); Files.writeString(marker, "done"); finish(1);
    }
    @FunctionalInterface interface Workload { void run(int count) throws Exception; }
    void benchmark(String name, Workload workload) throws Exception {
        workload.run(2000);
        var timings = new ArrayList<Double>(); permissionChecks = 0;
        for (int i = 0; i < 6; i++) { long start = System.nanoTime(); workload.run(10_000); timings.add((System.nanoTime() - start) / 1e6); }
        timings.sort(Double::compare);
        getLogger().info(String.format(Locale.ROOT, "PERFORMANCE %s: 10000 actions median=%.3f ms min=%.3f ms; permission-checks-per-action=%.3f",
                name, (timings.get(2) + timings.get(3)) / 2, timings.getFirst(), permissionChecks / 60_000.0));
    }
    void finish(int phase) { getLogger().info("RELEASE CHECKS PHASE " + phase + " PASS: " + checks + " performance checks"); Bukkit.shutdown(); }
}
