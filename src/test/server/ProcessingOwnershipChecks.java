package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Exercises block ownership, direct contributors, and automated processing on real workstations. */
public final class ProcessingOwnershipChecks extends ProcessingChecks {
    NamespacedKey ownerKey, inputKey;

    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "hybrid processing starts on Paper");
        world = Bukkit.getWorlds().getFirst(); worker = (ExecutorService) field(jobs, "worker");
        processing = (ProcessingListener) field(jobs, "processing");
        ownerKey = new NamespacedKey(jobs, "processing_owner"); inputKey = new NamespacedKey(jobs, "processing_inputs_v1");
        var marker = getDataFolder().toPath().resolve("ownership.yml");
        if (Files.exists(marker)) { restartOwnership(marker); return; }
        placementAndClaims(); mixedInputs(); blockedInputs(); brewingOwnership(); lifecycle(); caps(); legacyBatches(); saveOwnership(marker);
    }

    void place(Block block, Table actor) { placement(block, actor, false, true); }
    void placement(Block block, Table actor, boolean cancelled, boolean canBuild) {
        var event = new BlockPlaceEvent(block, block.getState(), block.getRelative(BlockFace.DOWN), new ItemStack(block.getType()), actor.player(), canBuild, EquipmentSlot.HAND);
        event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event);
    }
    boolean openEvent(Bench bench, boolean cancel) {
        var event = new InventoryOpenEvent(bench.view()); event.setCancelled(cancel); Bukkit.getPluginManager().callEvent(event); return !event.isCancelled();
    }
    UUID owner(Block block) {
        String text = ((TileState) block.getState(false)).getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return text == null ? null : UUID.fromString(text);
    }
    boolean attributed(Block block) { return ((TileState) block.getState(false)).getPersistentDataContainer().has(inputKey); }
    Bench owned(Material material, Table actor) throws Exception { var bench = bench(material, actor); place(bench.block(), actor); return bench; }
    void earned(Table actor, String job, double xp, String money, String label) { expect(jobs.profile(actor.player()), job, xp, money, label); }

    void placementAndClaims() throws Exception {
        for (var type : List.of(Material.FURNACE, Material.SMOKER, Material.BLAST_FURNACE, Material.BREWING_STAND, Material.CAMPFIRE, Material.SOUL_CAMPFIRE)) {
            var placer = table(); var block = newBlock(type); place(block, placer);
            check(placer.player().getUniqueId().equals(owner(block)), "placement assigns the owner of " + type);
        }
        var original = table(); var visitor = table(); var owned = owned(Material.FURNACE, original);
        openEvent(open(owned.block(), visitor), false);
        check(original.player().getUniqueId().equals(owner(owned.block())), "the first visitor cannot replace the recorded placer");
        var existing = bench(Material.SMOKER, original);
        openEvent(existing, true); check(owner(existing.block()) == null, "cancelled opening cannot claim an old workstation");
        var creative = table(); creative.player().setGameMode(GameMode.CREATIVE); openEvent(open(existing.block(), creative), false);
        check(owner(existing.block()) == null, "an ineligible visitor cannot claim an old workstation");
        openEvent(existing, false); openEvent(open(existing.block(), visitor), false);
        check(original.player().getUniqueId().equals(owner(existing.block())), "first eligible opening claims an unowned workstation once");
        var protectedBlock = newBlock(Material.BLAST_FURNACE);
        placement(protectedBlock, original, true, true); check(owner(protectedBlock) == null, "cancelled placement cannot assign ownership");
        placement(protectedBlock, original, false, false); check(owner(protectedBlock) == null, "denied building cannot assign ownership");
        var creativePlacer = table(); creativePlacer.player().setGameMode(GameMode.CREATIVE);
        var creativeOwned = owned(Material.FURNACE, creativePlacer);
        check(creativePlacer.player().getUniqueId().equals(owner(creativeOwned.block())), "placement records its actual placer even in creative mode");
        hopper(creativeOwned.block(), BlockFace.UP, Material.RAW_IRON, 1); cook(creativeOwned, 1);
        earned(creativePlacer, "smithing", 0, "0", "a last-known ineligible offline owner does not earn automatically");
        openEvent(open(creativeOwned.block(), visitor), false);
        check(creativePlacer.player().getUniqueId().equals(owner(creativeOwned.block())), "an eligible visitor cannot take an ineligible placer's block");
        creativePlacer.player().setGameMode(GameMode.SURVIVAL); openEvent(creativeOwned, false);
        hopper(creativeOwned.block(), BlockFace.UP, Material.RAW_IRON, 1); cook(creativeOwned, 1);
        earned(creativePlacer, "smithing", 1, "0.01", "the owner's eligible opening refreshes offline earning eligibility");
        var unopened = bench(Material.FURNACE, table()); hopper(unopened.block(), BlockFace.UP, Material.RAW_IRON, 1); cook(unopened, 1);
        check(owner(unopened.block()) == null, "hoppers and processing alone do not claim an unowned workstation");
        earned(unopened.actor(), "smithing", 0, "0", "unowned automatic processing earns nothing");
    }
    void mixedInputs() throws Exception {
        var operator = table(); var contributor = table(); var third = table(); var furnace = owned(Material.FURNACE, operator);
        var direct = open(furnace.block(), contributor); put(direct, 0, new ItemStack(Material.RAW_IRON, 2));
        hopper(furnace.block(), BlockFace.UP, Material.RAW_IRON, 2);
        put(open(furnace.block(), third), 0, new ItemStack(Material.RAW_IRON, 1)); cook(furnace, 5);
        earned(contributor, "smithing", 2, "0.02", "manual ingredients pay their contributor even while offline");
        earned(operator, "smithing", 2, "0.02", "only unassigned hopper inputs pay the block owner");
        earned(third, "smithing", 1, "0.01", "a third contributor keeps their own batch credit");
        check(!attributed(furnace.block()), "completed contributor queues are cleared without losing block ownership");
        hopper(furnace.block(), BlockFace.UP, Material.RAW_IRON, 3);
        check(!attributed(furnace.block()), "pure hopper input does not create per-item attribution data");
        cook(furnace, 3); earned(operator, "smithing", 5, "0.05", "an owner-only furnace continues paying without a contributor ledger");
        var lateOwner = table(); var oldContributor = table(); var old = bench(Material.FURNACE, oldContributor);
        put(old, 0, new ItemStack(Material.RAW_IRON, 2));
        openEvent(open(old.block(), lateOwner), false); cook(old, 2);
        earned(oldContributor, "smithing", 2, "0.02", "claiming an old furnace preserves ingredients assigned before the claim");
        earned(lateOwner, "smithing", 0, "0", "the first opener cannot take preassigned contributor earnings");
        var withdrawals = table(); var first = table(); var last = table(); var shared = owned(Material.FURNACE, withdrawals);
        put(open(shared.block(), first), 0, new ItemStack(Material.RAW_IRON, 2)); hopper(shared.block(), BlockFace.UP, Material.RAW_IRON, 2);
        var lastView = open(shared.block(), last); put(lastView, 0, new ItemStack(Material.RAW_IRON, 2));
        inputClick(lastView, 0, org.bukkit.event.inventory.ClickType.RIGHT, org.bukkit.event.inventory.InventoryAction.PICKUP_HALF, "PICKUP", 1, false);
        cook(shared, 3); earned(first, "smithing", 2, "0.02", "withdrawals preserve the earliest contributor's remaining quantity");
        earned(last, "smithing", 0, "0", "withdrawn contributor inputs do not pay");
        earned(withdrawals, "smithing", 1, "0.01", "withdrawals also remove the corresponding unassigned quantity");
    }
    void blockedInputs() throws Exception {
        var operator = table(); var creative = table(); var furnace = owned(Material.FURNACE, operator);
        creative.player().setGameMode(GameMode.CREATIVE); put(open(furnace.block(), creative), 0, new ItemStack(Material.RAW_IRON, 2));
        creative.player().setGameMode(GameMode.SURVIVAL); cook(furnace, 2);
        earned(operator, "smithing", 0, "0", "creative-inserted ingredients cannot pay the owner through fallback");
        earned(creative, "smithing", 0, "0", "returning to survival does not make creative inputs eligible");
        var attachment = creative.player().addAttachment(this, "justjobs.earn", false);
        put(open(furnace.block(), creative), 0, new ItemStack(Material.RAW_IRON)); creative.player().removeAttachment(attachment); cook(furnace, 1);
        earned(operator, "smithing", 0, "0", "permission-denied manual inputs cannot pay the owner either");
        var blocked = owned(Material.FURNACE, table());
        hopper(blocked.block(), BlockFace.UP, Material.RAW_IRON, 2); blocked.inventory().setItem(2, new ItemStack(Material.IRON_INGOT, 64)); nativeCook(blocked, 4); processing.flush();
        earned(blocked.actor(), "smithing", 0, "0", "a full output never pays owner fallback");
        blocked.inventory().setItem(2, null);
        Listener protection = new Listener() { @EventHandler(priority = EventPriority.HIGHEST) public void deny(BlockCookEvent event) { event.setCancelled(true); } };
        Bukkit.getPluginManager().registerEvents(protection, this);
        try { nativeCook(blocked, 4); processing.flush(); } finally { HandlerList.unregisterAll(protection); }
        earned(blocked.actor(), "smithing", 0, "0", "cancelled processing never pays owner fallback");
        cook(blocked, 2); earned(blocked.actor(), "smithing", 2, "0.02", "a later successful completion pays the owner once");
    }
    void brewingOwnership() throws Exception {
        var operator = table(); var visitor = table(); var stand = owned(Material.BREWING_STAND, operator);
        hopper(stand.block(), BlockFace.WEST, Material.POTION, 3);
        put(open(stand.block(), visitor), 3, new ItemStack(Material.NETHER_WART)); brew(stand);
        earned(visitor, "brewing", 3, "0.025", "manual brewing ingredient pays the visitor regardless of bottle ownership");
        earned(operator, "brewing", 0, "0", "the brewing owner cannot take a visitor's assigned ingredient");
        hopper(stand.block(), BlockFace.UP, Material.SUGAR, 1); brew(stand);
        earned(operator, "brewing", 3, "0.025", "a hopper ingredient pays once to the brewing-stand owner");
        hopper(stand.block(), BlockFace.UP, Material.REDSTONE, 1); brew(stand);
        earned(operator, "brewing", 6, "0.05", "automated potion enhancements also pay once per ingredient");
        var legacy = bench(Material.BREWING_STAND, table()); var claimant = legacy.actor();
        bottles(legacy, 1); hopper(legacy.block(), BlockFace.UP, Material.NETHER_WART, 1); openEvent(legacy, false); brew(legacy);
        earned(claimant, "brewing", 3, "0.025", "first opening a legacy stand enables owner fallback for its existing ingredient");
    }
    void lifecycle() throws Exception {
        var oldOwner = table(); var replacementOwner = table(); var furnace = owned(Material.FURNACE, oldOwner);
        var cancelled = new BlockBreakEvent(furnace.block(), oldOwner.player()); cancelled.setCancelled(true); Bukkit.getPluginManager().callEvent(cancelled);
        check(oldOwner.player().getUniqueId().equals(owner(furnace.block())), "cancelled breaking leaves ownership intact");
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(furnace.block(), oldOwner.player()));
        check(owner(furnace.block()) == null, "breaking removes the old owner's metadata");
        furnace.block().setType(Material.AIR, false); furnace.block().setType(Material.FURNACE, false); place(furnace.block(), replacementOwner);
        furnace = open(furnace.block(), replacementOwner); hopper(furnace.block(), BlockFace.UP, Material.RAW_IRON, 1); cook(furnace, 1);
        earned(replacementOwner, "smithing", 1, "0.01", "a replacement furnace pays its new placer"); earned(oldOwner, "smithing", 0, "0", "replacement does not inherit the previous owner");
    }
    void caps() throws Exception {
        var operator = table(); var first = owned(Material.FURNACE, operator); var second = owned(Material.BLAST_FURNACE, operator);
        Object limit = jobs.settings().get("economy.hourly-income-limit-per-job");
        try {
            jobs.settings().set("economy.hourly-income-limit-per-job", "0.03");
            hopper(first.block(), BlockFace.UP, Material.RAW_IRON, 3); cook(first, 3);
            hopper(second.block(), BlockFace.UP, Material.RAW_COPPER, 3); cook(second, 3);
            earned(operator, "smithing", 6, "0.03", "all of an owner's machines share one job money cap while XP stays uncapped");
        } finally { jobs.settings().set("economy.hourly-income-limit-per-job", limit); }
    }
    void legacyBatches() throws Exception {
        var operator = table(); var contributor = table(); var furnace = owned(Material.FURNACE, operator);
        furnace.inventory().setItem(0, new ItemStack(Material.RAW_IRON, 2));
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            output.writeInt(1); output.writeUTF("FURNACE"); output.writeInt(1);
            byte[] item = new ItemStack(Material.RAW_IRON).serializeAsBytes(); output.writeInt(item.length); output.write(item); output.writeInt(2);
            output.writeBoolean(true); output.writeLong(contributor.player().getUniqueId().getMostSignificantBits()); output.writeLong(contributor.player().getUniqueId().getLeastSignificantBits()); output.writeInt(1);
            output.writeBoolean(false); output.writeInt(1); output.flush();
            jobs.profile(contributor.player()); var state = (TileState) furnace.block().getState(false);
            state.getPersistentDataContainer().set(inputKey, PersistentDataType.BYTE_ARRAY, bytes.toByteArray()); state.update(false, false);
        }
        cook(furnace, 2); earned(contributor, "smithing", 1, "0.01", "1.1.0 contributor batches retain their original credit");
        earned(operator, "smithing", 1, "0.01", "unassigned quantities in a legacy batch use owner fallback");
    }
    void saveOwnership(java.nio.file.Path marker) throws Exception {
        var operator = table(); var contributor = table(); var furnace = owned(Material.FURNACE, operator);
        put(open(furnace.block(), contributor), 0, new ItemStack(Material.RAW_IRON, 2)); hopper(furnace.block(), BlockFace.UP, Material.RAW_IRON, 1);
        var stand = owned(Material.BREWING_STAND, operator); bottles(stand, 1); hopper(stand.block(), BlockFace.UP, Material.NETHER_WART, 1);
        var creative = table(); var denied = owned(Material.FURNACE, operator); creative.player().setGameMode(GameMode.CREATIVE);
        put(open(denied.block(), creative), 0, new ItemStack(Material.RAW_IRON)); creative.player().setGameMode(GameMode.SURVIVAL);
        var campfire = newBlock(Material.CAMPFIRE); place(campfire, operator); placeFood(contributor, campfire);
        var camp = (Campfire) campfire.getState(false); camp.setItem(1, new ItemStack(Material.BEEF)); camp.setCookTimeTotal(1, 1); camp.update(false, false);
        var light = (org.bukkit.block.data.Lightable) campfire.getBlockData(); light.setLit(false); campfire.setBlockData(light, false);
        processing.flush();
        var saved = new YamlConfiguration(); saved.set("operator", operator.player().getUniqueId().toString()); saved.set("contributor", contributor.player().getUniqueId().toString());
        saved.set("furnace", furnace.block().getX()); saved.set("stand", stand.block().getX()); saved.set("denied", denied.block().getX()); saved.set("campfire", campfire.getX());
        jobs.save(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS); world.save(); Files.createDirectories(getDataFolder().toPath()); saved.save(marker.toFile()); finishOwnership(1);
    }
    void restartOwnership(java.nio.file.Path marker) throws Exception {
        var saved = YamlConfiguration.loadConfiguration(marker.toFile()); nextPlayer = 1000; var actor = table();
        var operator = jobs.players().get(UUID.fromString(saved.getString("operator"))); var contributor = jobs.players().get(UUID.fromString(saved.getString("contributor")));
        check(operator != null && contributor != null, "operator and contributor profiles load while both are offline");
        var furnaceBlock = world.getBlockAt(saved.getInt("furnace"), 2, 128); check(operator.id.equals(owner(furnaceBlock)), "block ownership survives a world save and restart");
        cook(open(furnaceBlock, actor), 3); expect(operator, "smithing", 1, "0.01", "offline owner receives only the restarted unassigned ingot");
        expect(contributor, "smithing", 2, "0.02", "offline contributor retains the restarted manual batch");
        brew(open(world.getBlockAt(saved.getInt("stand"), 2, 128), actor));
        check(operator.xp("brewing") == 3, "a restarted hopper-fed brewing batch pays the offline block owner");
        cook(open(world.getBlockAt(saved.getInt("denied"), 2, 128), actor), 1);
        check(operator.xp("smithing") == 1, "blocked manual inputs remain blocked after restart");
        cookCamp(world.getBlockAt(saved.getInt("campfire"), 2, 128));
        check(operator.xp("cooking") == 1 && contributor.xp("cooking") == 1, "campfire contributor priority and owner fallback survive restart");
        openEvent(open(furnaceBlock, actor), false); check(operator.id.equals(owner(furnaceBlock)), "a visitor cannot take an owner's workstation after restart");
        finishOwnership(2);
    }
    void finishOwnership(int phase) { getLogger().info("RELEASE CHECKS PHASE " + phase + " PASS: " + checks + " ownership checks"); Bukkit.shutdown(); }
}
