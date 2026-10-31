package io.github.rokeydoess.justjobs;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.EnderDragonPart;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.UUID;

/** One full reward per eligible contributor, kept on the boss for its lifetime. */
public final class BossRewards implements Listener {
    private final JustJobsPlugin plugin;
    private final NamespacedKey contributors, rewarded;

    public BossRewards(JustJobsPlugin plugin) {
        this.plugin = plugin;
        contributors = new NamespacedKey(plugin, "boss_contributors");
        rewarded = new NamespacedKey(plugin, "boss_rewarded");
    }
    static boolean isBoss(EntityType type) { return type == EntityType.ENDER_DRAGON || type == EntityType.WITHER; }
    static boolean handles(JobRegistry registry, EntityType type) {
        return isBoss(type) && registry.jobs().stream().flatMap(job -> job.rules().stream())
                .anyMatch(rule -> rule.matches(JobRegistry.Activity.BOSS_KILL, null, type));
    }
    private boolean eligibleSpawn(org.bukkit.entity.LivingEntity boss) {
        if (plugin.settings().getBoolean("rules.reward-spawner-mobs", false)) return true;
        return !boss.fromMobSpawner() && switch (boss.getEntitySpawnReason()) {
            case SPAWNER, TRIAL_SPAWNER, SPAWNER_EGG, DISPENSE_EGG, COMMAND, CUSTOM -> false;
            default -> true;
        };
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        var target = event.getEntity() instanceof EnderDragonPart part ? part.getParent() : event.getEntity();
        if (!isBoss(target.getType()) || !(target instanceof org.bukkit.entity.LivingEntity boss)
                || !Double.isFinite(event.getFinalDamage()) || event.getFinalDamage() <= 0
                || !(event.getDamageSource().getCausingEntity() instanceof Player player)
                || !plugin.eligible(player) || !eligibleSpawn(boss)
                || plugin.settings().getStringList("rules.excluded-worlds").contains(boss.getWorld().getName())) return;
        var data = boss.getPersistentDataContainer();
        if (data.has(rewarded)) return;
        long[] previous = data.getOrDefault(contributors, PersistentDataType.LONG_ARRAY, new long[0]);
        UUID id = player.getUniqueId();
        for (int i = 0; i + 1 < previous.length; i += 2)
            if (previous[i] == id.getMostSignificantBits() && previous[i + 1] == id.getLeastSignificantBits()) return;
        // Each player is written once, rather than on every hit.
        long[] next = Arrays.copyOf(previous, previous.length + 2);
        next[previous.length] = id.getMostSignificantBits(); next[previous.length + 1] = id.getLeastSignificantBits();
        data.set(contributors, PersistentDataType.LONG_ARRAY, next);
        plugin.profile(player);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void death(EntityDeathEvent event) {
        var boss = event.getEntity();
        if (!handles(plugin.registry(), boss.getType()) || !eligibleSpawn(boss)) return;
        var data = boss.getPersistentDataContainer();
        if (data.has(rewarded)) return;
        long[] players = data.getOrDefault(contributors, PersistentDataType.LONG_ARRAY, new long[0]);
        data.set(rewarded, PersistentDataType.BYTE, (byte) 1);
        data.remove(contributors);
        for (int i = 0; i + 1 < players.length; i += 2)
            plugin.awardBoss(new UUID(players[i], players[i + 1]), boss.getWorld(), boss.getType());
        if (players.length > 0) plugin.save();
    }
}
