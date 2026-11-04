package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Grouped job selection, detail navigation, custom categories, and overflow on Paper. */
public final class GroupedMenuChecks extends ReleaseChecks {
    JobsMenu menus;
    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "grouped menus start on Paper");
        world = Bukkit.getWorlds().getFirst(); worker = (ExecutorService) field(jobs, "worker");
        player = player(MAIN, "MenuPlayer"); profile = jobs.profile(player); menus = new JobsMenu(jobs);
        var marker = getDataFolder().toPath().resolve("restart");
        if (Files.exists(marker)) {
            check(jobs.registry().get("extra_gather_0").category() == JobRegistry.Category.CRAFTING,
                    "custom menu category survives restart");
            menus.open(player, false, 2, null);
            check(slots().containsValue("long_rewards"), "overflow jobs remain reachable after restart");
            finish(2); return;
        }
        menus.open(player, false, 0, null);
        check(slots().size() == 17, "all seventeen default jobs fit on the grouped overview");
        check(slots().keySet().equals(Set.of(11, 12, 13, 14, 15, 19, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33))
                        && jobSlot("archaeology") / 9 == 3,
                "Archaeology joins Adventure and the default rows are centered with five, seven, and five jobs");
        for (var entry : slots().entrySet()) {
            var job = jobs.registry().get(entry.getValue());
            check(entry.getKey() / 9 == job.category().ordinal() + 1, job.name() + " occupies its category row");
            check(lore(entry.getKey()).size() == 4 && lore(entry.getKey()).getFirst().equals("Level 1  □□□□□□□□□□  0%")
                            && lore(entry.getKey()).getLast().equals("Click for activities and rewards"),
                    job.name() + " has a compact progress entry");
        }
        check(cards() == slots().size(), "the content rows have no category labels");
        check(current.getItem(0).getType() == Material.GRAY_STAINED_GLASS_PANE && current.getItem(4).getType() == Material.BOOK
                        && current.getItem(36).getType() == Material.COMPARATOR && current.getItem(39).getType() == Material.CLOCK
                        && current.getItem(41).getType() == Material.BOOK && current.getItem(44).getType() == Material.OAK_DOOR,
                "the overview preserves the existing top and bottom controls");
        progressFormat();
        click(jobSlot("mining")); later(2, this::miningDetails);
    }
    void progressFormat() throws Exception {
        var file = jobs.getDataFolder().toPath().resolve("config.yml");
        String original = Files.readString(file);
        try {
            var config = YamlConfiguration.loadConfiguration(file.toFile());
            config.set("display.job-progress-format", "Lv {level} [{bar}] {percent}%");
            config.set("display.job-progress-bar-length", 6);
            config.set("display.job-progress-bar-filled", "#");
            config.set("display.job-progress-bar-empty", "-");
            config.save(file.toFile()); reload();
            profile.xp.put("mining", jobs.registry().get("mining").progression().required(1) / 2);
            menus.open(player, false, 0, null);
            check(lore(jobSlot("mining")).getFirst().equals("Lv 1 [###---] 50%"), "reload applies the custom progress bar and format");
            config.set("display.job-progress-format", ""); config.save(file.toFile()); reload();
            menus.open(player, false, 0, null);
            check(lore(jobSlot("mining")).getFirst().startsWith("Earned this paycheck: "), "an empty format hides only the progress line");
        } finally {
            profile.xp.remove("mining"); Files.writeString(file, original); reload();
            menus.open(player, false, 0, null);
        }
    }
    @SuppressWarnings("unchecked") Map<Integer, String> slots() throws Exception { return (Map<Integer, String>) field(current.getHolder(), "jobSlots"); }
    String name(int slot) { return PaydayChecks.plain(current.getItem(slot).getItemMeta().displayName()); }
    int button(String name) {
        for (int slot = 36; slot < 44; slot++) if (current.getItem(slot) != null && name(slot).equals(name)) return slot;
        throw new AssertionError("Missing menu button: " + name);
    }
    void click(int slot) {
        var event = new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        menus.click(event); check(event.isCancelled(), "menu click stays protected at slot " + slot);
    }
    int cards() {
        int count = 0; for (int slot = 9; slot <= 35; slot++) if (current.getItem(slot) != null) count++;
        return count;
    }
    void miningDetails() throws Exception {
        check("mining".equals(field(current.getHolder(), "job")) && !(boolean) field(current.getHolder(), "leaderboard") && cards() == 4,
                "clicking Mining opens four individual reward entries");
        check(allLore().stream().anyMatch(line -> line.contains("$0.006 / stone block"))
                        && allLore().stream().anyMatch(line -> line.contains("$0.35 / rare ore")),
                "Mining details retain named reward rates");
        check(lore(4).stream().anyMatch(line -> line.contains("XP")) && lore(4).stream().noneMatch(line -> line.contains("/ stone block")),
                "the detail header shows progress without repeating the reward list");
        var drag = new InventoryDragEvent(view(), new ItemStack(Material.DIRT), new ItemStack(Material.DIRT), false, Map.of(19, new ItemStack(Material.DIRT)));
        menus.drag(drag); check(drag.isCancelled(), "reward entries cannot be dragged out");
        click(36); later(2, () -> {
            check(current.getSize() == 27, "job details open the shared settings menu");
            click(22); later(2, () -> {
                check("mining".equals(field(current.getHolder(), "job")) && cards() == 4, "Settings returns to the same job details");
                click(button("Job leaderboard")); later(2, () -> {
                    check((boolean) field(current.getHolder(), "leaderboard") && "mining".equals(field(current.getHolder(), "job")),
                            "the detail shortcut opens that job's leaderboard");
                    overflow();
                });
            });
        });
    }
    void overflow() throws Exception {
        var file = jobs.getDataFolder().toPath().resolve("jobs.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(file);
        for (int i = 0; i < 14; i++) {
            String path = "jobs.extra_gather_" + i;
            config.set(path + ".name", "Extra Gathering " + i); config.set(path + ".icon", "DIRT");
            config.set(path + ".rules", List.of(Map.of("activity", "block-break", "materials", List.of("DIRT"), "xp", 1, "money", "0.01")));
        }
        config.set("jobs.extra_gather_0.category", "crafting");
        var rules = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 31; i++) rules.add(Map.of("activity", "block-break", "materials", List.of("SAND"),
                "description", "reward " + i, "xp", 1, "money", "0.01"));
        config.set("jobs.long_rewards.name", "Many Activities"); config.set("jobs.long_rewards.rules", rules);
        config.set("jobs.disabled.category", "adventure"); config.set("jobs.disabled.enabled", false); config.save(file);
        reload();
        check(jobs.registry().get("extra_gather_0").category() == JobRegistry.Category.CRAFTING
                        && jobs.registry().get("extra_gather_1").category() == JobRegistry.Category.GATHERING,
                "explicit categories override automatic grouping for custom jobs");
        var seen = new HashSet<String>();
        for (int page = 0; page < 3; page++) {
            menus.open(player, false, page, null);
            for (var entry : slots().entrySet()) {
                check(seen.add(entry.getValue()), "overflow job appears once: " + entry.getValue());
                check(entry.getKey() / 9 == jobs.registry().get(entry.getValue()).category().ordinal() + 1,
                        "overflow job stays in its category row");
            }
        }
        check(seen.size() == 32 && !seen.contains("disabled"), "all enabled jobs remain accessible across category pages");
        menus.open(player, true, 0, null);
        check(cards() == slots().size() && slots().keySet().containsAll(List.of(9, 17)),
                "a full group uses both ends of its row without category labels on the leaderboard selector");
        menus.open(player, true, 2, null);
        check(slots().containsValue("long_rewards") && current.getItem(0).getType() == Material.PURPLE_STAINED_GLASS_PANE,
                "the leaderboard selector shares category pagination and its purple border");
        menus.open(player, false, 2, null); click(jobSlot("long_rewards"));
        later(2, () -> {
            check(cards() == 27 && (int) field(current.getHolder(), "parentPage") == 2, "large reward lists open with 27 entries and remember the overview page");
            click(button("Next page")); later(2, () -> {
                check(cards() == 4 && (int) field(current.getHolder(), "page") == 1, "remaining reward entries use a second detail page");
                check(allLore().stream().anyMatch(line -> line.contains("reward 30")), "the final configured reward is reachable");
                click(36); later(2, () -> {
                    click(22); later(2, () -> {
                        check(cards() == 4 && (int) field(current.getHolder(), "page") == 1, "Settings preserves the reward page");
                        click(button("Back")); later(2, this::returned);
                    });
                });
            });
        });
    }
    void returned() throws Exception {
        check(field(current.getHolder(), "job") == null && (int) field(current.getHolder(), "page") == 2 && slots().containsValue("long_rewards"),
                "Back returns to the overview page the job came from");
        var file = jobs.getDataFolder().toPath().resolve("jobs.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(file); config.set("jobs.long_rewards.category", "unknown"); config.save(file);
        var registry = jobs.registry(); reload();
        check(jobs.registry() == registry, "an invalid category rejects reload without replacing the working jobs");
        config.set("jobs.long_rewards.category", "gathering"); config.save(file); reload();
        menus.open(player, false, 2, null); var old = current.getHolder(); click(jobSlot("long_rewards"));
        menus.open(player, false, 0, null);
        later(2, () -> {
            check(current.getHolder() != old && field(current.getHolder(), "job") == null && (int) field(current.getHolder(), "page") == 0,
                    "a queued job click cannot reopen details after leaving its page");
            jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            var marker = getDataFolder().toPath().resolve("restart"); Files.createDirectories(marker.getParent()); Files.writeString(marker, "done"); finish(1);
        });
    }
    void reload() { jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"}); }
    void finish(int phase) { getLogger().info("RELEASE CHECKS PHASE " + phase + " PASS: " + checks + " grouped menu checks"); Bukkit.shutdown(); }
}
