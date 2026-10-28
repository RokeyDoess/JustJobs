package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.MapMeta;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Takes real menu results and verifies consumption, including bulk and failed transfers. */
public final class WorkstationChecks extends CraftingChecks {
    record Station(Player player, Object handle, Object menu, Inventory inventory, InventoryView view, int resultSlot) { }
    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "expanded jobs start on Paper");
        world = Bukkit.getWorlds().getFirst();
        worker = (ExecutorService) field(jobs, "worker");
        check(jobs.registry().jobs().size() == 17, "all seventeen default jobs load");
        check(jobs.settings().getString("economy.hourly-income-limit-per-job").equals("25"), "default money cap is twenty-five per job");
        if (Files.exists(getDataFolder().toPath().resolve("restart"))) {
            check(jobs.players().values().stream().anyMatch(p -> p.xp("building") == 32), "completed bulk stonecutting survives restart");
            finish(2); return;
        }
        var single = stone(1); take(single, ClickType.LEFT, InventoryAction.PICKUP_ALL, "PICKUP", 0, false);
        reward(single, "building", 1, "0.010", "a stonecutter pays for both slabs produced");
        var bulk = stone(32); take(bulk, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, "QUICK_MOVE", 0, false);
        reward(bulk, "building", 32, "0.320", "bulk stonecutting rewards the actual sixty-four outputs");
        check(count(bulk.player(), Material.STONE_BRICK_SLAB) == 64, "bulk rewards match the native inventory output");
        var full = stone(2); fill(full.player()); take(full, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, "QUICK_MOVE", 0, false);
        reward(full, "building", 0, "0", "a full inventory cannot earn from failed stonecutting");
        var partial = stone(4); fill(partial.player()); partial.player().getInventory().setItem(0, new ItemStack(Material.STONE_BRICK_SLAB, 62));
        take(partial, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, "QUICK_MOVE", 0, false);
        reward(partial, "building", 1, "0.010", "partial bulk cutting pays only for the consumed input");
        var cancelled = stone(1); take(cancelled, ClickType.LEFT, InventoryAction.PICKUP_ALL, "PICKUP", 0, true);
        reward(cancelled, "building", 0, "0", "cancelled result clicks earn nothing");
        var blocked = stone(1); invoke(blocked.menu(), "setCarried", nms(new ItemStack(Material.COBBLESTONE, 64)));
        take(blocked, ClickType.LEFT, InventoryAction.PICKUP_ALL, "PICKUP", 0, false);
        reward(blocked, "building", 0, "0", "an incompatible cursor cannot collect a reward");
        var hotbar = stone(1); take(hotbar, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, "SWAP", 0, false);
        reward(hotbar, "building", 1, "0.010", "hotbar result collection counts once");
        var dropped = stone(1); take(dropped, ClickType.CONTROL_DROP, InventoryAction.DROP_ALL_SLOT, "THROW", 1, false);
        reward(dropped, "building", 1, "0.010", "dropping a result counts only consumed materials");
        var preview = stone(1); settle(preview);
        reward(preview, "building", 0, "0", "recipe previews and closing unused inputs earn nothing");
        var creative = stone(1); creative.player().setGameMode(GameMode.CREATIVE);
        take(creative, ClickType.LEFT, InventoryAction.PICKUP_ALL, "PICKUP", 0, false);
        reward(creative, "building", 0, "0", "creative workstation use earns nothing");
        var rapid = stone(3);
        take(rapid, ClickType.LEFT, InventoryAction.PICKUP_ALL, "PICKUP", 0, false, false);
        take(rapid, ClickType.LEFT, InventoryAction.PICKUP_ALL, "PICKUP", 0, false, false);
        settle(rapid); reward(rapid, "building", 2, "0.020", "two rapid clicks count two consumed recipes without overlap");

        var loom = station("LoomMenu", Material.LOOM, 3);
        loom.inventory().setItem(0, new ItemStack(Material.WHITE_BANNER)); loom.inventory().setItem(1, new ItemStack(Material.RED_DYE));
        check(invoke(loom.menu(), "clickMenuButton", loom.handle(), 0).equals(true), "a loom pattern can be selected");
        take(loom, ClickType.LEFT, InventoryAction.PICKUP_ALL, "PICKUP", 0, false);
        reward(loom, "building", 1, "0.025", "weaving pays only when a pattern consumes dye");

        var smith = station("SmithingMenu", Material.SMITHING_TABLE, 3);
        smith.inventory().setItem(0, new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        smith.inventory().setItem(1, new ItemStack(Material.DIAMOND_SWORD)); smith.inventory().setItem(2, new ItemStack(Material.NETHERITE_INGOT));
        take(smith, ClickType.LEFT, InventoryAction.PICKUP_ALL, "PICKUP", 0, false);
        reward(smith, "smithing", 10, "0.25", "netherite upgrades pay the upgrade rate");
        var trim = station("SmithingMenu", Material.SMITHING_TABLE, 3);
        trim.inventory().setItem(0, new ItemStack(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE));
        trim.inventory().setItem(1, new ItemStack(Material.NETHERITE_CHESTPLATE)); trim.inventory().setItem(2, new ItemStack(Material.IRON_INGOT));
        take(trim, ClickType.LEFT, InventoryAction.PICKUP_ALL, "PICKUP", 0, false);
        reward(trim, "smithing", 3, "0.05", "trimming netherite armor uses the trim rate rather than the upgrade rate");

        var anvil = station("AnvilMenu", Material.ANVIL, 2);
        anvil.player().setLevel(50);
        var sword = new ItemStack(Material.IRON_SWORD); var damage = (Damageable) sword.getItemMeta(); damage.setDamage(200); sword.setItemMeta(damage);
        anvil.inventory().setItem(0, sword); anvil.inventory().setItem(1, new ItemStack(Material.IRON_INGOT));
        check(((Damageable) anvil.inventory().getItem(2).getItemMeta()).getDamage() == 138, "one ingot restores sixty-two iron-sword durability");
        take(anvil, ClickType.LEFT, InventoryAction.PICKUP_ALL, "PICKUP", 0, false);
        reward(anvil, "smithing", 1.24, "0.062", "anvil rewards scale with the fraction of durability restored");
        var rename = station("AnvilMenu", Material.ANVIL, 2); rename.player().setLevel(50);
        rename.inventory().setItem(0, new ItemStack(Material.IRON_SWORD)); invoke(rename.menu(), "setItemName", "Named sword");
        take(rename, ClickType.LEFT, InventoryAction.PICKUP_ALL, "PICKUP", 0, false);
        reward(rename, "smithing", 0, "0", "renaming an item gives no repair reward");

        for (var reagent : new Material[]{Material.PAPER, Material.GLASS_PANE, Material.MAP}) {
            var cartography = station("CartographyTableMenu", Material.CARTOGRAPHY_TABLE, 2);
            var map = new ItemStack(Material.FILLED_MAP); var meta = (MapMeta) map.getItemMeta();
            var view = Bukkit.createMap(world); view.setScale(org.bukkit.map.MapView.Scale.CLOSEST); meta.setMapView(view); map.setItemMeta(meta);
            cartography.inventory().setItem(0, map); cartography.inventory().setItem(1, new ItemStack(reagent));
            take(cartography, ClickType.LEFT, InventoryAction.PICKUP_ALL, "PICKUP", 0, false);
            reward(cartography, "exploring", reagent == Material.MAP ? 0 : 2, reagent == Material.MAP ? "0" : "0.05",
                    "cartography counts expanding and locking but excludes map copies: " + reagent);
        }
        var gear = table(); gear.grid().setMatrix(new ItemStack[]{new ItemStack(Material.DIAMOND), null, null,
                new ItemStack(Material.DIAMOND), null, null, new ItemStack(Material.STICK), null, null});
        click(gear, ClickType.LEFT, "PICKUP", 0, false);
        reward(gear, "smithing", 5, "0.10", "crafting a diamond sword rewards Smithing");
        jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        Files.createDirectories(getDataFolder().toPath()); Files.writeString(getDataFolder().toPath().resolve("restart"), "saved");
        finish(1);
    }
    Station station(String type, Material block, int resultSlot) throws Exception {
        var actor = table();
        world.getBlockAt(100, 1, 100).setType(block, false);
        var access = invoke(Class.forName("net.minecraft.world.inventory.ContainerLevelAccess"), "create", invoke(world, "getHandle"), construct("net.minecraft.core.BlockPos", 100, 1, 100));
        var menu = construct("net.minecraft.world.inventory." + type, 2, invoke(actor.handle(), "getInventory"), access);
        setField(actor.handle(), "containerMenu", menu);
        var view = (InventoryView) invoke(menu, "getBukkitView");
        return new Station(actor.player(), actor.handle(), menu, view.getTopInventory(), view, resultSlot);
    }
    Station stone(int amount) throws Exception {
        var station = station("StonecutterMenu", Material.STONECUTTER, 1);
        station.inventory().setItem(0, new ItemStack(Material.STONE_BRICKS, amount));
        var view = (org.bukkit.inventory.view.StonecutterView) station.view();
        int selected = -1;
        for (int i = 0; i < view.getRecipes().size(); i++) if (view.getRecipes().get(i).getResult().getType() == Material.STONE_BRICK_SLAB) selected = i;
        check(selected >= 0 && invoke(station.menu(), "clickMenuButton", station.handle(), selected).equals(true), "a native stonecutting recipe can be selected");
        return station;
    }
    void take(Station station, ClickType click, InventoryAction action, String nativeClick, int button, boolean cancel) throws Exception {
        take(station, click, action, nativeClick, button, cancel, true);
    }
    void take(Station station, ClickType click, InventoryAction action, String nativeClick, int button, boolean cancel, boolean finish) throws Exception {
        check(station.inventory().getItem(station.resultSlot()) != null && !station.inventory().getItem(station.resultSlot()).isEmpty(), "workstation has a real result before clicking");
        var event = new InventoryClickEvent(station.view(), InventoryType.SlotType.RESULT, station.resultSlot(), click, action, button);
        event.setCancelled(cancel); Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) invoke(station.menu(), "clicked", station.resultSlot(), button, enumValue("net.minecraft.world.inventory.ContainerInput", nativeClick), station.handle());
        if (finish) settle(station);
    }
    void settle(Station station) { Bukkit.getPluginManager().callEvent(new InventoryCloseEvent(station.view())); }
    void reward(Station station, String job, double xp, String money, String label) {
        var profile = jobs.profile(station.player());
        check(Math.abs(profile.xp(job) - xp) < 1e-9 && profile.pending.compareTo(new BigDecimal(money)) == 0,
                label + " (XP=" + profile.xp(job) + ", money=" + profile.pending + ")");
    }
    void finish(int phase) { getLogger().info("RELEASE CHECKS PHASE " + phase + " PASS: " + checks + " workstation checks"); Bukkit.shutdown(); }
}
