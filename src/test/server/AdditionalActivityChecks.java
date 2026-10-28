package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.*;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.data.type.Beehive;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTables;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Exercises real hive use, archaeology, persistent animal history and biome discoveries. */
public final class AdditionalActivityChecks extends CraftingChecks {
    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "additional activities start on Paper");
        world = Bukkit.getWorlds().getFirst();
        world.getChunkAt(9, 9).addPluginChunkTicket(this);
        worker = (ExecutorService) field(jobs, "worker");
        history = HandlerList.getRegisteredListeners(jobs).stream().map(r -> r.getListener()).filter(NaturalBlocks.class::isInstance).map(NaturalBlocks.class::cast).findFirst().orElseThrow();
        var marker = getDataFolder().toPath().resolve("restart.yml");
        if (Files.exists(marker)) nextPlayer = 100;
        var actor = table(); player = actor.player(); profile = jobs.profile(player);
        if (Files.exists(marker)) {
            var saved = YamlConfiguration.loadConfiguration(marker.toFile());
            var id = UUID.fromString(saved.getString("player")); var restored = jobs.players().get(id);
            check(restored.biomes.contains("minecraft:forest") && restored.biomes.contains("minecraft:plains"), "biome discoveries survive restart");
            var repeatPlayer = player(id, "StoredDiscoverer"); double before = restored.xp("exploring");
            jobs.discoverBiome(repeatPlayer, "minecraft:forest");
            check(restored.xp("exploring") == before, "revisiting a biome after restart does not pay again");
            var wolf = (Wolf) Bukkit.getEntity(UUID.fromString(saved.getString("wolf")));
            check(wolf != null, "the marked animal reloads from the saved world");
            double farming = profile.xp("farming"); Bukkit.getPluginManager().callEvent(new EntityTameEvent(wolf, player));
            check(profile.xp("farming") == farming, "taming history survives chunk saves and server restarts");
            var zombie = (ZombieVillager) Bukkit.getEntity(UUID.fromString(saved.getString("zombie")));
            check(zombie != null, "the reinfected villager reloads with its history");
            cure(zombie, spawn(Villager.class));
            check(profile.xp("healing") == 0, "curing history survives reinfection and server restarts");
            finish(2); return;
        }
        check(jobs.registry().jobs().size() == 17, "new default jobs are present without a separate curing job");
        var jobsFile = jobs.getDataFolder().toPath().resolve("jobs.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(jobsFile);
        config.set("jobs.healing.name", "Healing");
        config.set("jobs.healing.rules", List.of(Map.of("activity", "cure-villager", "entities", List.of("VILLAGER"), "xp", 5, "money", "0.25")));
        config.set("jobs.forest.name", "Forest Discovery");
        config.set("jobs.forest.rules", List.of(Map.of("activity", "biome-discover", "biomes", List.of("minecraft:forest"), "xp", 7, "money", "0")));
        config.save(jobsFile); reload();
        check(jobs.registry().get("forest") != null, "custom biome selectors load without code changes");
        var invalid = new java.io.File(getDataFolder(), "invalid-jobs.yml");
        config.set("jobs.forest.rules", List.of(Map.of("activity", "biome-discover", "biomes", List.of("minecraft:missing_biome"), "xp", 7)));
        config.save(invalid);
        try { JobRegistry.prepare(invalid, Progression.defaults()); throw new AssertionError("unknown biome accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("forest"), "unknown biomes identify the invalid custom job"); }
        config.set("jobs.forest.rules", List.of(Map.of("activity", "smith", "materials", List.of("IRON_CHESTPLATE"), "operations", List.of("invalid_operation"), "xp", 1)));
        config.save(invalid);
        try { JobRegistry.prepare(invalid, Progression.defaults()); throw new AssertionError("unknown operation accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("operation"), "unknown workstation operations are rejected clearly"); }

        var sheep = spawn(Sheep.class);
        var cancelled = new PlayerShearEntityEvent(player, sheep, new ItemStack(Material.SHEARS), EquipmentSlot.HAND, List.of(new ItemStack(Material.WHITE_WOOL, 2)));
        cancelled.setCancelled(true); Bukkit.getPluginManager().callEvent(cancelled);
        check(profile.xp("farming") == 0, "cancelled shearing earns nothing");
        Bukkit.getPluginManager().callEvent(new PlayerShearEntityEvent(player, sheep, new ItemStack(Material.SHEARS), EquipmentSlot.HAND, List.of()));
        check(profile.xp("farming") == 0, "shearing with no drops earns nothing");
        cancelled.setCancelled(false); Bukkit.getPluginManager().callEvent(cancelled);
        check(profile.xp("farming") == 1 && profile.pending.compareTo(new BigDecimal("0.025")) == 0, "shearing pays once per sheep rather than per wool item");
        var wolf = spawn(Wolf.class);
        var tame = new EntityTameEvent(wolf, player); tame.setCancelled(true); Bukkit.getPluginManager().callEvent(tame);
        check(profile.xp("farming") == 1, "cancelled taming does not claim the animal");
        tame.setCancelled(false); Bukkit.getPluginManager().callEvent(tame); Bukkit.getPluginManager().callEvent(tame);
        check(profile.xp("farming") == 6, "an animal gives its taming reward only once");

        var hive = world.getBlockAt(151, 20, 151); hive.setType(Material.BEEHIVE, false);
        var honey = (Beehive) hive.getBlockData(); honey.setHoneyLevel(honey.getMaximumHoneyLevel()); hive.setBlockData(honey, false);
        useHive(actor, hive, Material.GLASS_BOTTLE);
        check(profile.xp("farming") == 8 && ((Beehive) hive.getBlockData()).getHoneyLevel() == 0,
                "a real bottle harvest gives one farming reward and drains the hive");
        useHive(actor, hive, Material.GLASS_BOTTLE);
        check(profile.xp("farming") == 8, "using a bottle on an empty hive cannot pay again");
        hive.setBlockData(honey, false); useHive(actor, hive, Material.SHEARS);
        check(profile.xp("farming") == 10, "real manual honeycomb harvesting also pays once");

        var dig = excavation(152); brush(actor, dig, 9);
        check(profile.xp("archaeology") == 0, "individual brush strokes give no reward");
        brush(actor, dig, 10);
        check(profile.xp("archaeology") == 5 && dig.getType() == Material.SAND, "a real completed excavation rewards archaeology once");
        check(!history.eligible(dig), "excavated sand cannot receive a second natural-block reward");
        var placed = excavation(153); history.markPlaced(placed); brush(actor, placed, 10);
        check(profile.xp("archaeology") == 5, "player-placed suspicious blocks do not pay");
        Listener protection = new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST) public void deny(BlockDropItemEvent event) { event.setCancelled(true); }
        };
        Bukkit.getPluginManager().registerEvents(protection, this);
        try { brush(actor, excavation(154), 10); } finally { HandlerList.unregisterAll(protection); }
        check(profile.xp("archaeology") == 5, "cancelled excavation drops give no reward");

        var zombie = spawn(ZombieVillager.class); var villager = spawn(Villager.class);
        var cancelledCure = new EntityTransformEvent(zombie, List.of(villager), EntityTransformEvent.TransformReason.CURED);
        cancelledCure.setCancelled(true); Bukkit.getPluginManager().callEvent(cancelledCure);
        check(profile.xp("healing") == 0, "cancelled cures do not pay or claim their villager");
        cure(zombie, villager);
        check(profile.xp("healing") == 5, "a custom curing job credits the curing player");
        var reinfected = spawn(ZombieVillager.class);
        Bukkit.getPluginManager().callEvent(new EntityTransformEvent(villager, List.of(reinfected), EntityTransformEvent.TransformReason.INFECTION));
        cure(reinfected, spawn(Villager.class));
        check(profile.xp("healing") == 5, "reinfection cannot reset the curing reward");

        world.setBiome(156, 20, 156, org.bukkit.block.Biome.FOREST);
        var from = new Location(world, 155, 20, 156); var to = new Location(world, 156, 20, 156);
        var teleport = new PlayerTeleportEvent(player, from, to); Bukkit.getPluginManager().callEvent(teleport);
        check(!profile.biomes.contains("minecraft:forest"), "teleport events do not discover a biome");
        var move = new PlayerMoveEvent(player, from, to); move.setCancelled(true); Bukkit.getPluginManager().callEvent(move);
        check(!profile.biomes.contains("minecraft:forest"), "cancelled movement does not discover a biome");
        move.setCancelled(false); Bukkit.getPluginManager().callEvent(move);
        check(profile.biomes.contains("minecraft:forest") && profile.xp("exploring") == 10, "entering a biome gives the discovery bonus");
        check(profile.xp("forest") == 7, "a custom biome job rewards its selected biome");
        Bukkit.getPluginManager().callEvent(move);
        check(profile.xp("exploring") == 10, "moving back and forth does not repeat a discovery");
        jobs.discoverBiome(player, "minecraft:plains");
        check(profile.xp("exploring") == 20 && profile.xp("forest") == 7, "a different biome earns a separate bonus and respects custom selectors");
        var another = table().player(); jobs.discoverBiome(another, "minecraft:forest");
        check(jobs.profile(another).xp("exploring") == 10, "discoveries are tracked separately per player");
        player.setGameMode(GameMode.CREATIVE); jobs.discoverBiome(player, "minecraft:desert");
        check(!profile.biomes.contains("minecraft:desert"), "creative movement cannot claim biome rewards"); player.setGameMode(GameMode.SURVIVAL);
        jobs.settings().set("rules.excluded-worlds", List.of(world.getName())); jobs.discoverBiome(player, "minecraft:desert");
        check(!profile.biomes.contains("minecraft:desert"), "excluded worlds cannot claim biome rewards"); jobs.settings().set("rules.excluded-worlds", List.of());
        jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS); world.save();
        var saved = new YamlConfiguration(); saved.set("player", player.getUniqueId().toString());
        saved.set("wolf", wolf.getUniqueId().toString()); saved.set("zombie", reinfected.getUniqueId().toString());
        Files.createDirectories(getDataFolder().toPath()); saved.save(marker.toFile());
        finish(1);
    }
    <T extends LivingEntity> T spawn(Class<T> type) {
        var entity = world.spawn(new Location(world, 150.5, 20, 150.5), type);
        entity.setAI(false); entity.setGravity(false); entity.setInvulnerable(true); return entity;
    }
    void cure(ZombieVillager zombie, Villager villager) {
        OfflinePlayer owner = (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class},
                (proxy, method, args) -> method.getName().equals("getPlayer") ? player : empty(method.getReturnType()));
        var source = (ZombieVillager) Proxy.newProxyInstance(ZombieVillager.class.getClassLoader(), new Class<?>[]{ZombieVillager.class},
                (proxy, method, args) -> method.getName().equals("getConversionPlayer") ? owner : method.invoke(zombie, args));
        Bukkit.getPluginManager().callEvent(new EntityTransformEvent(source, List.of(villager), EntityTransformEvent.TransformReason.CURED));
    }
    org.bukkit.block.Block excavation(int x) {
        var block = world.getBlockAt(x, 20, 151); block.setType(Material.SUSPICIOUS_SAND, false);
        var state = (BrushableBlock) block.getState(); state.setLootTable(LootTables.DESERT_PYRAMID_ARCHAEOLOGY.getLootTable()); state.setSeed(1); state.update(true, false);
        return block;
    }
    void brush(Table actor, org.bukkit.block.Block block, int strokes) throws Exception {
        var level = invoke(world, "getHandle"); var pos = construct("net.minecraft.core.BlockPos", block.getX(), block.getY(), block.getZ());
        var entity = invoke(level, "getBlockEntity", pos);
        for (int i = 1; i <= strokes && block.getType() == Material.SUSPICIOUS_SAND; i++)
            invoke(entity, "brush", (long) (i * 20), level, actor.handle(), enumValue("net.minecraft.core.Direction", "NORTH"), nms(new ItemStack(Material.BRUSH)));
    }
    void useHive(Table actor, org.bukkit.block.Block block, Material item) throws Exception {
        actor.player().getInventory().setItemInMainHand(new ItemStack(item));
        var level = invoke(world, "getHandle"); var pos = construct("net.minecraft.core.BlockPos", block.getX(), block.getY(), block.getZ());
        var state = invoke(level, "getBlockState", pos);
        var hit = construct("net.minecraft.world.phys.BlockHitResult", construct("net.minecraft.world.phys.Vec3", block.getX() + .5, block.getY() + .5, block.getZ() + .5), enumValue("net.minecraft.core.Direction", "UP"), pos, false);
        invoke(state, "useItemOn", invoke(actor.handle(), "getMainHandItem"), level, actor.handle(), enumValue("net.minecraft.world.InteractionHand", "MAIN_HAND"), hit);
    }
    void reload() { jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"}); }
    void finish(int phase) { getLogger().info("RELEASE CHECKS PHASE " + phase + " PASS: " + checks + " additional activity checks"); Bukkit.shutdown(); }
}
