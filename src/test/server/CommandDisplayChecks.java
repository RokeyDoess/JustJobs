package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Uses Paper's command completion and the real paycheck inventory. */
public final class CommandDisplayChecks extends ReleaseChecks {
    final List<String> replies = new ArrayList<>();

    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "JustJobs starts with shared time formatting");
        world = Bukkit.getWorlds().getFirst();
        player = player(MAIN, "CommandChecks"); profile = jobs.profile(player);
        worker = (ExecutorService) field(jobs, "worker");
        if (Files.exists(getDataFolder().toPath().resolve("restart"))) {
            check(jobs.settings().getInt("display.time-format") == 24, "the default clock format survives restart");
            check(Integer.valueOf(12).equals(profile.timeFormat), "a personal twelve-hour choice survives restart independently of the default");
            checkClock(12);
            var inherited = jobs.players().get(new UUID(10, 1));
            check(inherited.timeFormat == null && jobs.timeFormat(inherited) == 24, "players without a choice still follow the default after restart");
            check(complete(true, "earnings", "AL").equals(List.of("alex", "Alice")), "offline player names still complete after restart");
            check(complete(false, "earnings", "").isEmpty(), "saved names stay hidden from non-admins after restart");
            finish(2); return;
        }
        jobs.profile(player(new UUID(10, 1), "Alice"));
        jobs.profile(player(new UUID(10, 2), "alex"));
        jobs.profile(player(new UUID(10, 3), "Blake"));
        check(complete(true, "EARNINGS", "AL").equals(List.of("alex", "Alice")), "earnings completion matches names and subcommands regardless of case");
        check(complete(true, "earnings", "").equals(List.of("alex", "Alice", "Blake", "CommandChecks")), "an empty prefix offers all known names in alphabetical order");
        check(complete(true, "earnings", "missing").isEmpty(), "unmatched names produce no suggestions");
        check(complete(false, "earnings", "").isEmpty(), "non-admins cannot complete earnings player names");
        check(complete(false, "earn").isEmpty(), "the earnings subcommand stays hidden from non-admins");
        check(complete(true, "backup", "Al").isEmpty() && complete(true, "earnings", "Alice", "").isEmpty(), "player suggestions only appear for the earnings name argument");
        jobs.profile(player(new UUID(10, 4), "Alice"));
        check(complete(true, "earnings", "Al").equals(List.of("alex", "Alice")), "duplicate stored names are suggested once");
        jobs.getCommand("jobs").execute(sender(true), "jobs", new String[]{"earnings", "aLiCe"});
        check(replies.stream().anyMatch(text -> text.startsWith("Alice — earnings")), "a completed name works with case-insensitive earnings lookup");
        check(jobs.settings().getInt("display.time-format") == 12, "clock displays default to twelve-hour time");
        checkClock(12);
        var file = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(file);
        config.set("display", null); config.save(file); reload();
        check(jobs.settings().getInt("display.time-format") == 12, "older configs receive the default clock setting");
        clockConfig(24); checkClock(24);
        for (Object invalid : List.of(13, 12.5, "12", false, 48)) {
            clockConfig(invalid);
            check(jobs.settings().getInt("display.time-format") == 24, "invalid clock format retains the previous configuration: " + invalid);
            checkClock(24);
        }
        config = YamlConfiguration.loadConfiguration(file);
        config.set("display", "invalid"); config.save(file); reload();
        check(jobs.settings().getInt("display.time-format") == 24, "a malformed display section is rejected safely");
        clockConfig(12); checkClock(12);
        config = YamlConfiguration.loadConfiguration(file);
        config.set("economy.payday-enabled", false); config.save(file); reload();
        check(distribution().equals("Distribution: As earnings become payable"), "immediate payouts keep their non-clock description");
        config.set("economy.payday-enabled", true); config.save(file); reload();
        preferencesChecks();
    }
    void preferencesChecks() {
        var menus = new JobsMenu(jobs);
        menus.open(player, false, 0, null); click(menus, 36);
        later(2, () -> {
            check(current.getSize() == 27 && current.getItem(16).getType() == Material.CLOCK,
                    "the jobs settings menu includes a clock option");
            check(PaydayChecks.plain(current.getItem(16).getItemMeta().displayName()).equals("Time format: 12-hour"),
                    "the clock option shows the inherited format");
            click(menus, 16);
            later(2, () -> {
                check(Integer.valueOf(24).equals(profile.timeFormat) && current.getSize() == 27,
                        "clicking the clock saves a personal twenty-four-hour choice without leaving settings");
                check(PaydayChecks.plain(current.getItem(16).getItemMeta().displayName()).equals("Time format: 24-hour"),
                        "the clock item refreshes after toggling");
                var other = jobs.players().get(new UUID(10, 1));
                check(other.timeFormat == null && jobs.timeFormat(other) == 12, "changing one clock leaves other players on the default");
                click(menus, 22);
                later(2, () -> {
                    checkClock(24);
                    clockConfig(24);
                    check(jobs.timeFormat(other) == 24, "reloading the default updates players who haven't chosen a format");
                    menus.open(player, true, 0, null); click(menus, 36);
                    later(2, () -> {
                        check(current.getSize() == 27 && current.getItem(0).getType() == Material.PURPLE_STAINED_GLASS_PANE
                                        && current.getItem(16).getType() == Material.CLOCK,
                                "leaderboards offer the same clock preference in their settings menu");
                        click(menus, 16);
                        later(2, () -> {
                            check(Integer.valueOf(12).equals(profile.timeFormat), "the clock can switch back to twelve-hour time");
                            checkClock(12);
                            clockConfig(12); checkClock(12);
                            clockConfig(24); checkClock(12);
                            check(jobs.timeFormat(other) == 24, "default changes leave personal choices intact");
                            menus.open(player, false, 0, null); click(menus, 36);
                            later(2, () -> {
                                click(menus, 16); menus.open(player, false, 0, null);
                                later(2, () -> {
                                    check(Integer.valueOf(12).equals(profile.timeFormat), "a queued clock click cannot change preferences after leaving settings");
                                    jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
                                    Files.createDirectories(getDataFolder().toPath());
                                    Files.writeString(getDataFolder().toPath().resolve("restart"), "saved");
                                    finish(1);
                                });
                            });
                        });
                    });
                });
            });
        });
    }
    void click(JobsMenu menus, int slot) {
        menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL));
    }
    List<String> complete(boolean admin, String... args) {
        return jobs.getCommand("jobs").tabComplete(sender(admin), "jobs", args);
    }
    CommandSender sender(boolean admin) {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
            case "hasPermission" -> !"justjobs.admin".equals(args[0]) || admin;
            case "getName" -> "CommandAdmin";
            case "sendMessage" -> { replies.add(String.valueOf(args[0])); yield null; }
            default -> empty(method.getReturnType());
        });
    }
    String distribution() {
        new JobsMenu(jobs).open(player, false, 0, null);
        return current.getItem(39).getItemMeta().lore().stream().map(PaydayChecks::plain)
                .filter(line -> line.startsWith("Distribution: ")).findFirst().orElseThrow();
    }
    void checkClock(int hours) {
        String line = distribution();
        String pattern = hours == 12 ? "Distribution: [A-Za-z]{3} (?:[1-9]|1[0-2]):[0-5][0-9] (?:AM|PM) .+"
                : "Distribution: [A-Za-z]{3} (?:[01][0-9]|2[0-3]):[0-5][0-9] .+";
        check(line.matches(pattern) && (hours == 12 || !line.contains(" AM ") && !line.contains(" PM ")),
                "the paycheck menu uses the active " + hours + "-hour clock");
    }
    void clockConfig(Object value) throws Exception {
        var file = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(file);
        config.set("display", null); config.set("display.time-format", value); config.save(file); reload();
    }
    void reload() { jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"}); }
    void finish(int phase) {
        getLogger().info("RELEASE CHECKS PHASE " + phase + " PASS: " + checks + " command/display checks");
        Bukkit.shutdown();
    }
}
