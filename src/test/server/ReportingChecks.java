package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Deposit history, cap reports, permissions, and restart persistence on Paper. */
public final class ReportingChecks extends ReleaseChecks {
    JobsMenu menus;
    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "reporting starts on Paper");
        world = Bukkit.getWorlds().getFirst(); player = player(MAIN, "ReportPlayer"); profile = jobs.profile(player);
        worker = (ExecutorService) field(jobs, "worker"); store = (DataStore) field(jobs, "store"); menus = new JobsMenu(jobs);
        if (Files.exists(getDataFolder().toPath().resolve("restart"))) {
            check(profile.economy("report_job").snapshot().capHits() == 1, "cap reaches survive restart");
            check(profile.economy("report_job").snapshot().paid().compareTo(new BigDecimal("4.36")) == 0, "deposited totals survive restart");
            check(receipts().size() == 2, "confirmed history survives restart");
            finish(2); return;
        }
        var settingsFile = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
        var settings = YamlConfiguration.loadConfiguration(settingsFile);
        settings.set("feedback.boss-bar", false); settings.set("feedback.level-up-chat", false);
        settings.set("feedback.milestone-level-interval", 0); settings.set("economy.payout-interval-seconds", 1);
        settings.save(settingsFile);
        var jobsFile = jobs.getDataFolder().toPath().resolve("jobs.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(jobsFile);
        config.set("jobs.report_job.name", "Reports");
        config.set("jobs.report_job.hourly-income-limit", "5");
        config.set("jobs.report_job.rules", List.of(
                Map.of("activity", "craft", "materials", List.of("TORCH"), "xp", 1, "money", "1"),
                Map.of("activity", "craft", "materials", List.of("TORCH"), "xp", 2, "money", "2")));
        config.save(jobsFile); jobs.loadSettings();
        jobs.award(player, JobRegistry.Activity.CRAFT, Material.TORCH, null, 2);
        jobs.award(player, JobRegistry.Activity.CRAFT, Material.TORCH, null, 1);
        var stats = profile.economy("report_job").snapshot();
        check(stats.earned().compareTo(new BigDecimal("5")) == 0 && stats.capped().compareTo(new BigDecimal("4")) == 0
                        && stats.capHits() == 1, "capped actions keep counting and record one cap reach");
        jobs.loadSettings();
        check(profile.economy("report_job").snapshot().capHits() == 1, "reload retains economy totals and cap reaches");
        jobs.awardProcessed(profile.id, world, JobRegistry.Activity.COOK, Material.COOKED_SALMON, 128);
        installProvider();
        jobs.awardBoss(profile.id, world, EntityType.WITHER);
        later(35, this::afterBonus);
    }
    List<DataStore.Receipt> receipts() throws Exception { return worker.submit(() -> store.history(MAIN)).get(10, TimeUnit.SECONDS); }
    void queue() throws Exception {
        var method = jobs.getClass().getDeclaredMethod("queueUnpaid"); method.setAccessible(true); method.invoke(jobs);
    }
    void afterBonus() throws Exception {
        var history = receipts();
        check(history.size() == 1 && history.getFirst().bonus() && history.getFirst().amount().compareTo(BigDecimal.TEN) == 0,
                "immediate bonus creates its own confirmed receipt");
        check(profile.economy("hunting").snapshot().paid().compareTo(BigDecimal.TEN) == 0, "bonus deposit updates the source job's paid total");
        rejected.add(MAIN);
        profile.paycheck.advance(jobs.nextPayday(), jobs.paydayInterval(), true); queue();
        later(35, () -> {
            check(receipts().size() == 1 && profile.economy("report_job").snapshot().paid().signum() == 0,
                    "failed paycheck attempts do not appear as deposits or increase paid totals");
            rejected.remove(MAIN); queue();
            later(35, this::afterPaycheck);
        });
    }
    void afterPaycheck() throws Exception {
        var history = receipts();
        check(history.size() == 2 && !history.getFirst().bonus() && history.getFirst().amount().compareTo(new BigDecimal("5")) == 0,
                "successful retry creates one receipt for the actual whole-number deposit");
        check(profile.pending.compareTo(new BigDecimal("0.64")) == 0
                        && profile.economy("report_job").snapshot().paid().compareTo(new BigDecimal("4.36")) == 0,
                "whole-number remainder stays unpaid and outside deposit totals");
        jobs.onCommand(player, jobs.getCommand("jobs"), "jobs", new String[]{"history"});
        menus.open(player, false, 0, null); var previous = current;
        later(5, () -> {
            check(current == previous, "a history query cannot replace a menu opened after the request");
            jobs.onCommand(player, jobs.getCommand("jobs"), "jobs", new String[]{"history"});
            later(5, this::historyMenu);
        });
    }
    void historyMenu() throws Exception {
        check(allLore().stream().anyMatch(line -> line.equals("Deposited: $5.00"))
                        && allLore().stream().anyMatch(line -> line.equals("Deposited: $10.00")),
                "history menu displays confirmed paycheck and bonus amounts");
        clickRecord(40); later(2, () -> {
            check(field(current.getHolder(), "job") == null, "history Back returns to the jobs overview");
            menus.open(player, false, 0, "mining");
            int clock = -1;
            for (int i = 36; i < 44; i++) if (current.getItem(i).getType() == Material.CLOCK) clock = i;
            check(clock >= 0, "job details retain the paycheck clock");
            menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, clock, ClickType.LEFT, InventoryAction.PICKUP_ALL));
            later(5, () -> {
                check(allLore().contains("Deposited: $5.00"), "clicking the paycheck clock opens history");
                clickRecord(40); later(2, () -> {
                    check("mining".equals(field(current.getHolder(), "job")), "history returns to its originating job details");
                    economyMenu();
                });
            });
        });
    }
    void economyMenu() throws Exception {
            jobs.onCommand(player, jobs.getCommand("jobs"), "jobs", new String[]{"economy"});
            check(allLore().contains("Earned: $15.64") && allLore().contains("Deposited: $15.00") && allLore().contains("Money above caps: $4.00"),
                    "admin economy reports earnings, deposits, and capped amounts separately");
            var drag = new InventoryDragEvent(view(), new ItemStack(Material.DIRT), new ItemStack(Material.DIRT), false, Map.of(9, new ItemStack(Material.DIRT)));
            jobs.records().drag(drag); check(drag.isCancelled(), "report entries cannot be dragged out");
            var previous = current;
            Player guest = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, method, args) ->
                    method.getName().equals("hasPermission") && args[0].equals("justjobs.admin") ? false : method.invoke(player, args));
            jobs.onCommand(guest, jobs.getCommand("jobs"), "jobs", new String[]{"economy"});
            check(current == previous && messages.get(MAIN).getLast().contains("permission"), "players without admin permission cannot open economy reports");
            check(jobs.onTabComplete(guest, jobs.getCommand("jobs"), "jobs", new String[]{""}).contains("history")
                            && !jobs.onTabComplete(guest, jobs.getCommand("jobs"), "jobs", new String[]{""}).contains("economy"),
                    "command suggestions expose personal history and restrict economy to admins");
            jobs.records().economyText(Bukkit.getConsoleSender());
            pagination();
    }
    void pagination() throws Exception {
        var recipient = new PlayerProfile(new java.util.UUID(73, 1), "HistoryPages");
        worker.submit(() -> {
            for (int i = 0; i < 30; i++) {
                var payment = new DataStore.Payment("history" + i, recipient.id, recipient.name, BigDecimal.ONE, Map.of("mining", BigDecimal.ONE));
                store.reserve(recipient.snapshot(), payment);
                store.settle(List.of(new DataStore.Settlement(payment.id(), "PAID", recipient.snapshot(), payment.jobs(), System.currentTimeMillis() + i)));
            }
            return null;
        }).get(10, TimeUnit.SECONDS);
        check(receipts().size() == 2, "history queries isolate each player's receipts");
        player = player(recipient.id, recipient.name);
        jobs.records().openHistory(player, 0);
        later(5, () -> {
            check(cards() == 27, "history displays the newest twenty-seven deposits on its first page");
            clickRecord(43); later(2, () -> {
                check(cards() == 3, "history's second page contains the remaining three deposits");
                clickRecord(37); later(2, () -> {
                    check(cards() == 27, "Previous returns to the first history page");
                    player = player(MAIN, "ReportPlayer");
                    jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
                    Files.createDirectories(getDataFolder().toPath()); Files.writeString(getDataFolder().toPath().resolve("restart"), "done"); finish(1);
                });
            });
        });
    }
    int cards() {
        int count = 0;
        for (int slot = 9; slot < 36; slot++) if (current.getItem(slot) != null) count++;
        return count;
    }
    void clickRecord(int slot) {
        var event = new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        jobs.records().click(event); check(event.isCancelled(), "report clicks protect their display items");
    }
    void finish(int phase) { getLogger().info("RELEASE CHECKS PHASE " + phase + " PASS: " + checks + " reporting checks"); Bukkit.shutdown(); }
}
