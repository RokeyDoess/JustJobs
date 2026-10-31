package checks;

import io.github.rokeydoess.justjobs.*;
import io.papermc.paper.event.player.PlayerTradeEvent;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.HandlerList;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Run only in a disposable server using scripts/check-server.ps1. */
public class ReleaseChecks extends JavaPlugin implements Listener {
    static final UUID MAIN = UUID.fromString("50db0c41-f933-47e1-979e-2ed04037fe59");
    static final UUID RETRY = UUID.fromString("e370c614-4888-4b53-9c0a-451959360ae1");
    static final UUID UNCERTAIN = UUID.fromString("ac3fcf85-4586-4656-9564-c54de7dfb52b");
    static final UUID CRASH = UUID.fromString("d83c045b-9d80-4560-ac1d-0ed5937ceac8");
    final Map<UUID, BigDecimal> balances = new HashMap<>();
    final Set<UUID> rejected = new HashSet<>();
    final Set<UUID> throwAfter = new HashSet<>();
    final Map<UUID, List<String>> messages = new HashMap<>();
    final Map<UUID, List<net.kyori.adventure.text.Component>> components = new HashMap<>();
    final Map<UUID, Integer> barsShown = new HashMap<>();
    static final UUID WALK = UUID.fromString("90346abf-b819-470e-bd5c-99401ba42be1");
    JustJobsPlugin jobs;
    NaturalBlocks history;
    ActivityListener activities;
    Economy provider;
    World world;
    ExecutorService worker;
    DataStore store;
    Player player;
    PlayerProfile profile;
    Inventory current;
    int checks;
    int closedMenus;
    int base;
    long loadStart;
    final List<Long> loadTimes = new ArrayList<>();
    final List<Player> loadPlayers = new ArrayList<>();
    final List<Double> fullTicks = new ArrayList<>();
    boolean sampling;
    final int loadTicks = Math.max(200, Integer.getInteger("justjobs.check.load-ticks", 200));

    @Override public void onEnable() { Bukkit.getPluginManager().registerEvents(this, this); later(40, this::start); }
    @EventHandler public void tickEnd(ServerTickEndEvent event) { if (sampling) fullTicks.add(event.getTickDuration()); }
    void later(long ticks, Checked action) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { action.run(); }
            catch (Throwable e) {
                getLogger().log(java.util.logging.Level.SEVERE, "RELEASE CHECKS FAILED", e);
                Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 1);
            }
        }, ticks);
    }
    interface Checked { void run() throws Exception; }
    void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++; getLogger().info("PASS: " + label);
    }
    static Object field(Object instance, String name) throws Exception {
        var f = instance.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(instance);
    }
    static Object empty(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0f;
        if (type == long.class) return 0L;
        return 0;
    }
    Player player(UUID id, String name) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> name;
            case "hasPermission", "isOnline", "isOnGround" -> true;
            case "getGameMode" -> GameMode.SURVIVAL;
            case "getWorld" -> world;
            case "getLocation" -> new Location(world, base, 200, 2048);
            case "sendMessage" -> {
                if (args != null && args.length > 0 && args[0] instanceof net.kyori.adventure.text.Component component)
                    components.computeIfAbsent(id, ignored -> new ArrayList<>()).add(component);
                if (args != null && args.length > 0) messages.computeIfAbsent(id, ignored -> new ArrayList<>()).add(
                        args[0] instanceof net.kyori.adventure.text.TextComponent text ? text.content() : String.valueOf(args[0]));
                yield null;
            }
            case "showBossBar" -> { barsShown.merge(id, 1, Integer::sum); yield null; }
            case "openInventory" -> { current = (Inventory) args[0]; yield null; }
            case "closeInventory" -> { closedMenus++; yield null; }
            case "getOpenInventory" -> view();
            case "hashCode" -> id.hashCode();
            case "equals" -> proxy == args[0];
            case "toString" -> name;
            default -> empty(method.getReturnType());
        });
    }
    InventoryView view() {
        return (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(), new Class<?>[]{InventoryView.class}, (p, method, args) -> switch (method.getName()) {
            case "getTopInventory" -> current;
            case "getBottomInventory" -> Bukkit.createInventory(null, 36);
            case "getPlayer" -> player;
            case "getType" -> InventoryType.CHEST;
            case "getTitle", "getOriginalTitle" -> "Checks";
            case "countSlots" -> 90;
            default -> empty(method.getReturnType());
        });
    }
    void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "JustJobs starts on Paper");
        immediatePayments();
        world = Bukkit.getWorlds().getFirst();
        base = 32768;
        world.getChunkAt(base >> 4, 2048 >> 4).addPluginChunkTicket(this);
        world.getChunkAt((base + 16) >> 4, 2048 >> 4).addPluginChunkTicket(this);
        player = player(MAIN, "ReleaseChecks");
        profile = jobs.profile(player);
        worker = (ExecutorService) field(jobs, "worker"); store = (DataStore) field(jobs, "store");
        history = HandlerList.getRegisteredListeners(jobs).stream().map(r -> r.getListener()).filter(NaturalBlocks.class::isInstance).map(NaturalBlocks.class::cast).findFirst().orElseThrow();
        activities = HandlerList.getRegisteredListeners(jobs).stream().map(r -> r.getListener()).filter(ActivityListener.class::isInstance).map(ActivityListener.class::cast).findFirst().orElseThrow();
        installProvider();
        if (Files.exists(getDataFolder().toPath().resolve("restart"))) {
            restartChecks(); return;
        }
        eventChecks();
        protectionChecks();
        newJobChecks();
        reloadChecks();
        spawnerReloadChecks();
        presentationChecks();
        travelChecks();
        menuChecks();
        paymentChecks();
        later(50, this::afterPayments);
    }
    void installProvider() {
        provider = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[]{Economy.class}, (p, method, args) -> switch (method.getName()) {
            case "getName" -> "TestEconomy";
            case "isEnabled", "hasAccount", "createPlayerAccount" -> true;
            case "fractionalDigits" -> 2;
            case "getBalance" -> balances.getOrDefault(((OfflinePlayer) args[0]).getUniqueId(), BigDecimal.ZERO).doubleValue();
            case "depositPlayer" -> {
                UUID id = ((OfflinePlayer) args[0]).getUniqueId();
                BigDecimal amount = BigDecimal.valueOf((double) args[1]);
                if (rejected.contains(id)) yield new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Test rejection");
                balances.merge(id, amount, BigDecimal::add);
                if (throwAfter.contains(id)) throw new IllegalStateException("Test exception after credit");
                yield new EconomyResponse(amount.doubleValue(), balances.get(id).doubleValue(), EconomyResponse.ResponseType.SUCCESS, "");
            }
            default -> empty(method.getReturnType());
        });
        Bukkit.getServicesManager().register(Economy.class, provider, this, ServicePriority.Highest);
    }
    void immediatePayments() throws Exception {
        var file = jobs.getDataFolder().toPath().resolve("config.yml").toFile();
        var config = YamlConfiguration.loadConfiguration(file); config.set("economy.payday-enabled", false); config.save(file);
        jobs.onCommand(Bukkit.getConsoleSender(), jobs.getCommand("jobs"), "jobs", new String[]{"reload"});
    }
    org.bukkit.block.Block block(int offset) { return world.getBlockAt(base + offset, 200, 2048); }
    void protectionChecks() {
        Listener protection = new Listener() {
            @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
            public void breakBlock(BlockBreakEvent event) { event.setCancelled(true); }
            @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
            public void placeBlock(BlockPlaceEvent event) { event.setCancelled(true); }
        };
        Bukkit.getPluginManager().registerEvents(protection, this);
        var dirt = block(90); dirt.setType(Material.DIRT, false);
        var component = block(91); component.setType(Material.PISTON, false);
        double digging = profile.xp("digging"), engineering = profile.xp("engineering");
        try {
            Bukkit.getPluginManager().callEvent(new BlockBreakEvent(dirt, player));
            Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(component, component.getState(), component.getRelative(org.bukkit.block.BlockFace.DOWN),
                    new ItemStack(Material.PISTON), player, true, EquipmentSlot.HAND));
            check(profile.xp("digging") == digging && history.eligible(dirt), "another listener can deny a break without rewards or changed natural eligibility");
            check(profile.xp("engineering") == engineering && !history.placementRewarded(component) && !history.playerPlaced(component),
                    "another listener can deny placement without rewards or consuming its placement allowance");
        } finally { HandlerList.unregisterAll(protection); }
    }
    Zombie huntingMob(boolean spawner) {
        var data = world.getPersistentDataContainer().getAdapterContext().newPersistentDataContainer();
        return (Zombie) Proxy.newProxyInstance(Zombie.class.getClassLoader(), new Class<?>[]{Zombie.class}, (p, method, args) -> switch (method.getName()) {
            case "getKiller" -> player;
            case "getType" -> EntityType.ZOMBIE;
            case "fromMobSpawner" -> spawner;
            case "getPersistentDataContainer" -> data;
            default -> empty(method.getReturnType());
        });
    }
    void killMob(Zombie mob) {
        Bukkit.getPluginManager().callEvent(new EntityDeathEvent(mob, DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>()));
    }
    void spawnerReloadChecks() throws Exception {
        var file = new java.io.File(jobs.getDataFolder(), "config.yml");
        var config = YamlConfiguration.loadConfiguration(file);
        double before = profile.xp("hunting");
        var money = profile.earnings.total("hunting", System.currentTimeMillis());
        config.set("rules.reward-spawner-mobs", true); config.save(file);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "jobs reload");
        killMob(huntingMob(true));
        check(profile.xp("hunting") == before + 5 && profile.earnings.total("hunting", System.currentTimeMillis()).subtract(money).compareTo(new BigDecimal("0.05")) == 0,
                "reload can enable spawner XP and money without migration resetting the choice");
        config.set("rules.reward-spawner-mobs", false); config.save(file);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "jobs reload");
        killMob(huntingMob(true));
        check(profile.xp("hunting") == before + 5 && profile.earnings.total("hunting", System.currentTimeMillis()).subtract(money).compareTo(new BigDecimal("0.05")) == 0,
                "reload can disable spawner XP and money immediately");
    }
    void eventChecks() throws Exception {
        check(jobs.registry().jobs().size() == 17, "seventeen default jobs load");
        var dirt = block(0); dirt.setType(Material.DIRT, false);
        var cancelledBreak = new BlockBreakEvent(dirt, player); cancelledBreak.setCancelled(true);
        Bukkit.getPluginManager().callEvent(cancelledBreak);
        check(profile.xp("digging") == 0 && history.eligible(dirt), "cancelled block breaks award nothing and preserve natural eligibility");
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(dirt, player));
        check(profile.xp("digging") == 1, "natural dirt rewards once");
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(dirt, player));
        check(profile.xp("digging") == 1, "repeated break at a consumed position does not reward");
        var log = block(1); log.setType(Material.OAK_LOG, false);
        Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(log, log.getState(), dirt, new ItemStack(Material.OAK_LOG), player, true, EquipmentSlot.HAND));
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(log, player));
        check(profile.xp("treefelling") == 0, "hand-placed logs never reward");
        Bukkit.getPluginManager().callEvent(new StructureGrowEvent(log.getLocation(), TreeType.TREE, true, player, List.of(log.getState())));
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(log, player));
        check(profile.xp("treefelling") == 2, "player-grown logs reward");
        history.flush();
        check(!new NaturalBlocks(jobs).eligible(dirt), "block history survives a flush and reload");
        check(!jobs.settings().getBoolean("rules.reward-spawner-mobs"), "spawner rewards are disabled by default");
        Zombie mob = huntingMob(true);
        var death = new EntityDeathEvent(mob, DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>());
        death.setCancelled(true); Bukkit.getPluginManager().callEvent(death);
        check(profile.xp("hunting") == 0, "cancelled deaths never reward");
        death.setCancelled(false); Bukkit.getPluginManager().callEvent(death);
        check(profile.xp("hunting") == 0 && profile.earnings.total("hunting", System.currentTimeMillis()).signum() == 0,
                "spawner kills give neither XP nor money by default");
        killMob(huntingMob(false));
        check(profile.xp("hunting") == 5 && profile.earnings.total("hunting", System.currentTimeMillis()).compareTo(new BigDecimal("0.05")) == 0,
                "natural hostile mobs still earn Hunting XP and money");
        for (var reason : List.of(CreatureSpawnEvent.SpawnReason.SPAWNER, CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER)) {
            var spawned = huntingMob(false);
            Bukkit.getPluginManager().callEvent(new CreatureSpawnEvent(spawned, reason));
            var restored = huntingMob(false);
            restored.getPersistentDataContainer().readFromBytes(spawned.getPersistentDataContainer().serializeToBytes());
            killMob(restored);
            var transformed = huntingMob(false);
            Bukkit.getPluginManager().callEvent(new EntityTransformEvent(restored, List.of(transformed), EntityTransformEvent.TransformReason.DROWNED));
            killMob(transformed);
            check(profile.xp("hunting") == 5 && profile.earnings.total("hunting", System.currentTimeMillis()).compareTo(new BigDecimal("0.05")) == 0,
                    reason + " provenance survives saved entity data and transformations");
        }
        current = Bukkit.createInventory(null, 9);
        for (int button = 0; button < 3; button++) {
            var event = new EnchantItemEvent(player, view(), block(2), new ItemStack(Material.DIAMOND_PICKAXE), button == 2 ? 30 : 1,
                    new HashMap<>(Map.of(Enchantment.UNBREAKING, 1)), Enchantment.UNBREAKING, 1, button);
            if (button == 0) {
                event.setCancelled(true); Bukkit.getPluginManager().callEvent(event);
                check(profile.xp("enchanting") == 0, "cancelled enchants never reward"); event.setCancelled(false);
            }
            double before = profile.xp("enchanting");
            BigDecimal earned = profile.earnings.total("enchanting", System.currentTimeMillis());
            Bukkit.getPluginManager().callEvent(event);
            check(profile.xp("enchanting") - before == (button + 1) * 10
                    && profile.earnings.total("enchanting", System.currentTimeMillis()).subtract(earned).compareTo(new BigDecimal("0.35").multiply(BigDecimal.valueOf(button + 1))) == 0,
                    "enchant button " + (button + 1) + " pays for " + (button + 1) + " levels spent");
        }
        var recipe = new MerchantRecipe(new ItemStack(Material.BREAD), 100);
        recipe.addIngredient(new ItemStack(Material.EMERALD, 4));
        recipe.setSpecialPrice(-2);
        AbstractVillager villager = (AbstractVillager) Proxy.newProxyInstance(AbstractVillager.class.getClassLoader(), new Class<?>[]{AbstractVillager.class}, (p, m, a) -> empty(m.getReturnType()));
        var trade = new PlayerTradeEvent(player, villager, recipe, true, true);
        trade.setCancelled(true); Bukkit.getPluginManager().callEvent(trade);
        check(profile.xp("trading") == 0, "cancelled trades never reward");
        trade.setCancelled(false);
        for (int i = 0; i < 8; i++) Bukkit.getPluginManager().callEvent(trade);
        check(profile.xp("trading") == 16, "repeated trades use the discounted emerald price");
        check(jobs.pvpAllowed(MAIN, RETRY) && !jobs.pvpAllowed(RETRY, MAIN), "PvP pair cooldown works in both directions");
        jobs.award(player, JobRegistry.Activity.BLOCK_BREAK, Material.STONE, null, 20_000);
        check(profile.earnings.total("mining", System.currentTimeMillis()).compareTo(new BigDecimal("25")) == 0, "money is clamped at 25 per job");
        double xp = profile.xp("mining");
        jobs.award(player, JobRegistry.Activity.BLOCK_BREAK, Material.STONE, null, 1);
        check(profile.xp("mining") == xp + 1 && profile.earnings.total("mining", System.currentTimeMillis()).compareTo(new BigDecimal("25")) == 0,
                "XP continues after the money cap without banking excess money");
    }
    void newJobChecks() {
        var builder = player(UUID.fromString("3cd13e8e-9b83-48da-946c-29d75ac03448"), "NewJobChecks");
        var progress = jobs.profile(builder);
        var wire = block(3); wire.setType(Material.REDSTONE_WIRE, false);
        var placement = new BlockPlaceEvent(wire, wire.getState(), block(0), new ItemStack(Material.REDSTONE), builder, true, EquipmentSlot.HAND);
        placement.setCancelled(true); Bukkit.getPluginManager().callEvent(placement);
        check(progress.xp("engineering") == 0 && !history.placementRewarded(wire), "cancelled engineering placement neither pays nor claims its position");
        placement.setCancelled(false); Bukkit.getPluginManager().callEvent(placement);
        Bukkit.getPluginManager().callEvent(placement);
        check(progress.xp("engineering") == 0 && progress.xp("building") == 0, "placing or replaying a placement no longer rewards either default job");
        wire.setType(Material.BRICKS, false);
        Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(wire, wire.getState(), block(0), new ItemStack(Material.BRICKS), builder, true, EquipmentSlot.HAND));
        check(progress.xp("building") == 0, "changing component type or placement job cannot reuse a claimed location");
        var other = player(UUID.fromString("86de063b-3512-4b8c-a026-3d829276121e"), "OtherBuilder");
        wire.setType(Material.PISTON, false);
        Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(wire, wire.getState(), block(0), new ItemStack(Material.PISTON), other, true, EquipmentSlot.HAND));
        check(jobs.profile(other).xp("engineering") == 0, "placement claims are shared across players");
        var bricks = block(4); bricks.setType(Material.BRICKS, false);
        Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(bricks, bricks.getState(), block(0), new ItemStack(Material.BRICKS), builder, true, EquipmentSlot.HAND));
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(bricks, builder));
        check(progress.xp("building") == 0 && progress.xp("mining") == 0, "placing and breaking building blocks earns no money or XP");
        bricks.setType(Material.OBSERVER, false);
        Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(bricks, bricks.getState(), block(0), new ItemStack(Material.OBSERVER), builder, true, EquipmentSlot.HAND));
        check(progress.xp("engineering") == 0, "swapping block types cannot create crafting rewards");
        Player creative = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, method, args) ->
                method.getName().equals("getGameMode") ? GameMode.CREATIVE : method.invoke(builder, args));
        var piston = block(5); piston.setType(Material.PISTON, false);
        Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(piston, piston.getState(), block(0), new ItemStack(Material.PISTON), creative, true, EquipmentSlot.HAND));
        check(!history.placementRewarded(piston), "creative placements do not earn or consume a reward claim");
        Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(piston, piston.getState(), block(0), new ItemStack(Material.PISTON), builder, false, EquipmentSlot.HAND));
        check(!history.placementRewarded(piston), "placements with building denied do not consume a reward claim");
        Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(piston, piston.getState(), block(0), new ItemStack(Material.PISTON), builder, true, EquipmentSlot.HAND));
        check(progress.xp("engineering") == 0 && progress.earnings.total("engineering", System.currentTimeMillis()).signum() == 0,
                "fresh components at fresh positions earn no placement rewards");
        var unused = block(10); unused.setType(Material.DIRT, false);
        Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(unused, unused.getState(), block(0), new ItemStack(Material.DIRT), builder, true, EquipmentSlot.HAND));
        check(!history.placementRewarded(unused), "placing an unrewarded material does not use the location's placement allowance");
        var lower = block(6); var upper = lower.getRelative(org.bukkit.block.BlockFace.UP);
        lower.setType(Material.AIR, false); upper.setType(Material.AIR, false);
        var before = List.of(lower.getState(), upper.getState());
        lower.setType(Material.OAK_DOOR, false); upper.setType(Material.OAK_DOOR, false);
        Bukkit.getPluginManager().callEvent(new BlockMultiPlaceEvent(before, block(0), new ItemStack(Material.OAK_DOOR), builder, true, EquipmentSlot.HAND));
        check(progress.xp("building") == 0 && !history.placementRewarded(lower) && !history.placementRewarded(upper), "multi-block placements earn no rewards or placement claims");
        var crop = block(8); crop.setType(Material.WHEAT, false);
        var age = (org.bukkit.block.data.Ageable) crop.getBlockData(); age.setAge(age.getMaximumAge() - 1); crop.setBlockData(age, false);
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(crop, builder));
        check(progress.xp("farming") == 0, "immature crops do not earn Farming XP");
        age.setAge(age.getMaximumAge()); crop.setBlockData(age, false);
        var harvest = new BlockBreakEvent(crop, builder); harvest.setCancelled(true); Bukkit.getPluginManager().callEvent(harvest);
        check(progress.xp("farming") == 0, "cancelled mature crop harvests do not reward");
        harvest.setCancelled(false); Bukkit.getPluginManager().callEvent(harvest);
        // A later crop can mature at the same farm position, even though it is player-planted.
        history.markPlaced(crop); crop.setBlockData(age, false);
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(crop, builder));
        check(progress.xp("farming") == 2, "mature replanted crops reward on later harvests at the same position");
        var berry = block(9); berry.setType(Material.SWEET_BERRY_BUSH, false);
        var berries = new PlayerHarvestBlockEvent(builder, berry, EquipmentSlot.HAND, List.of(new ItemStack(Material.SWEET_BERRIES, 3)));
        berries.setCancelled(true); Bukkit.getPluginManager().callEvent(berries);
        check(progress.xp("farming") == 2, "cancelled berry harvests do not reward");
        berries.setCancelled(false); Bukkit.getPluginManager().callEvent(berries);
        Bukkit.getPluginManager().callEvent(new PlayerHarvestBlockEvent(builder, berry, EquipmentSlot.HAND, List.of()));
        check(progress.xp("farming") == 3, "berry harvests pay once per harvest, not per berry, and empty harvests pay nothing");
        Cow cow = (Cow) Proxy.newProxyInstance(Cow.class.getClassLoader(), new Class<?>[]{Cow.class}, (p, method, args) ->
                method.getName().equals("getType") ? EntityType.COW : empty(method.getReturnType()));
        var breeding = new EntityBreedEvent(cow, cow, cow, builder, new ItemStack(Material.WHEAT), 1);
        breeding.setCancelled(true); Bukkit.getPluginManager().callEvent(breeding);
        Bukkit.getPluginManager().callEvent(new EntityBreedEvent(cow, cow, cow, null, null, 1));
        check(progress.xp("farming") == 3, "cancelled breeding and breeding without a credited player do not reward");
        breeding.setCancelled(false); Bukkit.getPluginManager().callEvent(breeding);
        check(progress.xp("farming") == 8 && progress.earnings.total("farming", System.currentTimeMillis()).compareTo(new BigDecimal("0.56")) == 0,
                "animal breeding rewards the player credited by Paper");
        Item caught = (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[]{Item.class}, (p, method, args) ->
                method.getName().equals("getItemStack") ? new ItemStack(Material.COD) : empty(method.getReturnType()));
        FishHook hook = (FishHook) Proxy.newProxyInstance(FishHook.class.getClassLoader(), new Class<?>[]{FishHook.class}, (p, method, args) -> empty(method.getReturnType()));
        var fishing = new PlayerFishEvent(builder, caught, hook, EquipmentSlot.HAND, PlayerFishEvent.State.CAUGHT_FISH);
        fishing.setCancelled(true); Bukkit.getPluginManager().callEvent(fishing);
        Bukkit.getPluginManager().callEvent(new PlayerFishEvent(builder, null, hook, EquipmentSlot.HAND, PlayerFishEvent.State.FAILED_ATTEMPT));
        Bukkit.getPluginManager().callEvent(new PlayerFishEvent(builder, caught, hook, EquipmentSlot.HAND, PlayerFishEvent.State.CAUGHT_ENTITY));
        check(progress.xp("fishing") == 0, "cancelled catches, failed attempts, and hooked entities do not reward Fishing");
        fishing.setCancelled(false); Bukkit.getPluginManager().callEvent(fishing);
        check(progress.xp("fishing") == 5 && progress.earnings.total("fishing", System.currentTimeMillis()).compareTo(new BigDecimal("0.25")) == 0,
                "a successful fishing catch earns its configured XP and payment");
        history.flush();
        check(!new NaturalBlocks(jobs).placementRewarded(wire), "default jobs no longer add placement-claim data");
        var engineering = jobs.registry().get("engineering").rules().stream().flatMap(r -> r.materials().stream()).collect(java.util.stream.Collectors.toSet());
        var building = jobs.registry().get("building").rules().stream().flatMap(r -> r.materials().stream()).collect(java.util.stream.Collectors.toSet());
        check(Collections.disjoint(engineering, building), "default Building and Engineering material lists never overlap");
    }
    void reloadChecks() throws Exception {
        var file = new java.io.File(jobs.getDataFolder(), "config.yml");
        var yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("storage.autosave-seconds", 1); yaml.set("economy.payout-interval-seconds", 1); yaml.set("rules.travel-sample-ticks", 2);
        yaml.setComments("feedback.payday-chat", List.of());
        yaml.setComments("storage.autosave-seconds", List.of("Save changed progress frequently on this server."));
        var previous = List.copyOf((List<BukkitTask>) field(jobs, "scheduled"));
        yaml.save(file); Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "jobs reload");
        check(previous.stream().allMatch(BukkitTask::isCancelled), "reload cancels every previous repeating task");
        var replacement = (List<BukkitTask>) field(jobs, "scheduled");
        check(replacement.size() == 6 && replacement.stream().noneMatch(BukkitTask::isCancelled), "reload replaces all schedules without duplicates");
        check(jobs.settings().getLong("rules.travel-sample-ticks") == 2, "new interval settings apply without a restart");
        var documented = YamlConfiguration.loadConfiguration(file);
        check(documented.getComments("feedback.payday-chat").stream().anyMatch(comment -> comment.contains("Allow payday messages")),
                "reload adds missing config explanations to an existing file");
        check(documented.getComments("storage.autosave-seconds").equals(List.of("Save changed progress frequently on this server."))
                        && documented.getInt("storage.autosave-seconds") == 1,
                "config documentation preserves custom comments and values");
        var original = jobs.settings();
        yaml.set("feedback.boss-bar", "broken"); yaml.save(file);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "jobs reload");
        check(jobs.settings() == original && replacement.stream().noneMatch(BukkitTask::isCancelled), "invalid reload retains settings and running schedules");
        yaml.set("feedback.boss-bar", true); yaml.set("economy.hourly-income-limit-per-job", null); yaml.save(file);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "jobs reload");
        check(YamlConfiguration.loadConfiguration(file).getString("economy.hourly-income-limit-per-job").equals("25"), "missing shared settings are filled without replacing owner values");
        var temp = new java.io.File(getDataFolder(), "invalid-job.yml"); temp.getParentFile().mkdirs();
        var jobFile = YamlConfiguration.loadConfiguration(new java.io.File(jobs.getDataFolder(), "jobs.yml"));
        jobFile.set("jobs.digging.rules", List.of(Map.of("activity", "block-break", "materials", List.of("DIRT"), "entities", List.of("*"), "xp", 1)));
        jobFile.save(temp);
        try { JobRegistry.load(temp, Progression.defaults()); throw new AssertionError("wrong selector accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("digging"), "wrong selectors cannot silently broaden a block reward"); }
        jobFile = YamlConfiguration.loadConfiguration(new java.io.File(jobs.getDataFolder(), "jobs.yml"));
        jobFile.set("jobs.digging.progression.xp-multiplier", 2); jobFile.save(temp);
        var scaled = JobRegistry.load(temp, Progression.defaults());
        check(scaled.get("digging").progression().required(50) == 2000 && scaled.get("mining").progression().required(50) == 1500,
                "per-job XP multiplier changes only the selected job");
        jobFile.set("jobs.fishing.rules", List.of(Map.of("activity", "fish", "materials", List.of("COD"), "xp", 2, "money", ".10")));
        jobFile.save(temp);
        var selectedCatch = JobRegistry.load(temp, Progression.defaults()).get("fishing").rules().getFirst();
        check(selectedCatch.matches(JobRegistry.Activity.FISH, Material.COD, null) && !selectedCatch.matches(JobRegistry.Activity.FISH, Material.SALMON, null),
                "custom fishing rules filter catches by item material");
        jobFile.set("jobs.fishing.rules", List.of(Map.of("activity", "fish", "entities", List.of("*"), "materials", List.of("COD"), "xp", 2)));
        jobFile.save(temp);
        try { JobRegistry.load(temp, Progression.defaults()); throw new AssertionError("fish entity selector accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("fishing"), "invalid fishing selectors are rejected with the job ID"); }
    }
    List<String> lore(int slot) {
        return current.getItem(slot).getItemMeta().lore().stream().map(line -> ((net.kyori.adventure.text.TextComponent) line).content()).toList();
    }
    @SuppressWarnings("unchecked") int jobSlot(String id) {
        try {
            var slots = (Map<Integer, String>) field(current.getHolder(), "jobSlots");
            return slots.entrySet().stream().filter(entry -> entry.getValue().equals(id)).findFirst().orElseThrow().getKey();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    void presentationChecks() throws Exception {
        var settingsFile = new java.io.File(jobs.getDataFolder(), "config.yml");
        var jobsFile = new java.io.File(jobs.getDataFolder(), "jobs.yml");
        String savedSettings = Files.readString(settingsFile.toPath()), savedJobs = Files.readString(jobsFile.toPath());
        try {
            var settings = YamlConfiguration.loadConfiguration(settingsFile); settings.set("economy.currency-symbol", "¤"); settings.save(settingsFile);
            var configuredJobs = YamlConfiguration.loadConfiguration(jobsFile);
            var rules = configuredJobs.getMapList("jobs.engineering.rules").stream().map(raw -> {
                var rule = new LinkedHashMap<String, Object>(); raw.forEach((key, value) -> rule.put(key.toString(), value)); return rule;
            }).toList();
            rules.getFirst().put("description", "custom wiring");
            rules.getLast().remove("description"); rules.getLast().put("materials", List.of("PISTON"));
            configuredJobs.set("jobs.engineering.rules", rules); configuredJobs.save(jobsFile);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "jobs reload");
            var menus = new JobsMenu(jobs); menus.open(player, false, 0, null);
            check(lore(jobSlot("engineering")).stream().anyMatch(line -> line.contains("¤0.02 / custom wiring")), "custom currency symbol and rule description apply on reload");
            check(lore(jobSlot("engineering")).stream().anyMatch(line -> line.contains("¤0.15 / piston")), "a custom rule without a description names its selected material");
            check(lore(19).stream().anyMatch(line -> line.contains("¤0.0085 / soil or sand block")), "small reward rates keep their exact decimal value in the menu");
            check(lore(4).stream().anyMatch(line -> line.startsWith("Unpaid earnings: ") && line.contains("¤"))
                    && lore(20).stream().anyMatch(line -> line.startsWith("Earned this hour: ¤")), "balances and hourly limits use the configured currency symbol");
            check(jobs.currency(new BigDecimal("0.005")).equals("<¤0.01"), "sub-cent saved balances display their threshold with the configured symbol");
            var active = jobs.settings(); settings.set("economy.currency-symbol", 12); settings.save(settingsFile);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "jobs reload");
            check(jobs.settings() == active, "a malformed currency symbol rejects reload without replacing active settings");
        } finally {
            Files.writeString(settingsFile.toPath(), savedSettings); Files.writeString(jobsFile.toPath(), savedJobs);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "jobs reload");
        }
    }
    void menuChecks() {
        check(profile.bossBar, "boss bar is on for a new player");
        jobs.toggleBossBar(player);
        check(!profile.bossBar, "player can turn the boss bar off");
        var menus = new JobsMenu(jobs); menus.open(player, false, 0, null);
        check(current.getSize() == 45 && current.getItem(36).getType() == Material.COMPARATOR
                        && current.getItem(39).getType() == Material.CLOCK && current.getItem(41).getType() == Material.BOOK,
                "settings are bottom left while paycheck and navigation stay centered");
        check(java.util.stream.IntStream.range(0, current.getSize()).noneMatch(slot -> current.getItem(slot) != null
                        && current.getItem(slot).getItemMeta().displayName().toString().matches(".*(Boss bar:|Level-up chat:|Payday chat:).*")),
                "the jobs page has no leftover feedback toggles");
        check(((JobsMenu.Page) current.getHolder()).getInventory() == current && current.getItem(4).getType() == Material.BOOK && current.getItem(44).getType() == Material.OAK_DOOR,
                "info is top center and close is bottom right");
        check(java.util.stream.IntStream.of(19, 20, 21, 22, 23, 24, 25, 11, 12, 13, 14, 15, 29, 30, 31, 32, 33).allMatch(s -> current.getItem(s) != null && current.getItem(s).getItemMeta().lore().size() >= 6)
                        && current.getItem(10) == null && current.getItem(0).getType() == Material.GRAY_STAINED_GLASS_PANE,
                "jobs fill the middle row first with balanced overflow and a gray border");
        check(current.getItem(19).getData(io.papermc.paper.datacomponent.DataComponentTypes.TOOLTIP_DISPLAY).hiddenComponents()
                .contains(io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS), "menu tools explicitly hide native attack attributes");
        check(lore(20).stream().anyMatch(line -> line.contains("$0.006 / stone block"))
                        && lore(20).stream().anyMatch(line -> line.contains("$0.03 / common ore (coal, copper, Nether ores)"))
                        && lore(20).stream().anyMatch(line -> line.contains("$0.08 / iron, gold, redstone or lapis ore"))
                        && lore(20).stream().anyMatch(line -> line.contains("$0.35 / rare ore (diamond, emerald, ancient debris)")),
                "Mining's four rates explain which block groups qualify");
        check(lore(jobSlot("engineering")).stream().anyMatch(line -> line.contains("/ redstone torch, repeater, tripwire hook or rail crafted"))
                        && lore(jobSlot("engineering")).stream().anyMatch(line -> line.contains("/ powered, detector or activator rail crafted"))
                        && lore(jobSlot("engineering")).stream().anyMatch(line -> line.contains("/ component crafted (piston, hopper, observer, etc.)")),
                "Engineering's three rates describe their component groups");
        check(profile.notifications, "level-up chat starts enabled by default");
        jobs.settings().set("feedback.level-up-chat-default", false);
        check(!jobs.profile(player(UUID.fromString("22245436-4f12-42ca-82cf-cb56909ee37a"), "QuietChecks")).notifications,
                "server owners can change the default chat preference for new players");
        jobs.settings().set("feedback.level-up-chat-default", true);
        var chatPlayer = player(new UUID(15, 1), "LevelMessageChecks");
        jobs.award(chatPlayer, JobRegistry.Activity.BLOCK_BREAK, Material.STONE, null, jobs.registry().get("mining").progression().required(1));
        check(messages.getOrDefault(chatPlayer.getUniqueId(), List.of()).stream().anyMatch(text -> text.startsWith("Reached level ") && text.endsWith(" Mining")),
                "level-up message uses Reached level followed by the level and job name");
        var drag = new InventoryDragEvent(view(), new ItemStack(Material.DIRT), new ItemStack(Material.DIRT), false, Map.of(0, new ItemStack(Material.DIRT)));
        menus.drag(drag); check(drag.isCancelled(), "menu items cannot be dragged out");
        var click = new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 0, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 0);
        menus.click(click); check(click.isCancelled(), "menu items cannot be hotbar-swapped");
        for (var type : List.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT,
                ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.CONTROL_DROP, ClickType.SWAP_OFFHAND, ClickType.MIDDLE)) {
            for (int slot : new int[]{0, 50, -999}) {
                var attempt = new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, slot, type, InventoryAction.UNKNOWN);
                Bukkit.getPluginManager().callEvent(attempt);
                check(attempt.isCancelled(), "menu blocks " + type + " at slot " + slot);
            }
        }
        var creative = new InventoryCreativeEvent(view(), InventoryType.SlotType.CONTAINER, 0, new ItemStack(Material.DIAMOND));
        Bukkit.getPluginManager().callEvent(creative);
        check(creative.isCancelled(), "creative inventory actions cannot alter menu items");
        menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 36, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        later(2, () -> {
            check(current.getSize() == 27 && current.getItem(10).getType() == Material.LEVER
                            && current.getItem(12).getType() == Material.PAPER && current.getItem(14).getType() == Material.GOLD_NUGGET
                            && current.getItem(16).getType() == Material.CLOCK,
                    "settings group personal feedback and clock preferences in a compact menu");
            check(current.getItem(22).getType() == Material.ARROW && current.getItem(26).getType() == Material.OAK_DOOR,
                    "settings offer a back button and a bottom-right close button");
            var settingsDrag = new InventoryDragEvent(view(), new ItemStack(Material.DIRT), new ItemStack(Material.DIRT), false, Map.of(10, new ItemStack(Material.DIRT)));
            menus.drag(settingsDrag); check(settingsDrag.isCancelled(), "settings items cannot be dragged out");
            menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL));
            later(2, () -> {
                check(profile.bossBar && current.getSize() == 27 && current.getItem(10).getItemMeta().displayName().toString().contains("On"),
                        "toggling the boss bar updates its state without leaving settings");
                menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 22, ClickType.LEFT, InventoryAction.PICKUP_ALL));
                later(2, () -> {
                    check(current.getSize() == 45 && current.getItem(39).getType() == Material.CLOCK,
                            "settings return to the jobs page with the paycheck entry intact");
                    settingsCloseChecks(menus);
                });
            });
        });
    }
    void settingsCloseChecks(JobsMenu menus) {
        menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 36, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        later(2, () -> {
            int before = closedMenus;
            menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 26, ClickType.LEFT, InventoryAction.PICKUP_ALL));
            later(2, () -> {
                check(closedMenus == before + 1, "the compact settings menu closes from its own bottom-right slot");
                boolean notifications = profile.notifications;
                menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 12, ClickType.LEFT, InventoryAction.PICKUP_ALL));
                menus.open(player, false, 0, null);
                later(2, () -> check(profile.notifications == notifications && current.getSize() == 45,
                        "a queued settings click cannot change preferences after the player leaves that page"));
            });
        });
    }
    void travelChecks() throws Exception {
        check(jobs.registry().get("exploring").distancePerReward() == 500 && jobs.registry().get("sailing").distancePerReward() == 500,
                "walking and sailing use their configured distance milestones");
        check(jobs.registry().get("dueling").name().equals("Dueling") && jobs.registry().get("treefelling").name().equals("Treefelling"),
                "default job IDs match their display names");
        // Compress a journey into one test tick; the normal speed ceiling is restored afterward.
        jobs.settings().set("rules.maximum-walk-blocks-per-sample", 1000);
        Player grounded = player(WALK, "WalkingChecks");
        Player jumpingBase = player(UUID.fromString("27d1a520-ae90-4b3d-9f49-ce485ba05c1a"), "JumpingChecks");
        // This check isolates travel batches; discovery bonuses have their own integration checks.
        jobs.profile(grounded).biomes.add(world.getBiome(base, 200, 2048).getKey().toString());
        jobs.profile(jumpingBase).biomes.addAll(jobs.profile(grounded).biomes);
        Player jumping = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, method, args) -> switch (method.getName()) {
            case "isOnGround" -> false;
            case "getFallDistance" -> 1.0f;
            default -> method.invoke(jumpingBase, args);
        });
        for (int i = 0; i < 1999; i++) {
            double x = base + i * .25;
            Bukkit.getPluginManager().callEvent(new PlayerMoveEvent(grounded, new Location(world, x, 200, 2048), new Location(world, x + .25, 200, 2048)));
            Bukkit.getPluginManager().callEvent(new PlayerMoveEvent(jumping, new Location(world, x, 200 + (i % 2), 2048), new Location(world, x + .25, 201 - (i % 2), 2048)));
        }
        check(jobs.profile(grounded).xp("exploring") == 0 && jobs.profile(jumping).xp("exploring") == 0 && barsShown.getOrDefault(WALK, 0) == 0,
                "travel below 500 blocks awards no XP or money and does not open a boss bar");
        for (Player p : List.of(grounded, jumping))
            Bukkit.getPluginManager().callEvent(new PlayerMoveEvent(p, new Location(world, base, 200, 2048), new Location(world, base + .25, 201, 2048)));
        check(jobs.profile(grounded).xp("exploring") == 10 && jobs.profile(jumping).xp("exploring") == 10,
                "jumping and grounded walking count exactly the same horizontal distance");
        check(jobs.profile(grounded).earnings.total("exploring", System.currentTimeMillis()).compareTo(new BigDecimal("0.375")) == 0
                        && barsShown.getOrDefault(WALK, 0) == 1, "500-block milestone applies the full reward and one boss-bar update");
        var cancelled = new PlayerMoveEvent(grounded, new Location(world, base, 200, 2048), new Location(world, base + 1, 200, 2048));
        cancelled.setCancelled(true); Bukkit.getPluginManager().callEvent(cancelled);
        Bukkit.getPluginManager().callEvent(new PlayerTeleportEvent(grounded, new Location(world, base, 200, 2048), new Location(world, base + 100, 200, 2048)));
        check(jobs.profile(grounded).travel.distance("exploring") == 0, "cancelled moves and teleports do not accumulate distance");
        jobs.award(grounded, JobRegistry.Activity.TRAVEL, null, null, 25);
        var position = new java.util.concurrent.atomic.AtomicReference<>(new Location(world, base, 200, 2048));
        Player[] driver = new Player[1];
        Boat boat = (Boat) Proxy.newProxyInstance(Boat.class.getClassLoader(), new Class<?>[]{Boat.class}, (p, method, args) -> switch (method.getName()) {
            case "getPassengers" -> List.of(driver[0]);
            case "getUniqueId" -> UUID.fromString("eced884d-ff59-4858-9202-ae5713b1b039");
            case "isInWater" -> true;
            default -> empty(method.getReturnType());
        });
        Player sailingBase = player(UUID.fromString("e46e345d-3d70-47fe-b4bd-167ce0ae8760"), "SailingChecks");
        driver[0] = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, method, args) -> switch (method.getName()) {
            case "getVehicle" -> boat;
            case "getLocation" -> position.get();
            default -> method.invoke(sailingBase, args);
        });
        activities.sampleSailing(driver[0]);
        for (int i = 1; i <= 19; i++) { position.set(new Location(world, base + i * 25, 200, 2048)); activities.sampleSailing(driver[0]); }
        check(jobs.profile(driver[0]).xp("sailing") == 0, "sailing below 500 blocks keeps its reward buffered");
        position.set(new Location(world, base + 500, 200, 2048)); activities.sampleSailing(driver[0]);
        check(jobs.profile(driver[0]).xp("sailing") == 5 && jobs.profile(driver[0]).earnings.total("sailing", System.currentTimeMillis()).compareTo(new BigDecimal("0.25")) == 0,
                "500-block sailing milestone applies its full XP and money reward");
        for (int i = 1; i <= 5; i++) { position.set(new Location(world, base + 500 + i * .2, 200, 2048)); activities.sampleSailing(driver[0]); }
        check(Math.abs(jobs.profile(driver[0]).travel.distance("sailing") - .6) < 1e-6, "slow sailing buffers small samples instead of discarding their distance");
        activities.resetTravel();
        check(Math.abs(jobs.profile(driver[0]).travel.distance("sailing") - 1) < 1e-6, "a reload flushes unfinished sailing samples into saved travel progress");
        jobs.settings().set("rules.maximum-walk-blocks-per-sample", 12);
    }
    void paymentChecks() {
        rejected.add(RETRY); throwAfter.add(UNCERTAIN);
        check(jobs.registry().get("dueling").rules().getFirst().money().compareTo(new BigDecimal("2.5")) == 0, "a default Dueling kill pays two and a half currency");
        // Seed 12.50 to exercise fractional remainders and automatic payment retries.
        jobs.award(player(RETRY, "RetryChecks"), JobRegistry.Activity.PLAYER_KILL, null, EntityType.PLAYER, 5);
        jobs.award(player(UNCERTAIN, "UncertainChecks"), JobRegistry.Activity.PLAYER_KILL, null, EntityType.PLAYER, 5);
    }
    void afterPayments() throws Exception {
        check(profile.bossBar, "boss-bar button applies its toggle after the inventory click");
        jobs.getCommand("jobs").execute(player, "jobs", new String[]{"bossbar"});
        check(!profile.bossBar, "boss-bar command uses the same saved preference");
        check(jobs.players().get(RETRY).pending.compareTo(new BigDecimal("12.50")) == 0 && balances.getOrDefault(RETRY, BigDecimal.ZERO).signum() == 0,
                "rejected payment remains available for an automatic retry");
        check(jobs.players().get(UNCERTAIN).pending.compareTo(new BigDecimal("0.50")) == 0 && balances.get(UNCERTAIN).compareTo(new BigDecimal("12")) == 0,
                "provider exception after credit is reconciled without duplication");
        check(profile.pending.compareTo(BigDecimal.ONE) < 0 && balances.get(MAIN).signum() > 0, "whole money pays immediately; only the fraction remains");
        check(worker.submit(store::unresolved).get(10, TimeUnit.SECONDS).isEmpty(), "all successful and failed attempts settle without admin review");
        rejected.remove(RETRY);
        later(45, () -> {
            check(jobs.players().get(RETRY).pending.compareTo(new BigDecimal("0.50")) == 0 && balances.get(RETRY).compareTo(new BigDecimal("12")) == 0, "failed payments automatically resume without new activity or an admin");
            fractionalChecks();
        });
    }
    void fractionalChecks() throws Exception {
        var file = new java.io.File(jobs.getDataFolder(), "config.yml");
        var yaml = YamlConfiguration.loadConfiguration(file); yaml.set("economy.whole-number-payouts", false); yaml.save(file);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "jobs reload");
        var fractional = player(UUID.fromString("6cab3b20-56a8-4e3e-8761-c07dbadcd2ae"), "FractionalChecks");
        jobs.award(fractional, JobRegistry.Activity.BLOCK_BREAK, Material.DIRT, null, 20);
        later(15, () -> {
            check(jobs.profile(fractional).pending.signum() == 0 && balances.get(fractional.getUniqueId()).compareTo(new BigDecimal("0.17")) == 0,
                    "fractional mode pays new earnings without waiting for a whole coin");
            check(worker.submit(store::load).get(10, TimeUnit.SECONDS).get(MAIN).bossBar == false, "boss-bar preference is saved");
            unavailableChecks();
        });
    }
    void unavailableChecks() {
        UUID id = UUID.fromString("8473a5af-c635-48e9-841e-b18c5a351ab2");
        Bukkit.getServicesManager().unregister(Economy.class, provider);
        var p = player(id, "UnavailableChecks");
        jobs.award(p, JobRegistry.Activity.BLOCK_BREAK, Material.DIRT, null, 20);
        later(25, () -> {
            check(jobs.profile(p).pending.compareTo(new BigDecimal("0.17")) == 0, "unavailable economy retains earnings");
            Bukkit.getServicesManager().register(Economy.class, provider, this, ServicePriority.Highest);
            later(25, () -> {
                check(jobs.profile(p).pending.signum() == 0 && balances.get(id).compareTo(new BigDecimal("0.17")) == 0,
                        "payouts resume automatically when the economy returns");
                loadChecks();
            });
        });
    }
    void loadChecks() throws Exception {
        var historical = new ArrayList<PlayerProfile.Snapshot>();
        for (int i = 0; i < 10_000; i++) {
            var p = new PlayerProfile(new UUID(1, i), "Saved" + i);
            for (var job : jobs.registry().jobs()) p.xp.put(job.id(), (double) (i + 1));
            jobs.players().put(p.id, p);
            historical.add(p.snapshot());
        }
        worker.submit(() -> { store.save(historical, Map.of()); return null; }).get(30, TimeUnit.SECONDS);
        // An unmarked change is a sentinel: a save of every known profile would overwrite this row.
        jobs.players().get(new UUID(1, 0)).name = "UnexpectedRewrite";
        for (int i = 0; i < 100; i++) {
            var p = player(new UUID(2, i), "Active" + i); jobs.profile(p); loadPlayers.add(p);
        }
        // Fixture creation and the initial database seed are not gameplay work.
        later(1, () -> {
            loadStart = System.nanoTime(); sampling = true; loadTick(0);
        });
    }
    void loadTick(int tick) {
        long start = System.nanoTime();
        // 100 players, each doing 20 block actions and one mob kill per second.
        for (Player p : loadPlayers) {
            jobs.award(p, JobRegistry.Activity.BLOCK_BREAK, Material.STONE, null, 1);
            if (tick % 20 == 0) jobs.award(p, JobRegistry.Activity.MOB_KILL, null, EntityType.ZOMBIE, 1);
            Bukkit.getPluginManager().callEvent(new PlayerMoveEvent(p, new Location(world, base + tick * .2, 200, 2048),
                    new Location(world, base + (tick + 1) * .2, 201 - (tick % 2), 2048)));
        }
        // 100 changed block positions each tick in already loaded chunks.
        for (int i = 0; i < 100; i++) history.mark(world.getBlockAt(base + (i % 16), 120 + (tick / 16) % 150, 2048 + ((tick + i) % 16)));
        loadTimes.add(System.nanoTime() - start);
        if (tick == 100) check(loadPlayers.stream().allMatch(p -> balances.getOrDefault(p.getUniqueId(), BigDecimal.ZERO).signum() > 0),
                "payout queue is fair while all 100 players keep earning");
        if (tick < loadTicks - 1) later(1, () -> loadTick(tick + 1));
        else later(40, this::afterLoad);
    }
    void afterLoad() throws Exception {
        sampling = false;
        var sorted = loadTimes.stream().sorted().toList();
        getLogger().info(String.format(Locale.ROOT, "LOAD: 100 active / 10,000 saved players; %,d block rewards + %,d kills + %,d tracking edits + %,d movement events; main-thread work p50=%.3f ms p95=%.3f ms max=%.3f ms; elapsed=%.2f s",
                loadTicks * 100, ((loadTicks + 19) / 20) * 100, loadTicks * 100, loadTicks * 100,
                sorted.get(sorted.size() / 2) / 1e6, sorted.get((int) (sorted.size() * .95)) / 1e6, sorted.getLast() / 1e6, (System.nanoTime() - loadStart) / 1e9));
        var ticks = fullTicks.stream().sorted().toList();
        double p95 = ticks.get((int) (ticks.size() * .95));
        getLogger().info(String.format(Locale.ROOT, "LOAD: full server ticks, including scheduled payments, saves and tracking flushes: p50=%.3f ms p95=%.3f ms max=%.3f ms",
                ticks.get(ticks.size() / 2), p95, ticks.getLast()));
        check(p95 < 50, "95% of full server ticks stay inside the 50 ms tick budget under simulated load");
        int kills = (loadTicks + 19) / 20;
        double traveled = loadTicks * .2, paidDistance = Math.floor((traveled + 1e-6) / 500) * 500;
        check(loadPlayers.stream().allMatch(p -> jobs.profile(p).xp("mining") == loadTicks && jobs.profile(p).xp("hunting") == kills * 5), "all simulated actions retain their XP");
        check(loadPlayers.stream().allMatch(p -> Math.abs(jobs.profile(p).xp("exploring") - 10 - paidDistance * .01) < 1e-6
                        && Math.abs(jobs.profile(p).travel.distance("exploring") - (traveled - paidDistance)) < 1e-6),
                "movement events give one discovery bonus and buffer distance without repeatedly applying travel XP");
        var expected = new BigDecimal("0.006").multiply(BigDecimal.valueOf(loadTicks)).min(new BigDecimal("25"))
                .add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(kills)).min(new BigDecimal("25")))
                .add(new BigDecimal("0.00075").multiply(BigDecimal.valueOf(paidDistance)).add(new BigDecimal("0.25")).min(new BigDecimal("25")));
        check(loadPlayers.stream().allMatch(p -> balances.getOrDefault(p.getUniqueId(), BigDecimal.ZERO).add(jobs.profile(p).pending).compareTo(expected) == 0),
                "all 100 simulated players receive exact payments under load");
        check(worker.submit(store::load).get(10, TimeUnit.SECONDS).get(new UUID(1, 0)).name.equals("Saved0"), "idle saved players are not rewritten during active-player saves");
        var menus = new JobsMenu(jobs); long before = System.nanoTime(); menus.open(player, true, 0, "mining");
        getLogger().info(String.format(Locale.ROOT, "LOAD: leaderboard request with 10,000 saved players returned in %.3f ms; sorting runs off the server thread", (System.nanoTime() - before) / 1e6));
        later(20, () -> {
            menus.open(player, true, 0, "mining");
            check(current.getItem(9).getType() == Material.PLAYER_HEAD && current.getItem(4).getItemMeta().lore().stream().anyMatch(l -> l.toString().contains("Your rank:")),
                    "large leaderboard uses player heads and includes personal rank");
            check(java.util.stream.IntStream.range(0, 27).allMatch(index -> {
                int slot = 9 + index;
                return current.getItem(slot).getItemMeta().displayName() instanceof net.kyori.adventure.text.TextComponent name
                        && name.content().startsWith("#" + (index + 1) + " ");
            }), "ranked player entries read left to right from the top row to the bottom row");
            var owner = current.getItem(9).getData(io.papermc.paper.datacomponent.DataComponentTypes.PROFILE);
            check(owner != null && owner.dynamic() && owner.uuid().equals(MAIN), "leaderboard head identifies its ranked player and resolves skins without a server lookup");
            check(current.getItem(0).getType() == Material.PURPLE_STAINED_GLASS_PANE, "leaderboards use purple borders");
            int nextSlot = java.util.stream.IntStream.range(36, 44).filter(slot -> current.getItem(slot) != null
                    && current.getItem(slot).getItemMeta().displayName() instanceof net.kyori.adventure.text.TextComponent name
                    && name.content().equals("Next page")).findFirst().orElseThrow();
            var next = new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, nextSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            menus.click(next);
            later(2, () -> {
                check(current.getItem(9).getItemMeta().displayName().toString().contains("#28 "), "leaderboard next page starts with rank 28 at the top left");
                check(current.getItem(36).getType() == Material.COMPARATOR, "detailed leaderboards also offer settings at the bottom left");
                menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 36, ClickType.LEFT, InventoryAction.PICKUP_ALL));
                later(2, () -> {
                    check(current.getSize() == 27 && current.getItem(0).getType() == Material.PURPLE_STAINED_GLASS_PANE,
                            "leaderboard settings keep the purple border");
                    var refresh = JobsMenu.class.getDeclaredMethod("refresh", Player.class, JobsMenu.Page.class);
                    refresh.setAccessible(true); refresh.invoke(menus, player, current.getHolder());
                    later(2, () -> {
                        check(current.getSize() == 27, "ranking refreshes do not replace an open settings menu");
                        menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 22, ClickType.LEFT, InventoryAction.PICKUP_ALL));
                        later(2, () -> {
                            check(current.getItem(9).getItemMeta().displayName().toString().contains("#28 "),
                                    "Back restores the selected job and leaderboard page");
                            previewChecks(menus);
                        });
                    });
                });
            });
        });
    }
    void previewChecks(JobsMenu menus) {
            menus.open(player, true, 0, null);
            later(20, () -> {
                menus.open(player, true, 0, null);
                var lore = current.getItem(19).getItemMeta().lore();
                check(lore.stream().anyMatch(line -> line instanceof net.kyori.adventure.text.TextComponent text && text.content().startsWith("#1 ")),
                        "leaderboard selector shows top players in job lore");
                check(lore.stream().noneMatch(line -> line.toString().contains("Earned this hour")), "leaderboard preview omits personal job statistics");
                check(current.getItem(36).getType() == Material.COMPARATOR
                                && java.util.stream.IntStream.range(0, current.getSize()).noneMatch(slot -> current.getItem(slot) != null
                                && current.getItem(slot).getItemMeta().displayName().toString().matches(".*(Boss bar:|Level-up chat:|Payday chat:).*")),
                        "the leaderboards page has one settings entry and no leftover toggles");
                jobs.settings().set("feedback.leaderboard-preview-size", 3); menus.open(player, true, 0, null);
                check(current.getItem(19).getItemMeta().lore().stream().filter(line -> line instanceof net.kyori.adventure.text.TextComponent text
                        && text.content().startsWith("#")).count() == 3, "leaderboard preview size is configurable");
                jobs.settings().set("feedback.leaderboard-preview-size", 5);
                menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 36, ClickType.LEFT, InventoryAction.PICKUP_ALL));
                later(2, () -> {
                    menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 12, ClickType.LEFT, InventoryAction.PICKUP_ALL));
                    later(2, () -> {
                        check(!profile.notifications && current.getSize() == 27, "level-up chat toggles from settings without leaving the menu");
                        menus.click(new InventoryClickEvent(view(), InventoryType.SlotType.CONTAINER, 22, ClickType.LEFT, InventoryAction.PICKUP_ALL));
                        later(2, () -> {
                            check(current.getSize() == 45 && current.getItem(4).getType() == Material.GOLDEN_HELMET,
                                    "Back returns to the main leaderboards page");
                            pistonChecks();
                        });
                    });
                });
            });
    }
    void pistonChecks() {
        for (int i = 13; i <= 18; i++) block(i).setType(Material.AIR, false);
        var piston = block(14); piston.setType(Material.STICKY_PISTON, true);
        var facing = (org.bukkit.block.data.Directional) piston.getBlockData(); facing.setFacing(org.bukkit.block.BlockFace.EAST); piston.setBlockData(facing, true);
        block(15).setType(Material.OAK_LOG, false); history.markPlaced(block(15));
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "setblock " + (base + 13) + " 200 2048 redstone_block");
        later(20, () -> {
            check(block(16).getType() == Material.OAK_LOG && history.playerPlaced(block(16)), "real piston preserves placed-log tracking across a chunk boundary");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "setblock " + (base + 13) + " 200 2048 air");
            later(20, () -> {
                check(block(15).getType() == Material.OAK_LOG && history.playerPlaced(block(15)), "real sticky piston preserves tracking when retracting");
                double before = profile.xp("treefelling");
                Bukkit.getPluginManager().callEvent(new BlockBreakEvent(block(15), player));
                check(profile.xp("treefelling") == before, "piston movement cannot turn placed logs into rewards");
                prepareRestart();
            });
        });
    }
    void prepareRestart() throws Exception {
        jobs.save(); worker.submit(() -> null).get(10, TimeUnit.SECONDS);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "jobs backup");
        worker.submit(() -> null).get(10, TimeUnit.SECONDS);
        try (var files = Files.list(jobs.getDataFolder().toPath().resolve("backups"))) {
            var backups = files.filter(path -> path.toString().endsWith(".db")).toList();
            check(backups.size() == 1, "admin backup creates one checked database snapshot");
            try (var backup = new DataStore(backups.getFirst())) {
                var saved = backup.load().get(MAIN);
                check(saved != null && saved.xp("mining") == profile.xp("mining"), "backup contains current progress and can be opened independently");
            }
        }
        // Simulate an interruption after reservation and before a Vault call.
        var crashPlayer = jobs.profile(player(CRASH, "CrashChecks"));
        crashPlayer.pending = new BigDecimal("0.37");
        var payment = new DataStore.Payment(UUID.randomUUID().toString(), CRASH, crashPlayer.name, new BigDecimal("2"));
        worker.submit(() -> { store.reserve(crashPlayer.snapshot(), payment); return null; }).get(10, TimeUnit.SECONDS);
        getDataFolder().mkdirs(); Files.writeString(getDataFolder().toPath().resolve("restart"), Integer.toString(checks));
        getLogger().info("RELEASE CHECKS PHASE 1 PASS: " + checks + " checks");
        Bukkit.shutdown();
    }
    void restartChecks() throws Exception {
        var crash = jobs.players().get(CRASH);
        check(crash != null && crash.pending.compareTo(new BigDecimal("2.37")) == 0, "restart automatically recovers an interrupted reservation");
        check(!profile.bossBar, "boss-bar toggle survives a server restart");
        check(!profile.notifications, "level-up chat toggle survives a server restart");
        check(jobs.players().get(WALK).travel.distance("exploring") == 25, "uncompleted travel distance survives a server restart");
        check(!history.eligible(block(0)) && history.playerPlaced(block(15)), "natural and placed block tracking survive a server restart");
        check(!history.placementRewarded(block(3)) && !history.placementRewarded(block(4)) && !history.placementRewarded(block(6).getRelative(org.bukkit.block.BlockFace.UP)),
                "the new defaults leave no placement claims after a restart");
        check(profile.earnings.total("mining", System.currentTimeMillis()).compareTo(new BigDecimal("25")) == 0, "hourly cap survives a server restart");
        double xp = profile.xp("mining");
        jobs.award(player, JobRegistry.Activity.BLOCK_BREAK, Material.STONE, null, 1);
        check(profile.xp("mining") == xp + 1 && profile.earnings.total("mining", System.currentTimeMillis()).compareTo(new BigDecimal("25")) == 0, "relogging or restarting cannot reset the money cap");
        later(45, () -> {
            check(crash.pending.signum() == 0 && balances.get(CRASH).compareTo(new BigDecimal("2.37")) == 0, "recovered earnings pay automatically, including fractional mode");
            check(worker.submit(store::unresolved).get(10, TimeUnit.SECONDS).isEmpty(), "restart recovery leaves no payments waiting for an admin");
            getLogger().info("RELEASE CHECKS PHASE 2 PASS: " + checks + " checks");
            Bukkit.shutdown();
        });
    }
}
