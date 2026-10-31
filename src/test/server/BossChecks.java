package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Boss participation, bonus caps, native damage, and entity persistence on Paper. */
public final class BossChecks extends CraftingChecks {
    NamespacedKey participants;
    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "boss rewards start on Paper");
        world = Bukkit.getWorlds().getFirst(); worker = (ExecutorService) field(jobs, "worker");
        participants = new NamespacedKey(jobs, "boss_contributors");
        var marker = getDataFolder().toPath().resolve("boss.yml");
        if (Files.exists(marker)) { restartBoss(marker); return; }
        sharedRewards(); exclusions(); capsAndConfiguration(); nativeDamage(); saveBoss(marker);
    }
    <T extends LivingEntity> T spawn(Class<T> type, SpawnReason reason) {
        var location = new Location(world, 200.5, 100, 200.5);
        location.getChunk().addPluginChunkTicket(this);
        var boss = world.spawn(location, type, reason, entity -> {
            entity.setAI(false); entity.setGravity(false); entity.setPersistent(true);
        });
        if (boss instanceof Wither wither) wither.setInvulnerabilityTicks(0);
        return boss;
    }
    Wither wither() { return spawn(Wither.class, SpawnReason.BUILD_WITHER); }
    void hit(Entity target, Player actor, double amount, boolean cancelled) {
        var source = DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(actor).withDirectEntity(actor).build();
        var event = new EntityDamageByEntityEvent(actor, target, DamageCause.ENTITY_ATTACK, source, amount);
        event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event);
    }
    void die(LivingEntity target, boolean cancelled) {
        var event = new EntityDeathEvent(target, DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>());
        event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event);
    }
    void expect(Table actor, double xp, String money, String label) {
        var p = jobs.profile(actor.player());
        check(p.xp("hunting") == xp && p.pending.compareTo(new BigDecimal(money)) == 0, label);
    }
    int count(LivingEntity boss) {
        return boss.getPersistentDataContainer().getOrDefault(participants, PersistentDataType.LONG_ARRAY, new long[0]).length / 2;
    }
    void sharedRewards() throws Exception {
        var first = table(); var second = table(); var spectator = table(); var dragon = spawn(EnderDragon.class, SpawnReason.DEFAULT);
        check(!dragon.getParts().isEmpty(), "the real dragon exposes its damageable parts");
        hit(dragon.getParts().iterator().next(), first.player(), 1, false);
        hit(dragon, first.player(), 2, false);
        var arrow = world.spawn(new Location(world, 200, 100, 200), Arrow.class);
        arrow.setShooter(second.player());
        var source = DamageSource.builder(DamageType.ARROW).withDirectEntity(arrow).withCausingEntity(second.player()).build();
        Bukkit.getPluginManager().callEvent(new EntityDamageByEntityEvent(arrow, dragon, DamageCause.PROJECTILE, source, 2));
        check(count(dragon) == 2, "dragon parts and projectiles record unique contributors rather than hits");
        die(dragon, true); expect(first, 0, "0", "a cancelled boss death pays nobody");
        check(count(dragon) == 2, "cancelled death retains lifetime participation");
        die(dragon, false);
        expect(first, 200, "50", "the first dragon contributor receives the full XP and money bonus");
        expect(second, 200, "50", "a projectile contributor receives the same full dragon bonus");
        expect(spectator, 0, "0", "being near a boss without damaging it earns nothing");
        die(dragon, false); expect(first, 200, "50", "repeated death notifications cannot repeat a boss bonus");
        check(jobs.capEarnings(jobs.profile(first.player()), "hunting", System.currentTimeMillis()).signum() == 0,
                "a dragon bonus leaves the ordinary hunting allowance unused");
        check(jobs.profile(first.player()).paycheck.available().compareTo(new BigDecimal("50")) == 0, "boss bonuses become payable immediately");
        var boss = wither(); hit(boss, first.player(), 1, false); die(boss, false);
        expect(first, 250, "62.5", "a player-built Wither pays one quarter of the dragon's money and XP");
        check(Paycheck.byJob(jobs.profile(first.player()).paycheck.snapshot().due()).get("hunting").compareTo(new BigDecimal("62.5")) == 0,
                "separate boss sources still attribute their total to the Hunting job");
        dragon.remove(); boss.remove(); arrow.remove();
    }
    void exclusions() throws Exception {
        var accepted = table(); var denied = table(); var creative = table(); creative.player().setGameMode(GameMode.CREATIVE);
        var permission = denied.player().addAttachment(this, "justjobs.earn", false);
        var boss = wither(); hit(boss, accepted.player(), 0, false); hit(boss, accepted.player(), 1, true);
        hit(boss, creative.player(), 1, false); hit(boss, denied.player(), 1, false);
        check(count(boss) == 0, "zero, cancelled, creative, and permission-denied hits do not qualify");
        denied.player().removeAttachment(permission);
        jobs.settings().set("rules.excluded-worlds", List.of(world.getName())); hit(boss, accepted.player(), 1, false);
        check(count(boss) == 0, "excluded worlds cannot add boss contributors");
        jobs.settings().set("rules.excluded-worlds", List.of());
        die(boss, false); expect(accepted, 0, "0", "a boss with no qualifying contributors pays nobody");
        boss.remove();
        var command = spawn(Wither.class, SpawnReason.COMMAND); hit(command, accepted.player(), 1, false); die(command, false);
        expect(accepted, 0, "0", "command-spawned bosses follow the artificial-spawn restriction"); command.remove();
        jobs.settings().set("rules.reward-spawner-mobs", true);
        var allowed = spawn(Wither.class, SpawnReason.CUSTOM); hit(allowed, accepted.player(), 1, false); die(allowed, false);
        expect(accepted, 50, "12.5", "allowing artificial spawns also permits their configured boss reward");
        jobs.settings().set("rules.reward-spawner-mobs", false); allowed.remove();
    }
    void capsAndConfiguration() throws Exception {
        var actor = table(); jobs.award(actor.player(), JobRegistry.Activity.MOB_KILL, null, EntityType.ZOMBIE, 500);
        var boss = wither(); hit(boss, actor.player(), 1, false); die(boss, false);
        expect(actor, 2550, "37.5", "boss bonuses pay in full even after the normal payday cap is reached"); boss.remove();
        jobs.settings().set("economy.payday-enabled", false);
        var immediate = table(); var first = wither(); hit(first, immediate.player(), 1, false); die(first, false);
        jobs.award(immediate.player(), JobRegistry.Activity.MOB_KILL, null, EntityType.ZOMBIE, 500);
        expect(immediate, 2550, "37.5", "a bonus leaves the full rolling-hour allowance available in immediate mode"); first.remove();
        jobs.settings().set("economy.payday-enabled", true);
        var file = jobs.getDataFolder().toPath().resolve("jobs.yml"); String original = Files.readString(file);
        try {
            var config = YamlConfiguration.loadConfiguration(file.toFile());
            var rules = new ArrayList<Map<String, Object>>();
            for (var raw : config.getMapList("jobs.hunting.rules")) {
                var rule = new LinkedHashMap<String, Object>(); raw.forEach((key, value) -> rule.put(key.toString(), value));
                if ("boss-kill".equals(rule.get("activity"))) { rule.put("bonus", false); rule.put("money", "3.25"); rule.put("xp", 9); }
                rules.add(rule);
            }
            config.set("jobs.hunting.rules", rules); config.save(file.toFile()); reload();
            var limited = table(); jobs.award(limited.player(), JobRegistry.Activity.MOB_KILL, null, EntityType.ZOMBIE, 500);
            var capped = wither(); hit(capped, limited.player(), 1, false); die(capped, false);
            expect(limited, 2509, "25", "turning bonus off applies the cap while custom boss XP still pays"); capped.remove();
            var custom = table(); var customBoss = wither(); hit(customBoss, custom.player(), 1, false); die(customBoss, false);
            expect(custom, 9, "3.25", "reloaded boss rules apply custom money and XP"); customBoss.remove();
            rules.getFirst().put("bonus", true); config.set("jobs.hunting.rules", rules); config.save(file.toFile()); reload();
            var reusable = table(); jobs.award(reusable.player(), JobRegistry.Activity.MOB_KILL, null, EntityType.ZOMBIE, 600);
            expect(reusable, 3000, "30", "the bonus rule option is reusable for activities beyond boss fights");
            check(jobs.capEarnings(jobs.profile(reusable.player()), "hunting", System.currentTimeMillis()).signum() == 0,
                    "a custom non-boss bonus also leaves the normal allowance untouched");
        } finally { Files.writeString(file, original); reload(); }
    }
    void nativeDamage() throws Exception {
        var actor = table(); var boss = wither();
        boss.damage(1, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(actor.player()).withDirectEntity(actor.player()).build());
        check(count(boss) == 1 && boss.getHealth() < boss.getMaxHealth(), "real server damage records the attacking player");
        boss.kill(DamageSource.builder(DamageType.GENERIC_KILL).build());
        expect(actor, 50, "12.5", "an environmental finishing blow still pays the earlier contributor");
    }
    void saveBoss(java.nio.file.Path marker) throws Exception {
        var actor = table(); var boss = wither(); hit(boss, actor.player(), 1, false);
        var saved = new YamlConfiguration(); saved.set("boss", boss.getUniqueId().toString()); saved.set("player", actor.player().getUniqueId().toString());
        jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS); world.save();
        Files.createDirectories(getDataFolder().toPath()); saved.save(marker.toFile()); finishBoss(1);
    }
    void restartBoss(java.nio.file.Path marker) throws Exception {
        var saved = YamlConfiguration.loadConfiguration(marker.toFile()); world.getChunkAt(12, 12).addPluginChunkTicket(this);
        var boss = (Wither) Bukkit.getEntity(UUID.fromString(saved.getString("boss")));
        var actor = jobs.players().get(UUID.fromString(saved.getString("player")));
        check(boss != null && count(boss) == 1 && actor != null, "boss participation and offline profiles survive restart");
        check(boss.getEntitySpawnReason() == SpawnReason.BUILD_WITHER, "the legitimate Wither spawn reason survives restart");
        die(boss, false); check(actor.xp("hunting") == 50 && actor.pending.compareTo(new BigDecimal("12.5")) == 0,
                "an offline lifetime contributor receives the full bonus after restart");
        die(boss, false); check(actor.pending.compareTo(new BigDecimal("12.5")) == 0, "a restarted boss cannot pay the same contributor twice");
        check(actor.paycheck.available().compareTo(new BigDecimal("12.5")) == 0, "the restarted bonus is payable without waiting for payday");
        finishBoss(2);
    }
    void reload() { jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"}); }
    void finishBoss(int phase) { getLogger().info("RELEASE CHECKS PHASE " + phase + " PASS: " + checks + " boss checks"); Bukkit.shutdown(); }
}
