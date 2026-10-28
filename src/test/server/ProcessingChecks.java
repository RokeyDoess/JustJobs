package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Lightable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.CampfireStartEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs native processing and hopper transfers against real block inventories. */
public final class ProcessingChecks extends CraftingChecks {
    record Bench(Table actor, Block block, Object entity, Object menu, Inventory inventory, InventoryView view) { }
    ProcessingListener processing;
    int nextBlock;
    @EventHandler(priority = EventPriority.HIGHEST) public void quickFurnace(FurnaceStartSmeltEvent event) { event.setTotalCookTime(1); }
    @EventHandler(priority = EventPriority.HIGHEST) public void quickCampfire(CampfireStartEvent event) { event.setTotalCookTime(1); }

    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "processing build starts on Paper");
        world = Bukkit.getWorlds().getFirst();
        worker = (ExecutorService) field(jobs, "worker");
        processing = (ProcessingListener) field(jobs, "processing");
        check(jobs.registry().jobs().size() == 17, "seventeen jobs load with Cooking and Brewing");
        var marker = getDataFolder().toPath().resolve("processing.yml");
        if (Files.exists(marker)) { restart(marker); return; }
        var first = table(); var second = table();
        var a = jobs.profile(first.player()); var b = jobs.profile(second.player());
        var shared = bench(Material.FURNACE, first);
        put(shared, 0, new ItemStack(Material.RAW_IRON, 2));
        var otherView = open(shared.block(), second);
        put(otherView, 0, new ItemStack(Material.RAW_IRON, 1));
        check(a.xp("smithing") == 0 && b.xp("smithing") == 0, "loading ingredients gives no processing reward");
        cook(shared, 2); expect(a, "smithing", 2, "0.02", "the first batch keeps its original owner");
        expect(b, "smithing", 0, "0", "a top-up does not claim earlier inputs");
        cook(shared, 1); expect(b, "smithing", 1, "0.01", "the second player earns only for their top-up");
        var shifted = bench(Material.FURNACE, table()); var shiftProfile = jobs.profile(shifted.actor().player());
        shifted.actor().player().getInventory().setItem(0, new ItemStack(Material.RAW_IRON, 2));
        inputClick(shifted, shifted.inventory().getSize() + 27, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, "QUICK_MOVE", 0, false);
        cook(shifted, 2); expect(shiftProfile, "smithing", 2, "0.02", "shift-click loading records the actual inserted inputs");
        var limited = bench(Material.FURNACE, table()); var limitedProfile = jobs.profile(limited.actor().player());
        invoke(limited.menu(), "setCarried", nms(new ItemStack(Material.RAW_IRON)));
        var insertion = new InventoryClickEvent(limited.view(), InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);
        Bukkit.getPluginManager().callEvent(insertion);
        invoke(limited.menu(), "clicked", 0, 0, enumValue("net.minecraft.world.inventory.ContainerInput", "PICKUP"), limited.actor().handle());
        limited.inventory().setItem(0, new ItemStack(Material.RAW_IRON, 2)); processing.flush(); cook(limited, 2);
        expect(limitedProfile, "smithing", 1, "0.01", "unobserved extra inputs cannot exceed the player's inserted quantity");
        var dragged = bench(Material.FURNACE, table()); var dragProfile = jobs.profile(dragged.actor().player());
        var dragEvent = new InventoryDragEvent(dragged.view(), new ItemStack(Material.AIR), new ItemStack(Material.RAW_IRON, 2), false, Map.of(0, new ItemStack(Material.RAW_IRON, 2)));
        Bukkit.getPluginManager().callEvent(dragEvent); if (!dragEvent.isCancelled()) dragged.inventory().setItem(0, new ItemStack(Material.RAW_IRON, 2));
        processing.flush(); cook(dragged, 2); expect(dragProfile, "smithing", 2, "0.02", "drag loading records each inserted input");

        var automated = bench(Material.FURNACE, table()); var manual = jobs.profile(automated.actor().player());
        put(automated, 0, new ItemStack(Material.RAW_GOLD));
        hopper(automated.block(), org.bukkit.block.BlockFace.UP, Material.RAW_GOLD, 2);
        cook(automated, 3); expect(manual, "smithing", 1, "0.01", "real hopper inputs never inherit a manually loaded batch's owner");
        var collected = bench(Material.FURNACE, table()); var collectedProfile = jobs.profile(collected.actor().player());
        put(collected, 0, new ItemStack(Material.RAW_IRON));
        nativeCook(collected, 1);
        var outputHopper = collected.block().getRelative(org.bukkit.block.BlockFace.DOWN); outputHopper.setType(Material.HOPPER, false);
        tickHopper(outputHopper); processing.flush();
        expect(collectedProfile, "smithing", 1, "0.01", "hopper output collection preserves the input owner's reward");
        check(((Hopper) outputHopper.getState()).getInventory().contains(Material.IRON_INGOT), "the real output hopper receives the smelted ingot");

        var withdrawn = bench(Material.FURNACE, table()); var withdrawA = jobs.profile(withdrawn.actor().player());
        var withdrawBActor = table(); var withdrawB = jobs.profile(withdrawBActor.player());
        put(withdrawn, 0, new ItemStack(Material.RAW_IRON, 3)); var withdrawView = open(withdrawn.block(), withdrawBActor);
        put(withdrawView, 0, new ItemStack(Material.RAW_IRON, 3));
        inputClick(withdrawView, 0, ClickType.RIGHT, InventoryAction.PICKUP_HALF, "PICKUP", 1, false);
        cook(withdrawn, 3);
        expect(withdrawA, "smithing", 3, "0.03", "withdrawing inputs removes the latest top-up first");
        expect(withdrawB, "smithing", 0, "0", "withdrawn inputs do not receive a reward");
        var swapped = bench(Material.FURNACE, table()); var swapA = jobs.profile(swapped.actor().player());
        put(swapped, 0, new ItemStack(Material.RAW_IRON, 2)); var swapActor = table(); var swapB = jobs.profile(swapActor.player());
        var swapView = open(swapped.block(), swapActor); swapActor.player().getInventory().setItem(0, new ItemStack(Material.RAW_IRON, 2));
        inputClick(swapView, 0, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, "SWAP", 0, false);
        cook(swapped, 2); expect(swapA, "smithing", 0, "0", "identical hotbar swaps replace the old attribution");
        expect(swapB, "smithing", 2, "0.02", "the player who actually inserts the replacement stack earns");

        var cancelled = bench(Material.FURNACE, table()); var cancelledProfile = jobs.profile(cancelled.actor().player());
        put(cancelled, 0, new ItemStack(Material.RAW_IRON), true);
        check(cancelled.inventory().getItem(0) == null || cancelled.inventory().getItem(0).isEmpty(), "cancelled insertion leaves the furnace empty");
        cancelled.inventory().setItem(0, new ItemStack(Material.RAW_IRON)); cook(cancelled, 1);
        expect(cancelledProfile, "smithing", 0, "0", "cancelled insertion cannot label later unowned items");
        var creative = bench(Material.FURNACE, table()); var creativeProfile = jobs.profile(creative.actor().player());
        creative.actor().player().setGameMode(GameMode.CREATIVE); put(creative, 0, new ItemStack(Material.RAW_IRON));
        creative.actor().player().setGameMode(GameMode.SURVIVAL); cook(creative, 1);
        expect(creativeProfile, "smithing", 0, "0", "creative-loaded inputs remain uncredited after returning to survival");
        var replaced = bench(Material.FURNACE, table()); var replacedProfile = jobs.profile(replaced.actor().player());
        put(replaced, 0, new ItemStack(Material.RAW_IRON));
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(replaced.block(), replaced.actor().player()));
        replaced.block().setType(Material.AIR, false); replaced.block().setType(Material.FURNACE, false);
        replaced = open(replaced.block(), replaced.actor()); replaced.inventory().setItem(0, new ItemStack(Material.RAW_IRON)); cook(replaced, 1);
        expect(replacedProfile, "smithing", 0, "0", "replacing a furnace cannot inherit its old input attribution");
        var changed = bench(Material.FURNACE, table()); var changedProfile = jobs.profile(changed.actor().player());
        put(changed, 0, new ItemStack(Material.RAW_IRON)); changed.inventory().setItem(0, new ItemStack(Material.RAW_GOLD)); cook(changed, 1);
        changed.inventory().setItem(2, null); changed.inventory().setItem(0, new ItemStack(Material.RAW_IRON)); cook(changed, 1);
        expect(changedProfile, "smithing", 0, "0", "unobserved replacement clears stale claims instead of reviving them later");
        var ambiguous = bench(Material.FURNACE, table()); var ambiguousProfile = jobs.profile(ambiguous.actor().player());
        put(ambiguous, 0, new ItemStack(Material.RAW_IRON)); nativeCook(ambiguous, 1);
        ambiguous.inventory().setItem(0, new ItemStack(Material.RAW_IRON)); processing.flush();
        cook(ambiguous, 1); expect(ambiguousProfile, "smithing", 0, "0", "ambiguous unobserved changes cannot carry a claim into a later completion");
        var full = bench(Material.FURNACE, table()); var fullProfile = jobs.profile(full.actor().player());
        put(full, 0, new ItemStack(Material.RAW_IRON)); full.inventory().setItem(2, new ItemStack(Material.IRON_INGOT, 64));
        nativeCook(full, 3); processing.flush(); expect(fullProfile, "smithing", 0, "0", "a blocked output never earns processing rewards");
        var protectedBench = bench(Material.FURNACE, table()); var protectedProfile = jobs.profile(protectedBench.actor().player());
        put(protectedBench, 0, new ItemStack(Material.RAW_IRON));
        Listener protection = new Listener() { @EventHandler(priority = EventPriority.HIGHEST) public void deny(BlockCookEvent event) { event.setCancelled(true); } };
        Bukkit.getPluginManager().registerEvents(protection, this);
        try { nativeCook(protectedBench, 3); processing.flush(); } finally { HandlerList.unregisterAll(protection); }
        expect(protectedProfile, "smithing", 0, "0", "cancelled smelting gives no reward");
        cook(protectedBench, 1); expect(protectedProfile, "smithing", 1, "0.01", "a later successful retry consumes the claim once");

        for (var type : List.of(Material.FURNACE, Material.SMOKER)) {
            var food = bench(type, table()); var foodProfile = jobs.profile(food.actor().player());
            put(food, 0, new ItemStack(Material.BEEF, 2)); cook(food, 2);
            expect(foodProfile, "cooking", 2, "0.01", "food processing pays Cooking in " + type);
            check(foodProfile.xp("smithing") == 0, "cooked food cannot also receive an ingot-smelting reward");
        }
        var blast = bench(Material.BLAST_FURNACE, table()); var blastProfile = jobs.profile(blast.actor().player());
        put(blast, 0, new ItemStack(Material.RAW_COPPER)); cook(blast, 1);
        expect(blastProfile, "smithing", 1, "0.01", "blast furnaces share the same ingot rate");
        campfireChecks(); brewingChecks(); foodCrafts(); layoutChecks();

        var savedActor = table(); var savedProfile = jobs.profile(savedActor.player());
        var savedFurnace = bench(Material.FURNACE, savedActor); put(savedFurnace, 0, new ItemStack(Material.RAW_COPPER));
        var savedBrew = bench(Material.BREWING_STAND, savedActor); put(savedBrew, 3, new ItemStack(Material.NETHER_WART));
        var savedCamp = newBlock(Material.CAMPFIRE); placeFood(savedActor, savedCamp);
        var light = (Lightable) savedCamp.getBlockData(); light.setLit(false); savedCamp.setBlockData(light, false);
        processing.flush();
        var saved = new YamlConfiguration(); saved.set("owner", savedProfile.id.toString());
        saved.set("furnace", savedFurnace.block().getX()); saved.set("brewer", savedBrew.block().getX()); saved.set("campfire", savedCamp.getX());
        jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS); world.save();
        Files.createDirectories(getDataFolder().toPath()); saved.save(marker.toFile()); finish(1);
    }

    Bench bench(Material type, Table actor) throws Exception { return open(newBlock(type), actor); }
    Block newBlock(Material type) {
        int x = 128 + nextBlock++ * 3; var block = world.getBlockAt(x, 2, 128); block.getChunk().addPluginChunkTicket(this); block.setType(type, false); return block;
    }
    Bench open(Block block, Table actor) throws Exception {
        var entity = invoke(invoke(world, "getHandle"), "getBlockEntity", pos(block));
        var menu = invoke(entity, "createMenu", 3, invoke(actor.handle(), "getInventory"), actor.handle());
        setField(actor.handle(), "containerMenu", menu); var view = (InventoryView) invoke(menu, "getBukkitView");
        return new Bench(actor, block, entity, menu, view.getTopInventory(), view);
    }
    Object pos(Block block) throws Exception { return construct("net.minecraft.core.BlockPos", block.getX(), block.getY(), block.getZ()); }
    void put(Bench bench, int slot, ItemStack item) throws Exception { put(bench, slot, item, false); }
    void put(Bench bench, int slot, ItemStack item, boolean cancelled) throws Exception {
        invoke(bench.menu(), "setCarried", nms(item));
        inputClick(bench, slot, ClickType.LEFT, InventoryAction.PLACE_ALL, "PICKUP", 0, cancelled);
    }
    void inputClick(Bench bench, int slot, ClickType click, InventoryAction action, String nativeClick, int button, boolean cancelled) throws Exception {
        var event = new InventoryClickEvent(bench.view(), InventoryType.SlotType.CONTAINER, slot, click, action, button);
        event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) invoke(bench.menu(), "clicked", slot, button, enumValue("net.minecraft.world.inventory.ContainerInput", nativeClick), bench.actor().handle());
        processing.flush();
    }
    void nativeCook(Bench bench, int ticks) throws Exception {
        var furnace = (Furnace) bench.block().getState(false);
        if (countItem(furnace.getInventory().getFuel()) == 0) furnace.getInventory().setFuel(new ItemStack(Material.COAL));
        for (int i = 0; i < ticks; i++) invoke(Class.forName("net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity"), "serverTick",
                invoke(world, "getHandle"), pos(bench.block()), invoke(invoke(world, "getHandle"), "getBlockState", pos(bench.block())), bench.entity());
    }
    void cook(Bench bench, int amount) throws Exception {
        int before = countItem(bench.inventory().getItem(0));
        for (int i = 0; i < amount * 4 + 4 && countItem(bench.inventory().getItem(0)) > before - amount; i++) nativeCook(bench, 1);
        processing.flush(); check(countItem(bench.inventory().getItem(0)) == before - amount, "native cooking consumes exactly the requested raw items");
    }
    void hopper(Block destination, org.bukkit.block.BlockFace side, Material material, int amount) throws Exception {
        var hopper = destination.getRelative(side); hopper.setType(Material.HOPPER, false);
        var direction = (Directional) hopper.getBlockData(); direction.setFacing(side == org.bukkit.block.BlockFace.UP ? org.bukkit.block.BlockFace.DOWN : side.getOppositeFace()); hopper.setBlockData(direction, false);
        var inventory = ((Hopper) hopper.getState()).getInventory();
        inventory.setItem(0, material == Material.POTION ? water() : new ItemStack(material, amount));
        for (int i = 0; i < amount; i++) { if (material == Material.POTION) inventory.setItem(0, water()); tickHopper(hopper); }
        processing.flush();
    }
    void tickHopper(Block block) throws Exception {
        var level = invoke(world, "getHandle"); var entity = invoke(level, "getBlockEntity", pos(block));
        setField(entity, "cooldownTime", 0);
        invoke(Class.forName("net.minecraft.world.level.block.entity.HopperBlockEntity"), "pushItemsTick", level, pos(block), invoke(level, "getBlockState", pos(block)), entity);
    }
    ItemStack water() { var item = new ItemStack(Material.POTION); var meta = (PotionMeta) item.getItemMeta(); meta.setBasePotionType(PotionType.WATER); item.setItemMeta(meta); return item; }
    void brew(Bench bench) throws Exception { brew(bench, true); }
    void brew(Bench bench, boolean settle) throws Exception {
        var level = invoke(world, "getHandle"); var state = invoke(level, "getBlockState", pos(bench.block()));
        var stand = (BrewingStand) bench.block().getState(false); stand.setFuelLevel(20); stand.update(false, false);
        invoke(Class.forName("net.minecraft.world.level.block.entity.BrewingStandBlockEntity"), "serverTick", level, pos(bench.block()), state, bench.entity());
        stand = (BrewingStand) bench.block().getState(false); stand.setBrewingTime(1); stand.update(false, false);
        invoke(Class.forName("net.minecraft.world.level.block.entity.BrewingStandBlockEntity"), "serverTick", level, pos(bench.block()), state, bench.entity());
        if (settle) processing.flush();
    }
    void bottles(Bench bench, int count) { for (int i = 0; i < 3; i++) bench.inventory().setItem(i, i < count ? water() : null); }
    void brewingChecks() throws Exception {
        var actor = table(); var profile = jobs.profile(actor.player()); var stand = bench(Material.BREWING_STAND, actor);
        put(stand, 3, new ItemStack(Material.NETHER_WART)); hopper(stand.block(), org.bukkit.block.BlockFace.WEST, Material.POTION, 3); brew(stand);
        expect(profile, "brewing", 3, "0.025", "three hopper-supplied bottles still give one reward for the manually loaded ingredient");
        var other = table(); var otherProfile = jobs.profile(other.player()); var otherView = open(stand.block(), other);
        put(otherView, 3, new ItemStack(Material.SUGAR)); brew(stand);
        expect(otherProfile, "brewing", 3, "0.025", "the next potion ingredient credits its own inserter");
        put(stand, 3, new ItemStack(Material.REDSTONE)); brew(stand);
        expect(profile, "brewing", 6, "0.05", "extending potions earns an ingredient reward");
        put(stand, 3, new ItemStack(Material.GUNPOWDER)); brew(stand);
        expect(profile, "brewing", 9, "0.075", "making splash potions counts once per gunpowder");
        put(stand, 3, new ItemStack(Material.DRAGON_BREATH)); brew(stand);
        expect(profile, "brewing", 19, "0.175", "lingering upgrades count the consumed breath even when its bottle is returned");
        var single = bench(Material.BREWING_STAND, table()); var singleProfile = jobs.profile(single.actor().player());
        bottles(single, 1); put(single, 3, new ItemStack(Material.NETHER_WART)); brew(single);
        expect(singleProfile, "brewing", 3, "0.025", "one bottle pays the same ingredient rate as three");
        var collected = bench(Material.BREWING_STAND, table()); var collectorProfile = jobs.profile(collected.actor().player());
        bottles(collected, 1); put(collected, 3, new ItemStack(Material.NETHER_WART)); brew(collected, false);
        var output = collected.block().getRelative(org.bukkit.block.BlockFace.DOWN); output.setType(Material.HOPPER, false); tickHopper(output); processing.flush();
        expect(collectorProfile, "brewing", 3, "0.025", "hopper collection cannot erase a completed ingredient reward");
        check(((Hopper) output.getState()).getInventory().contains(Material.POTION), "the output hopper really collects the brewed potion");
        var batchActor = table(); var laterActor = table(); var batchProfile = jobs.profile(batchActor.player()); var laterProfile = jobs.profile(laterActor.player());
        var batches = bench(Material.BREWING_STAND, batchActor); put(batches, 3, new ItemStack(Material.NETHER_WART, 2));
        put(open(batches.block(), laterActor), 3, new ItemStack(Material.NETHER_WART));
        for (int i = 0; i < 3; i++) { bottles(batches, 3); brew(batches); }
        expect(batchProfile, "brewing", 6, "0.05", "brewing preserves the first player's ingredient quantities across batches");
        expect(laterProfile, "brewing", 3, "0.025", "a brewing top-up earns only for its own consumed ingredient");
        var auto = bench(Material.BREWING_STAND, table()); var autoProfile = jobs.profile(auto.actor().player());
        bottles(auto, 3); put(auto, 4, new ItemStack(Material.BLAZE_POWDER)); hopper(auto.block(), org.bukkit.block.BlockFace.UP, Material.NETHER_WART, 1); brew(auto);
        expect(autoProfile, "brewing", 0, "0", "manual fuel cannot claim a hopper-fed potion ingredient");
        var protectedStand = bench(Material.BREWING_STAND, table()); var protectedProfile = jobs.profile(protectedStand.actor().player());
        bottles(protectedStand, 3); put(protectedStand, 3, new ItemStack(Material.NETHER_WART));
        Listener protection = new Listener() { @EventHandler(priority = EventPriority.HIGHEST) public void deny(BrewEvent event) { event.setCancelled(true); } };
        Bukkit.getPluginManager().registerEvents(protection, this);
        try { brew(protectedStand); } finally { HandlerList.unregisterAll(protection); }
        expect(protectedProfile, "brewing", 0, "0", "cancelled brewing never consumes a credited claim");
        brew(protectedStand); expect(protectedProfile, "brewing", 3, "0.025", "a successful brewing retry rewards only once");
    }
    void placeFood(Table actor, Block block) throws Exception {
        actor.player().getInventory().setItemInMainHand(new ItemStack(Material.BEEF));
        var event = new PlayerInteractEvent(actor.player(), org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                actor.player().getInventory().getItemInMainHand(), block, org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            var level = invoke(world, "getHandle"); var state = invoke(level, "getBlockState", pos(block));
            var hit = construct("net.minecraft.world.phys.BlockHitResult", construct("net.minecraft.world.phys.Vec3", block.getX() + .5, block.getY() + .5, block.getZ() + .5), enumValue("net.minecraft.core.Direction", "UP"), pos(block), false);
            invoke(state, "useItemOn", invoke(actor.handle(), "getMainHandItem"), level, actor.handle(), enumValue("net.minecraft.world.InteractionHand", "MAIN_HAND"), hit);
        }
        processing.flush();
    }
    void cookCamp(Block block) throws Exception {
        var level = invoke(world, "getHandle"); var entity = invoke(level, "getBlockEntity", pos(block));
        var cache = invoke(Class.forName("net.minecraft.world.item.crafting.RecipeManager"), "createCheck", Class.forName("net.minecraft.world.item.crafting.RecipeType").getField("CAMPFIRE_COOKING").get(null));
        invoke(Class.forName("net.minecraft.world.level.block.entity.CampfireBlockEntity"), "cookTick", level, pos(block), invoke(level, "getBlockState", pos(block)), entity, cache);
        processing.flush();
    }
    void campfireChecks() throws Exception {
        var campfire = newBlock(Material.CAMPFIRE); var first = table(); var second = table();
        var a = jobs.profile(first.player()); var b = jobs.profile(second.player());
        placeFood(first, campfire); placeFood(first, campfire); placeFood(second, campfire); placeFood(second, campfire);
        cookCamp(campfire); expect(a, "cooking", 2, "0.01", "campfire slots keep the first player's food attribution");
        expect(b, "cooking", 2, "0.01", "campfire slots keep the second player's food attribution");
    }
    void foodCrafts() throws Exception {
        var bread = table(); bread.grid().setMatrix(new ItemStack[]{new ItemStack(Material.WHEAT), new ItemStack(Material.WHEAT), new ItemStack(Material.WHEAT), null, null, null, null, null, null});
        click(bread, ClickType.LEFT, "PICKUP", 0, false);
        reward(bread, "cooking", 1, "0.01", "food crafting contributes to Cooking");
        var cookies = table(); cookies.grid().setMatrix(new ItemStack[]{new ItemStack(Material.WHEAT, 2), new ItemStack(Material.COCOA_BEANS, 2), new ItemStack(Material.WHEAT, 2), null, null, null, null, null, null});
        click(cookies, ClickType.SHIFT_LEFT, "QUICK_MOVE", 0, false);
        reward(cookies, "cooking", 4, "0.04", "bulk food crafting counts the sixteen actual cookies");
    }
    @SuppressWarnings("unchecked") void layoutChecks() throws Exception {
        var file = jobs.getDataFolder().toPath().resolve("jobs.yml"); var original = Files.readString(file);
        try {
            var config = YamlConfiguration.loadConfiguration(file.toFile());
            for (int i = 0; i < 12; i++) config.set("jobs.extra_" + i + ".rules", List.of(Map.of("activity", "craft", "materials", List.of("FLOWER_POT"), "xp", 1, "money", "0")));
            config.save(file.toFile()); jobs.loadSettings();
            player = player(MAIN, "LayoutChecks"); var menus = new JobsMenu(jobs); menus.open(player, false, 0, null);
            check(((Map<Integer, String>) field(current.getHolder(), "jobSlots")).size() == 27, "a jobs page uses all twenty-seven content slots");
            check(java.util.stream.IntStream.rangeClosed(9, 35).allMatch(slot -> current.getItem(slot) != null && current.getItem(slot).getType() != Material.GRAY_STAINED_GLASS_PANE), "overflow jobs occupy the newly opened side slots");
            check(current.getItem(0).getType() == Material.GRAY_STAINED_GLASS_PANE && current.getItem(8).getType() == Material.GRAY_STAINED_GLASS_PANE
                            && current.getItem(36).getType() == Material.COMPARATOR && current.getItem(44).getType() == Material.OAK_DOOR,
                    "top and bottom borders retain their corners, settings and close controls");
            menus.open(player, false, 1, null);
            check(((Map<Integer, String>) field(current.getHolder(), "jobSlots")).size() == 2 && current.getItem(18) == null && current.getItem(26) == null,
                    "the next jobs page holds the remainder without side decorations");
            menus.open(player, true, 0, null);
            check(current.getItem(0).getType() == Material.PURPLE_STAINED_GLASS_PANE && current.getItem(36).getType() == Material.COMPARATOR,
                    "leaderboards keep their purple top and bottom borders and existing controls");
            var drag = new InventoryDragEvent(view(), new ItemStack(Material.DIRT), new ItemStack(Material.DIRT), false, Map.of(9, new ItemStack(Material.DIRT)));
            menus.drag(drag); check(drag.isCancelled(), "newly opened content slots remain protected menu items");
        } finally { Files.writeString(file, original); jobs.loadSettings(); }
    }
    void restart(java.nio.file.Path marker) throws Exception {
        var saved = YamlConfiguration.loadConfiguration(marker.toFile()); var profile = jobs.players().get(UUID.fromString(saved.getString("owner")));
        check(profile != null, "the batch owner loads without being online");
        double smithing = profile.xp("smithing"), brewing = profile.xp("brewing"), cooking = profile.xp("cooking"); var money = profile.pending;
        nextPlayer = 500; var actor = table();
        var furnaceBlock = world.getBlockAt(saved.getInt("furnace"), 2, 128); furnaceBlock.getChunk().addPluginChunkTicket(this);
        var furnace = open(furnaceBlock, actor); cook(furnace, 1);
        var brewerBlock = world.getBlockAt(saved.getInt("brewer"), 2, 128); brewerBlock.getChunk().addPluginChunkTicket(this);
        var brewer = open(brewerBlock, actor); bottles(brewer, 3); brew(brewer);
        var campfire = world.getBlockAt(saved.getInt("campfire"), 2, 128); campfire.getChunk().addPluginChunkTicket(this);
        var lit = (Lightable) campfire.getBlockData(); lit.setLit(true); campfire.setBlockData(lit, false);
        cookCamp(campfire);
        check(profile.xp("smithing") == smithing + 1 && profile.xp("brewing") == brewing + 3 && profile.xp("cooking") == cooking + 1,
                "furnace, brewing and campfire batches retain their original owner through restart");
        check(profile.pending.subtract(money).compareTo(new BigDecimal("0.04")) == 0, "offline completions use the normal saved earnings and payday ledger");
        finish(2);
    }
    void expect(PlayerProfile profile, String job, double xp, String money, String label) {
        check(Math.abs(profile.xp(job) - xp) < 1e-9 && profile.pending.compareTo(new BigDecimal(money)) == 0,
                label + " (XP=" + profile.xp(job) + ", money=" + profile.pending + ")");
    }
    static int countItem(ItemStack item) { return item == null || item.isEmpty() ? 0 : item.getAmount(); }
    void finish(int phase) { getLogger().info("RELEASE CHECKS PHASE " + phase + " PASS: " + checks + " processing checks"); Bukkit.shutdown(); }
}
