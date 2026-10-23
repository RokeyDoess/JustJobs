package io.github.rokeydoess.justjobs;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.github.rokeydoess.justjobs.JobRegistry.Activity.*;

public final class ActivityListener implements Listener {
    private final JustJobsPlugin plugin;
    private final NaturalBlocks natural;
    private final NamespacedKey spawned;
    private final Map<UUID, TravelSample> travel = new HashMap<>();
    private final Map<UUID, WalkWindow> walking = new HashMap<>();
    private record TravelSample(Player player, Location location, UUID vehicle, double pending) { }
    private record WalkWindow(int second, double distance) { }

    public ActivityListener(JustJobsPlugin plugin, NaturalBlocks natural) {
        this.plugin = plugin; this.natural = natural;
        spawned = new NamespacedKey(plugin, "artificial_spawn");
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) {
        var block = e.getBlock();
        if (block.getBlockData() instanceof Ageable crop && crop.getAge() == crop.getMaximumAge())
            plugin.award(e.getPlayer(), CROP_HARVEST, block.getType(), null, 1);
        if (!natural.playerPlaced(block))
            plugin.award(e.getPlayer(), BLOCK_BREAK, block.getType(), null, 1, natural.eligible(block));
        natural.mark(block);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        var player = e.getPlayer();
        var block = e.getBlockPlaced();
        if (!e.canBuild() || !plugin.eligible(player) || !plugin.registry().rewards(BLOCK_PLACE, block.getType(), null)) return;
        var blocks = e instanceof BlockMultiPlaceEvent multi
                ? multi.getReplacedBlockStates().stream().map(org.bukkit.block.BlockState::getBlock).toList() : java.util.List.of(block);
        // A bed/door uses one item but occupies several positions. Claim all of them and pay once.
        if (blocks.stream().anyMatch(natural::placementRewarded)) return;
        blocks.forEach(natural::claimPlacement);
        plugin.award(player, BLOCK_PLACE, block.getType(), null, 1);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void harvest(PlayerHarvestBlockEvent e) {
        if (e.getItemsHarvested().stream().anyMatch(item -> !item.isEmpty()))
            plugin.award(e.getPlayer(), CROP_HARVEST, e.getHarvestedBlock().getType(), null, 1);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breed(EntityBreedEvent e) {
        if (e.getBreeder() instanceof Player player) plugin.award(player, BREED, null, e.getEntityType(), 1);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH && e.getCaught() instanceof org.bukkit.entity.Item item && !item.getItemStack().isEmpty())
            plugin.award(e.getPlayer(), FISH, item.getItemStack().getType(), null, 1);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent e) {
        if (switch (e.getSpawnReason()) {
            case SPAWNER, SPAWNER_EGG, DISPENSE_EGG, COMMAND, CUSTOM, BREEDING, BUILD_IRONGOLEM, BUILD_SNOWMAN, BUILD_WITHER, TRIAL_SPAWNER -> true;
            default -> false;
        }) e.getEntity().getPersistentDataContainer().set(spawned, PersistentDataType.BYTE, (byte) 1);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void transform(EntityTransformEvent e) {
        if (e.getEntity().fromMobSpawner() || e.getEntity().getPersistentDataContainer().has(spawned, PersistentDataType.BYTE))
            e.getTransformedEntities().forEach(entity -> entity.getPersistentDataContainer().set(spawned, PersistentDataType.BYTE, (byte) 1));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void death(EntityDeathEvent e) {
        if (e.isCancelled()) return;
        var killer = e.getEntity().getKiller();
        if (killer == null || !plugin.eligible(killer)) return;
        if (e.getEntity() instanceof Player victim) {
            if (victim.getUniqueId().equals(killer.getUniqueId()) || victim.getGameMode() != org.bukkit.GameMode.SURVIVAL && victim.getGameMode() != org.bukkit.GameMode.ADVENTURE) return;
            if (!plugin.registry().rewards(PLAYER_KILL, null, victim.getType())) return;
            if (!plugin.pvpAllowed(killer.getUniqueId(), victim.getUniqueId())) return;
            plugin.award(killer, PLAYER_KILL, null, victim.getType(), 1);
            plugin.save();
        } else {
            if (!plugin.settings().getBoolean("rules.reward-spawner-mobs", true)
                    && (e.getEntity().fromMobSpawner() || e.getEntity().getPersistentDataContainer().has(spawned, PersistentDataType.BYTE))) return;
            plugin.award(killer, MOB_KILL, null, e.getEntityType(), 1);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void enchant(EnchantItemEvent e) {
        // The displayed requirement (e.g. 30) is not the price: the three buttons cost 1, 2, 3 levels.
        if (!e.isCancelled() && !e.getEnchantsToAdd().isEmpty())
            plugin.award(e.getEnchanter(), ENCHANT, e.getItem().getType(), null, e.whichButton() + 1);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trade(PlayerTradeEvent e) {
        int emeralds = e.getTrade().getAdjustedIngredient1().getType() == Material.EMERALD ? e.getTrade().getAdjustedIngredient1().getAmount() : 0;
        var second = e.getTrade().getIngredients().size() > 1 ? e.getTrade().getIngredients().get(1) : null;
        if (second != null && second.getType() == Material.EMERALD) emeralds += second.getAmount();
        if (emeralds > 0) plugin.award(e.getPlayer(), TRADE, null, null, emeralds);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) { plugin.profile(e.getPlayer()); travel.remove(e.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) { clearSailing(e.getPlayer().getUniqueId()); plugin.hideBar(e.getPlayer().getUniqueId()); walking.remove(e.getPlayer().getUniqueId()); plugin.save(); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) { clearSailing(e.getPlayer().getUniqueId()); walking.remove(e.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void world(PlayerChangedWorldEvent e) { travel.remove(e.getPlayer().getUniqueId()); walking.remove(e.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent e) { travel.remove(e.getPlayer().getUniqueId()); walking.remove(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (e instanceof PlayerTeleportEvent || e.isCancelled() || !e.hasChangedPosition()) return;
        Player player = e.getPlayer();
        if (!plugin.eligible(player) || player.getVehicle() != null || player.isFlying() || player.isGliding()
                || player.isRiptiding() || player.isInWater() || player.isSwimming() || player.getFallDistance() > 3
                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)
                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)) return;
        if (e.getFrom().getWorld() != e.getTo().getWorld()) return;
        double distance = Math.hypot(e.getTo().getX() - e.getFrom().getX(), e.getTo().getZ() - e.getFrom().getZ());
        if (!Double.isFinite(distance) || distance <= 0) return;
        int second = Math.floorDiv(Bukkit.getCurrentTick(), 20);
        var previous = walking.get(player.getUniqueId());
        double used = previous == null || previous.second() != second ? 0 : previous.distance();
        double maximum = plugin.settings().getDouble("rules.maximum-walk-blocks-per-sample", 12);
        if (distance > maximum || used + distance > maximum + 1e-9) return;
        walking.put(player.getUniqueId(), new WalkWindow(second, used + distance));
        plugin.award(player, TRAVEL, null, null, distance);
    }

    public void sampleTravel() {
        for (Player player : Bukkit.getOnlinePlayers()) sampleSailing(player);
    }
    public void sampleSailing(Player player) {
        UUID id = player.getUniqueId();
        if (!plugin.eligible(player) || player.isFlying() || player.isGliding() || player.isRiptiding()) { clearSailing(id); return; }
        var vehicle = player.getVehicle();
        if (!(vehicle instanceof Boat) || vehicle.getPassengers().isEmpty() || vehicle.getPassengers().getFirst() != player || !vehicle.isInWater()) { clearSailing(id); return; }
        var location = player.getLocation();
        UUID vehicleId = vehicle.getUniqueId();
        var previous = travel.get(id);
        travel.put(id, new TravelSample(player, location.clone(), vehicleId, 0));
        if (previous == null) return;
        if (previous.location().getWorld() != location.getWorld() || !java.util.Objects.equals(previous.vehicle(), vehicleId)) {
            flushSailing(previous); return;
        }
        double dx = location.getX() - previous.location().getX();
        double dz = location.getZ() - previous.location().getZ();
        double distance = Math.hypot(dx, dz);
        double maximum = plugin.settings().getDouble("rules.maximum-sail-blocks-per-sample", 35);
        if (!Double.isFinite(distance) || distance > maximum) { flushSailing(previous); return; }
        double accumulated = previous.pending() + distance;
        if (accumulated + 1e-9 < plugin.settings().getDouble("rules.travel-minimum-displacement", 0.5))
            travel.put(id, new TravelSample(player, location.clone(), vehicleId, accumulated));
        else if (accumulated > 0) plugin.award(player, SAIL, null, null, accumulated);
    }
    private void flushSailing(TravelSample sample) {
        if (sample.pending() > 0) plugin.award(sample.player(), SAIL, null, null, sample.pending());
    }
    private void clearSailing(UUID id) { var previous = travel.remove(id); if (previous != null) flushSailing(previous); }
    public void resetTravel() { travel.values().forEach(this::flushSailing); travel.clear(); walking.clear(); }
}
