package checks;

import io.github.rokeydoess.justjobs.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Boarding-driven sailing, driver changes, cancellation, reload discovery, and idle shutdown. */
public final class SailingChecks extends CraftingChecks {
    Player first, second;
    Boat boat;
    final List<Entity> passengers = new ArrayList<>();
    final Map<UUID, Entity> vehicles = new HashMap<>();
    final Map<UUID, Location> positions = new HashMap<>();
    boolean water = true, permitted = true;
    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "boarding-driven sailing starts on Paper");
        world = Bukkit.getWorlds().getFirst(); worker = (ExecutorService) field(jobs, "worker");
        activities = (ActivityListener) field(jobs, "activities");
        var marker = getDataFolder().toPath().resolve("restart");
        if (Files.exists(marker)) {
            check(task() == null && riders().isEmpty(), "restart with no riders leaves no sailing timer");
            var profile = jobs.players().get(new UUID(43, 2));
            check(profile.xp("sailing") == 5 && Math.abs(profile.travel.distance("sailing") - 30.2) < 1e-6,
                    "earned sailing XP and unfinished distance survive restart");
            finishSailing(2); return;
        }
        first = actor(1); second = actor(2);
        boat = (Boat) Proxy.newProxyInstance(Boat.class.getClassLoader(), new Class<?>[]{Boat.class}, (p, method, args) -> switch (method.getName()) {
            case "getPassengers" -> List.copyOf(passengers);
            case "getUniqueId" -> new UUID(43, 3);
            case "getType" -> EntityType.OAK_BOAT;
            case "isInWater" -> water;
            default -> empty(method.getReturnType());
        });
        check(task() == null && riders().isEmpty(), "startup without boats creates no sailing timer");
        for (int i = 0; i < 1000; i++) activities.sampleTravel();
        check(task() == null && riders().isEmpty(), "empty sailing maintenance stays inactive");
        mount(second, true); check(checksPending().isEmpty(), "cancelled boarding cannot start tracking");
        mount(first, false);
        check(task() == null && checksPending().size() == 1, "boarding waits until the passenger list has changed");
        later(2, this::afterBoarding);
    }
    Player actor(int number) {
        UUID id = new UUID(43, number); Player delegate = player(id, "Sailor" + number);
        positions.put(id, new Location(world, 256, 100, 256));
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, method, args) -> switch (method.getName()) {
            case "getVehicle" -> vehicles.get(id);
            case "getLocation" -> positions.get(id).clone();
            case "hasPermission" -> permitted;
            case "equals" -> p == args[0];
            case "hashCode" -> id.hashCode();
            default -> method.invoke(delegate, args);
        });
    }
    void mount(Player player, boolean cancel) {
        var event = new EntityMountEvent(player, boat); event.setCancelled(cancel); Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) { vehicles.put(player.getUniqueId(), boat); passengers.add(player); }
    }
    void dismount(Player player, boolean cancel) {
        var event = new EntityDismountEvent(player, boat); event.setCancelled(cancel); Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) { vehicles.remove(player.getUniqueId()); passengers.remove(player); }
    }
    void move(Player player, double distance) { positions.get(player.getUniqueId()).add(distance, 0, 0); activities.sampleTravel(); }
    BukkitTask task() throws Exception { return (BukkitTask) field(activities, "sailingTask"); }
    Map<?, ?> riders() throws Exception { return (Map<?, ?>) field(activities, "sailors"); }
    Map<?, ?> checksPending() throws Exception { return (Map<?, ?>) field(activities, "sailingChecks"); }
    double distance(Player player) { return jobs.profile(player).travel.distance("sailing"); }
    void afterBoarding() throws Exception {
        check(task() != null && riders().size() == 1, "the first rider starts a sailing timer");
        for (int i = 0; i < 4; i++) move(first, .1);
        check(distance(first) == 0, "small sailing samples remain buffered below the minimum displacement");
        dismount(first, true);
        later(2, () -> {
            check(task() != null && riders().size() == 1, "cancelled dismounting keeps the rider tracked");
            dismount(first, false);
            later(2, this::afterDismount);
        });
    }
    void afterDismount() throws Exception {
        check(Math.abs(distance(first) - .4) < 1e-6, "dismounting preserves sub-threshold sailing distance");
        check(task() == null && riders().isEmpty(), "the last rider leaving stops the sailing timer");
        positions.put(first.getUniqueId(), new Location(world, 1000, 100, 256));
        positions.put(second.getUniqueId(), new Location(world, 1000, 100, 256));
        mount(first, false);
        later(2, () -> {
            move(first, .1); dismount(first, false);
            positions.get(first.getUniqueId()).add(10, 0, 0); mount(first, false);
            later(2, () -> {
                activities.sampleTravel();
                check(Math.abs(distance(first) - .5) < 1e-6, "briefly leaving and reboarding the same boat cannot count movement on land");
                mount(second, false); later(2, this::driverChange);
            });
        });
    }
    void driverChange() throws Exception {
        move(first, 1); move(second, 1);
        check(Math.abs(distance(first) - 1.5) < 1e-6 && distance(second) == 0, "only the driver earns, and reboarding does not count the gap between rides");
        dismount(first, false);
        later(2, () -> {
            activities.sampleTravel(); move(second, .2); water = false; activities.sampleTravel();
            check(Math.abs(distance(second) - .2) < 1e-6, "the remaining passenger can become the driver and keeps distance earned before leaving water");
            water = true; activities.sampleTravel();
            for (int i = 0; i < 20; i++) move(second, 25);
            check(jobs.profile(second).xp("sailing") == 5 && Math.abs(distance(second) - .2) < 1e-6,
                    "boarding-driven sampling keeps the same 500-block reward and remainder");
            var from = positions.get(second.getUniqueId()).clone(); var to = from.clone().add(1000, 0, 0);
            Bukkit.getPluginManager().callEvent(new PlayerTeleportEvent(second, from, to)); positions.put(second.getUniqueId(), to);
            later(2, this::afterTeleport);
        });
    }
    void afterTeleport() throws Exception {
        activities.sampleTravel(); permitted = false; move(second, 10);
        check(jobs.profile(second).xp("sailing") == 5 && Math.abs(distance(second) - .2) < 1e-6,
                "teleports and permission changes cannot add sailing rewards");
        permitted = true; activities.sampleTravel(); move(second, 30);
        vehicles.remove(second.getUniqueId()); passengers.clear(); activities.sampleTravel();
        check(task() == null && riders().isEmpty() && Math.abs(distance(second) - 30.2) < 1e-6,
                "a missing vehicle is pruned even without a dismount event");
        mount(first, false); Bukkit.getPluginManager().callEvent(new PlayerQuitEvent(first, Component.empty()));
        later(2, () -> {
            check(task() == null && riders().isEmpty() && checksPending().isEmpty(), "quitting cancels deferred boarding checks without restarting the timer");
            nativeReload();
        });
    }
    void nativeReload() throws Exception {
        var actor = table(); var playerList = invoke(invoke(Bukkit.getServer(), "getServer"), "getPlayerList");
        var list = (List<Object>) invoke(playerList, "getPlayers"); var lookup = (Map<UUID, Object>) invoke(playerList, "getPlayersByUUID");
        var nativeBoat = (Boat) world.spawnEntity(new Location(world, 260, 100, 260), EntityType.OAK_BOAT); nativeBoat.setGravity(false);
        list.add(actor.handle()); lookup.put(actor.player().getUniqueId(), actor.handle());
        Class.forName("net.minecraft.world.entity.Entity").getField("valid").setBoolean(actor.handle(), true);
        check(nativeBoat.addPassenger(actor.player()) && !checksPending().isEmpty(), "native boat boarding emits the mount event used for tracking");
        later(2, () -> {
            try {
                check(task() != null && riders().containsKey(actor.player().getUniqueId()), "a real online boat rider starts the timer");
                var previous = task();
                var file = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
                var config = YamlConfiguration.loadConfiguration(file); config.set("rules.travel-sample-ticks", 2); config.save(file);
                jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"});
                check(previous.isCancelled() && task() != null && task() != previous && riders().containsKey(actor.player().getUniqueId()),
                        "reload replaces the active timer and discovers an already mounted rider");
                activities.stopTravel();
                check(task() == null && riders().isEmpty() && checksPending().isEmpty(), "shutdown cleanup stops all sailing work");
            } finally {
                nativeBoat.removePassenger(actor.player()); nativeBoat.remove();
                Class.forName("net.minecraft.world.entity.Entity").getField("valid").setBoolean(actor.handle(), false);
                lookup.remove(actor.player().getUniqueId()); list.remove(actor.handle()); activities.stopTravel();
            }
            jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            var marker = getDataFolder().toPath().resolve("restart"); Files.createDirectories(marker.getParent()); Files.writeString(marker, "done"); finishSailing(1);
        });
    }
    void finishSailing(int phase) { getLogger().info("RELEASE CHECKS PHASE " + phase + " PASS: " + checks + " sailing checks"); Bukkit.shutdown(); }
}
