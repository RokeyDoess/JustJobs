package io.github.rokeydoess.justjobs;

import io.papermc.paper.event.block.PlayerShearBlockEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameEvent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.Brushable;
import org.bukkit.entity.Player;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.GenericGameEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.github.rokeydoess.justjobs.JobRegistry.Activity.*;

/** Player actions outside crafting grids and workstation menus. */
public final class AdditionalActivities implements Listener {
    private final JustJobsPlugin plugin;
    private final NaturalBlocks natural;
    private final NamespacedKey tamed;
    private final NamespacedKey cured;
    private record Excavation(UUID player, Material material) { }
    private final Map<Block, Excavation> excavations = new HashMap<>();

    public AdditionalActivities(JustJobsPlugin plugin, NaturalBlocks natural) {
        this.plugin = plugin; this.natural = natural;
        tamed = new NamespacedKey(plugin, "taming_rewarded");
        cured = new NamespacedKey(plugin, "curing_rewarded");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void shear(PlayerShearEntityEvent event) {
        if (event.getDrops().stream().anyMatch(item -> !item.isEmpty()))
            plugin.award(event.getPlayer(), SHEAR, null, event.getEntity().getType(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void honeycomb(PlayerShearBlockEvent event) {
        if (event.getBlock().getBlockData() instanceof org.bukkit.block.data.type.Beehive hive
                && hive.getHoneyLevel() == hive.getMaximumHoneyLevel()
                && event.getDrops().stream().anyMatch(item -> item.getType() == Material.HONEYCOMB && !item.isEmpty()))
            plugin.award(event.getPlayer(), BEEHIVE_HARVEST, Material.HONEYCOMB, null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void honeyBottle(GenericGameEvent event) {
        // Vanilla emits this after filling the bottle, before resetting the hive's honey level.
        if (event.isAsynchronous() || event.getEvent() != GameEvent.FLUID_PICKUP || !(event.getEntity() instanceof Player player)) return;
        if (event.getLocation().getBlock().getBlockData() instanceof org.bukkit.block.data.type.Beehive hive
                && hive.getHoneyLevel() == hive.getMaximumHoneyLevel())
            plugin.award(player, BEEHIVE_HARVEST, Material.HONEY_BOTTLE, null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void tame(EntityTameEvent event) {
        if (!(event.getOwner() instanceof Player player) || !plugin.eligible(player)) return;
        var data = event.getEntity().getPersistentDataContainer();
        if (data.has(tamed, PersistentDataType.BYTE)) return;
        data.set(tamed, PersistentDataType.BYTE, (byte) 1);
        plugin.award(player, TAME, null, event.getEntityType(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void transform(EntityTransformEvent event) {
        for (var key : java.util.List.of(tamed, cured))
            if (event.getEntity().getPersistentDataContainer().has(key, PersistentDataType.BYTE))
                event.getTransformedEntities().forEach(entity -> entity.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1));
        if (event.getTransformReason() != EntityTransformEvent.TransformReason.CURED
                || !(event.getEntity() instanceof ZombieVillager zombie)
                || event.getEntity().getPersistentDataContainer().has(cured, PersistentDataType.BYTE)) return;
        var owner = zombie.getConversionPlayer();
        var player = owner == null ? null : owner.getPlayer();
        if (player == null || !plugin.eligible(player)) return;
        zombie.getPersistentDataContainer().set(cured, PersistentDataType.BYTE, (byte) 1);
        event.getTransformedEntities().forEach(entity -> entity.getPersistentDataContainer().set(cured, PersistentDataType.BYTE, (byte) 1));
        plugin.award(player, CURE_VILLAGER, null, org.bukkit.entity.EntityType.VILLAGER, 1);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void brushing(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Player player) || !(event.getBlock().getBlockData() instanceof Brushable)
                || event.getTo() != Material.SAND && event.getTo() != Material.GRAVEL || !natural.eligible(event.getBlock())) return;
        var candidate = new Excavation(player.getUniqueId(), event.getBlock().getType());
        excavations.put(event.getBlock(), candidate);
        Bukkit.getScheduler().runTask(plugin, () -> excavations.remove(event.getBlock(), candidate));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void excavated(BlockDropItemEvent event) {
        var candidate = excavations.remove(event.getBlock());
        if (candidate == null || !candidate.player().equals(event.getPlayer().getUniqueId())
                || candidate.material() != event.getBlockState().getType() || event.getItems().stream().noneMatch(item -> !item.getItemStack().isEmpty())) return;
        plugin.award(event.getPlayer(), BRUSH, candidate.material(), null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void biome(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || !event.hasChangedBlock() || !plugin.eligible(event.getPlayer())) return;
        plugin.discoverBiome(event.getPlayer(), event.getTo().getBlock().getBiome().getKey().toString());
    }
}
