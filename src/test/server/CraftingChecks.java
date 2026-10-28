package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Exercises Paper's real crafting menus without connecting clients. No server internals enter the plugin. */
public class CraftingChecks extends ReleaseChecks {
    int nextPlayer;
    record Table(Player player, Object handle, Object menu, CraftingInventory grid, InventoryView view) { }

    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "crafting build starts on Paper");
        immediatePayments();
        world = Bukkit.getWorlds().getFirst();
        worker = (ExecutorService) field(jobs, "worker"); store = (DataStore) field(jobs, "store");
        installProvider();
        if (Files.exists(getDataFolder().toPath().resolve("restart"))) {
            check(jobs.players().values().stream().anyMatch(p -> p.xp("engineering") == 512), "bulk crafting XP survives restart");
            check(worker.submit(store::unresolved).get().isEmpty(), "crafting payouts leave no unresolved reservations after restart");
            getLogger().info("RELEASE CHECKS PHASE 2 PASS: " + checks + " crafting checks"); Bukkit.shutdown(); return;
        }
        var single = rails(1);
        check(jobs.profile(single.player()).xp("engineering") == 0, "a valid recipe preview gives no reward");
        click(single, ClickType.LEFT, "PICKUP", 0, false);
        reward(single, "engineering", 16, "0.32", "normal crafting rewards each of the sixteen rails produced");

        var bulk = rails(32); click(bulk, ClickType.SHIFT_LEFT, "QUICK_MOVE", 0, false);
        reward(bulk, "engineering", 512, "10.24", "shift crafting counts all thirty-two consumed recipes");
        check(count(bulk.player(), Material.RAIL) == 512, "bulk reward matches the actual output in the player's inventory");

        var partial = rails(2); fill(partial.player()); partial.player().getInventory().setItem(0, new ItemStack(Material.RAIL, 63));
        click(partial, ClickType.SHIFT_LEFT, "QUICK_MOVE", 0, false);
        reward(partial, "engineering", 16, "0.32", "a partial shift transfer and dropped remainder reward the recipe once");
        check(count(partial.player(), Material.RAIL) == 64 && partial.grid().getMatrix()[0].getAmount() == 1,
                "partial transfer consumes exactly one recipe and fills the last inventory space");

        var full = rails(2); fill(full.player()); click(full, ClickType.SHIFT_LEFT, "QUICK_MOVE", 0, false);
        reward(full, "engineering", 0, "0", "a full inventory earns no bulk crafting reward");
        check(full.grid().getMatrix()[0].getAmount() == 2, "failed bulk crafting leaves its ingredients untouched");

        var blocked = rails(1); invoke(blocked.menu(), "setCarried", nms(new ItemStack(Material.COBBLESTONE, 64)));
        click(blocked, ClickType.LEFT, "PICKUP", 0, false);
        reward(blocked, "engineering", 0, "0", "an incompatible cursor cannot earn a reward for a failed click");

        var cancelled = rails(1); click(cancelled, ClickType.LEFT, "PICKUP", 0, true);
        reward(cancelled, "engineering", 0, "0", "cancelled crafts earn nothing");
        Listener protection = new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST) public void deny(CraftItemEvent event) { event.setCancelled(true); }
        };
        Bukkit.getPluginManager().registerEvents(protection, this);
        try {
            var protectedTable = rails(1); click(protectedTable, ClickType.LEFT, "PICKUP", 0, false);
            reward(protectedTable, "engineering", 0, "0", "another plugin can cancel crafting without awarding XP or money");
        } finally { HandlerList.unregisterAll(protection); }

        var hotbar = rails(1); click(hotbar, ClickType.NUMBER_KEY, "SWAP", 0, false);
        reward(hotbar, "engineering", 16, "0.32", "hotbar crafting rewards the actual output once");
        var dropped = rails(1); click(dropped, ClickType.CONTROL_DROP, "THROW", 1, false);
        reward(dropped, "engineering", 16, "0.32", "dropping a crafted result rewards only the consumed recipe");

        var creative = rails(1); creative.player().setGameMode(GameMode.CREATIVE);
        click(creative, ClickType.LEFT, "PICKUP", 0, false);
        reward(creative, "engineering", 0, "0", "creative crafting never earns rewards");

        var bricks = table(); bricks.grid().setMatrix(new ItemStack[]{new ItemStack(Material.BRICK, 2), new ItemStack(Material.BRICK, 2), null,
                new ItemStack(Material.BRICK, 2), new ItemStack(Material.BRICK, 2), null, null, null, null});
        click(bricks, ClickType.SHIFT_LEFT, "QUICK_MOVE", 0, false);
        reward(bricks, "building", 2, "0.030", "Building rewards crafted masonry blocks");

        var small = table(true); small.grid().setMatrix(new ItemStack[]{new ItemStack(Material.BRICK, 2), new ItemStack(Material.BRICK, 2),
                new ItemStack(Material.BRICK, 2), new ItemStack(Material.BRICK, 2)});
        click(small, ClickType.SHIFT_LEFT, "QUICK_MOVE", 0, false);
        reward(small, "building", 2, "0.030", "the player's two-by-two crafting grid also counts bulk output correctly");

        var automatic = table();
        var automaticRecipe = Bukkit.getRecipesFor(new ItemStack(Material.RAIL)).stream().filter(org.bukkit.inventory.CraftingRecipe.class::isInstance).findFirst().orElseThrow();
        Bukkit.getPluginManager().callEvent(new org.bukkit.event.block.CrafterCraftEvent(world.getBlockAt(100, 1, 100), (org.bukkit.inventory.CraftingRecipe) automaticRecipe, new ItemStack(Material.RAIL, 16)));
        check(jobs.profile(automatic.player()).xp("engineering") == 0, "an automatic crafter produces no player crafting reward");

        for (var input : List.of(Material.OAK_LOG, Material.REDSTONE_BLOCK, Material.IRON_BLOCK, Material.DIAMOND_BLOCK)) {
            var excluded = table(); excluded.grid().setMatrix(new ItemStack[]{new ItemStack(input), null, null, null, null, null, null, null, null});
            click(excluded, ClickType.SHIFT_LEFT, "QUICK_MOVE", 0, false);
            check(jobs.profile(excluded.player()).xp("building") == 0 && jobs.profile(excluded.player()).xp("engineering") == 0,
                    "converting " + input + " gives no crafting job reward");
        }
        var allowed = jobs.registry().get("building").rules().stream().filter(rule -> rule.activity() == JobRegistry.Activity.CRAFT)
                .flatMap(rule -> rule.materials().stream()).collect(java.util.stream.Collectors.toSet());
        check(allowed.size() == 13 && Collections.disjoint(allowed, Set.of(Material.OAK_PLANKS, Material.COBBLESTONE, Material.STONE_SLAB,
                        Material.GLASS_PANE, Material.WHITE_WOOL, Material.IRON_BLOCK, Material.QUARTZ_BLOCK, Material.QUARTZ_PILLAR)),
                "Building's crafting grid has thirteen explicit outputs and excludes cheap or reversible conversions");
        jobs.save(); worker.submit(() -> {}).get();
        Files.createDirectories(getDataFolder().toPath()); Files.writeString(getDataFolder().toPath().resolve("restart"), "ready");
        getLogger().info("RELEASE CHECKS PHASE 1 PASS: " + checks + " crafting checks"); Bukkit.shutdown();
    }
    Table table() throws Exception { return table(false); }
    Table table(boolean smallGrid) throws Exception {
        UUID id = new UUID(4, ++nextPlayer);
        Object server = invoke(Bukkit.getServer(), "getServer");
        Object profile = construct("com.mojang.authlib.GameProfile", id, "Craft" + nextPlayer);
        Object handle = construct("net.minecraft.server.level.ServerPlayer", server, invoke(world, "getHandle"), profile,
                invoke(Class.forName("net.minecraft.server.level.ClientInformation"), "createDefault"));
        Object connection = construct("net.minecraft.network.Connection", enumValue("net.minecraft.network.protocol.PacketFlow", "SERVERBOUND"));
        Object cookie = invoke(Class.forName("net.minecraft.server.network.CommonListenerCookie"), "createInitial", profile, false);
        Object listener = construct("net.minecraft.server.network.ServerGamePacketListenerImpl", server, connection, handle, cookie);
        setField(handle, "connection", listener);
        Object access = invoke(Class.forName("net.minecraft.world.inventory.ContainerLevelAccess"), "create", invoke(world, "getHandle"), construct("net.minecraft.core.BlockPos", 100, 1, 100));
        Object menu = smallGrid ? handle.getClass().getField("inventoryMenu").get(handle)
                : construct("net.minecraft.world.inventory.CraftingMenu", 1, invoke(handle, "getInventory"), access);
        setField(handle, "containerMenu", menu);
        InventoryView view = (InventoryView) invoke(menu, "getBukkitView");
        return new Table((Player) invoke(handle, "getBukkitEntity"), handle, menu, (CraftingInventory) view.getTopInventory(), view);
    }
    Table rails(int ingredients) throws Exception {
        var table = table();
        table.grid().setMatrix(new ItemStack[]{new ItemStack(Material.IRON_INGOT, ingredients), null, new ItemStack(Material.IRON_INGOT, ingredients),
                new ItemStack(Material.IRON_INGOT, ingredients), new ItemStack(Material.STICK, ingredients), new ItemStack(Material.IRON_INGOT, ingredients),
                new ItemStack(Material.IRON_INGOT, ingredients), null, new ItemStack(Material.IRON_INGOT, ingredients)});
        return table;
    }
    void click(Table table, ClickType click, String nativeClick, int button, boolean cancel) throws Exception {
        Recipe recipe = table.grid().getRecipe();
        check(recipe != null && !table.grid().getResult().isEmpty(), "native crafting grid has an actual recipe result");
        var event = new CraftItemEvent(recipe, table.view(), InventoryType.SlotType.RESULT, 0, click, InventoryAction.UNKNOWN, button);
        event.setCancelled(cancel); Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) invoke(table.menu(), "clicked", 0, button, enumValue("net.minecraft.world.inventory.ContainerInput", nativeClick), table.handle());
    }
    void reward(Table table, String job, double xp, String money, String label) {
        var progress = jobs.profile(table.player());
        check(progress.xp(job) == xp && progress.earnings.total(job, System.currentTimeMillis()).compareTo(new BigDecimal(money)) == 0,
                label + " (XP=" + progress.xp(job) + ", money=" + progress.earnings.total(job, System.currentTimeMillis()) + ")");
    }
    void fill(Player player) { for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Material.COBBLESTONE, 64)); }
    int count(Player player, Material material) { return Arrays.stream(player.getInventory().getStorageContents()).filter(Objects::nonNull).filter(i -> i.getType() == material).mapToInt(ItemStack::getAmount).sum(); }
    Object nms(ItemStack item) throws Exception { return invoke(Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack"), "asNMSCopy", item); }
    @SuppressWarnings({"unchecked", "rawtypes"}) Object enumValue(String type, String value) throws Exception { return Enum.valueOf((Class) Class.forName(type), value); }
    static boolean accepts(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i] == int.class ? Integer.class : types[i] == boolean.class ? Boolean.class
                    : types[i] == long.class ? Long.class : types[i] == double.class ? Double.class : types[i];
            if (args[i] != null && !type.isInstance(args[i])) return false;
        }
        return true;
    }
    static Object construct(String type, Object... args) throws Exception {
        for (var constructor : Class.forName(type).getConstructors()) if (accepts(constructor.getParameterTypes(), args)) return constructor.newInstance(args);
        throw new NoSuchMethodException(type + " constructor");
    }
    static Object invoke(Object target, String name, Object... args) throws Exception {
        Class<?> type = target instanceof Class<?> cls ? cls : target.getClass();
        for (var method : type.getMethods()) if (method.getName().equals(name) && accepts(method.getParameterTypes(), args)) {
            try { return method.invoke(target instanceof Class<?> ? null : target, args); }
            catch (InvocationTargetException e) { throw new Exception("Failed " + type.getSimpleName() + "." + name, e.getCause()); }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    static void setField(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
