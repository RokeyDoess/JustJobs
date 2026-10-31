package io.github.rokeydoess.justjobs;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record JobRegistry(List<Job> jobs) {
    public enum Activity {
        BLOCK_BREAK, BLOCK_PLACE, CRAFT, CROP_HARVEST, MOB_KILL, BREED, FISH, PLAYER_KILL, TRADE, TRAVEL, SAIL, ENCHANT,
        SHEAR, BEEHIVE_HARVEST, TAME, STONECUT, LOOM, BRUSH, SMITH, ANVIL_REPAIR, CURE_VILLAGER, CARTOGRAPHY, BIOME_DISCOVER,
        SMELT, COOK, BREW, BOSS_KILL;
        static Activity parse(String text) { return valueOf(text.toUpperCase(Locale.ROOT).replace('-', '_')); }
        boolean usesMaterials() { return switch (this) {
            case BLOCK_BREAK, BLOCK_PLACE, CRAFT, CROP_HARVEST, FISH, BEEHIVE_HARVEST, STONECUT, LOOM, BRUSH, SMITH, ANVIL_REPAIR, CARTOGRAPHY, SMELT, COOK, BREW -> true;
            default -> false;
        }; }
        boolean usesEntities() { return this == MOB_KILL || this == BOSS_KILL || this == BREED || this == SHEAR || this == TAME || this == CURE_VILLAGER; }
        boolean usesItems() { return usesMaterials() && this != BLOCK_BREAK && this != BLOCK_PLACE && this != CROP_HARVEST && this != BRUSH; }
    }
    public record Rule(Activity activity, Set<Material> materials, Set<EntityType> entities,
                       boolean any, double xp, BigDecimal money, String description, Set<String> biomes, Set<String> operations, boolean bonus) {
        public Rule(Activity activity, Set<Material> materials, Set<EntityType> entities, boolean any, double xp, BigDecimal money,
                    String description, Set<String> biomes, Set<String> operations) {
            this(activity, materials, entities, any, xp, money, description, biomes, operations, false);
        }
        public boolean matches(Activity type, Material material, EntityType entity) {
            return matches(type, material, entity, null);
        }
        public boolean matches(Activity type, Material material, EntityType entity, String detail) {
            if (activity != type || !operations.isEmpty() && (detail == null || !operations.contains(detail))) return false;
            if (activity == Activity.BIOME_DISCOVER) return any || detail != null && biomes.contains(detail);
            return activity == type && (any || (material != null && materials.contains(material))
                    || (entity != null && entities.contains(entity)));
        }
    }
    public record Job(String id, String name, String description, Material icon, BossBar.Color color, boolean naturalOnly,
                      BigDecimal hourlyLimit, double distancePerReward, long bossBarCooldownSeconds, Progression progression, List<Rule> rules) { }

    public Job get(String id) { return jobs.stream().filter(j -> j.id.equals(id)).findFirst().orElse(null); }
    public boolean rewards(Activity activity, Material material, EntityType entity) {
        return rewards(activity, material, entity, null);
    }
    public boolean rewards(Activity activity, Material material, EntityType entity, String detail) {
        return jobs.stream().flatMap(j -> j.rules().stream())
                .anyMatch(r -> r.matches(activity, material, entity, detail) && (r.xp() > 0 || r.money().signum() > 0));
    }

    public record Loaded(JobRegistry registry, YamlConfiguration configuration, boolean upgraded) { }
    public static JobRegistry load(File file, Progression defaults) throws Exception {
        var loaded = prepare(file, defaults);
        if (loaded.upgraded()) AtomicFiles.write(file.toPath(), loaded.configuration().saveToString());
        return loaded.registry();
    }
    public static Loaded prepare(File file, Progression defaults) throws Exception {
        return prepare(file, defaults, null);
    }
    public static Loaded prepare(File file, Progression defaults, Progression formerDefaults) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.load(file);
        boolean upgraded = ConfigUpgrade.jobs(yaml, defaults, formerDefaults);
        ConfigValues.keys(yaml, Set.of("config-version", "jobs"));
        var section = yaml.getConfigurationSection("jobs");
        if (section == null || section.getKeys(false).isEmpty()) throw new IllegalArgumentException("jobs.yml needs a jobs section");
        var jobs = new ArrayList<Job>();
        for (String id : section.getKeys(false)) {
            try {
                if (!id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("Use a lowercase ID with letters, numbers, underscores or hyphens");
                var entry = section.getConfigurationSection(id);
                if (entry == null) throw new IllegalArgumentException("Expected a job section");
                ConfigValues.keys(entry, Set.of("enabled", "name", "description", "icon", "color", "natural-only", "hourly-income-limit", "distance-per-reward", "boss-bar-cooldown-seconds", "progression", "rules"));
                if (!ConfigValues.bool(entry, "enabled", true)) continue;
                var icon = Material.valueOf(entry.getString("icon", "BOOK").toUpperCase(Locale.ROOT));
                if (!icon.isItem() || icon.isAir()) throw new IllegalArgumentException("Icon must be an item");
                var color = BossBar.Color.valueOf(entry.getString("color", "GREEN").toUpperCase(Locale.ROOT));
                if (entry.contains("progression")) {
                    var progression = entry.getConfigurationSection("progression");
                    if (progression == null) throw new IllegalArgumentException("progression must be a section");
                    ConfigValues.keys(progression, Set.of("first-level-xp", "early-level-xp", "plateau-xp", "early-level", "plateau-level", "xp-multiplier"));
                }
                double firstXp = ConfigValues.number(entry, "progression.first-level-xp", defaults.first());
                double plateauXp = ConfigValues.number(entry, "progression.plateau-xp", defaults.plateau());
                var curve = new Progression(firstXp,
                        ConfigValues.number(entry, "progression.early-level-xp", Math.min(plateauXp, Math.max(firstXp, defaults.early()))), plateauXp,
                        (int) ConfigValues.integer(entry, "progression.early-level", defaults.earlyLevel(), 2, 9999),
                        (int) ConfigValues.integer(entry, "progression.plateau-level", defaults.plateauLevel(), 3, 10000),
                        ConfigValues.number(entry, "progression.xp-multiplier", 1));
                var rules = new ArrayList<Rule>();
                if (!entry.isList("rules") || entry.getList("rules").size() != entry.getMapList("rules").size())
                    throw new IllegalArgumentException("rules must be a list of rule sections");
                for (var map : entry.getMapList("rules")) {
                    var rule = new YamlConfiguration();
                    for (var pair : map.entrySet()) rule.set(pair.getKey().toString(), pair.getValue());
                    var activity = Activity.parse(rule.getString("activity", ""));
                    ConfigValues.keys(rule, Set.of("activity", "materials", "entities", "biomes", "operations", "xp", "money", "description", "bonus"));
                    boolean bypass = ConfigValues.bool(rule, "bonus", false);
                    double xp = ConfigValues.number(rule, "xp", 0);
                    var money = ConfigValues.money(rule, "money", BigDecimal.ZERO);
                    if (!Double.isFinite(xp) || xp < 0 || xp > 1_000_000 || money.signum() < 0 || money.compareTo(new BigDecimal("1000000")) > 0 || money.scale() > 8)
                        throw new IllegalArgumentException("Rewards must be nonnegative, at most 1 million, with at most 8 money decimal places");
                    Set<Material> materials = new HashSet<>();
                    Set<EntityType> entities = new HashSet<>();
                    Set<String> biomes = new HashSet<>();
                    Set<String> operations = new HashSet<>();
                    if (rule.contains("materials") && !activity.usesMaterials())
                        throw new IllegalArgumentException(activity + " does not use materials");
                    if (rule.contains("entities") && !activity.usesEntities())
                        throw new IllegalArgumentException(activity + " does not use entities");
                    if (rule.contains("biomes") && activity != Activity.BIOME_DISCOVER)
                        throw new IllegalArgumentException("biomes is only supported by biome-discover rules");
                    if (rule.contains("operations") && activity != Activity.SMITH && activity != Activity.CARTOGRAPHY)
                        throw new IllegalArgumentException("operations is only supported by smith and cartography rules");
                    for (String operation : ConfigValues.strings(rule, "operations")) {
                        var allowed = activity == Activity.SMITH ? Set.of("upgrade", "trim") : Set.of("expand", "lock", "copy");
                        if (!allowed.contains(operation)) throw new IllegalArgumentException("Unknown operation '" + operation + "'; choose " + allowed);
                        operations.add(operation);
                    }
                    if (rule.contains("operations") && operations.isEmpty()) throw new IllegalArgumentException("operations must not be empty");
                    boolean any = !activity.usesMaterials() && !activity.usesEntities() && activity != Activity.BIOME_DISCOVER;
                    for (String name : ConfigValues.strings(rule, "biomes")) {
                        if (name.equals("*")) any = true;
                        else {
                            var key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
                            if (key == null || org.bukkit.Registry.BIOME.get(key) == null) throw new IllegalArgumentException("Unknown biome: " + name);
                            biomes.add(key.toString());
                        }
                    }
                    for (String name : ConfigValues.strings(rule, "materials")) {
                        if (name.equals("*")) any = true;
                        else if (name.startsWith("#")) {
                            var key = NamespacedKey.fromString(name.substring(1));
                            Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                            if (tag == null) throw new IllegalArgumentException("Unknown block tag: " + name);
                            tag.getValues().stream().filter(m -> !activity.usesItems() || m.isItem()).forEach(materials::add);
                        } else {
                            var material = Material.valueOf(name.toUpperCase(Locale.ROOT));
                            boolean itemActivity = activity.usesItems();
                            if (itemActivity ? !material.isItem() || material.isAir() : !material.isBlock())
                                throw new IllegalArgumentException((itemActivity ? "Not an item: " : "Not a block: ") + name);
                            materials.add(material);
                        }
                    }
                    for (String name : ConfigValues.strings(rule, "entities")) {
                        if (name.equals("*")) any = true;
                        else if (name.equals("#hostile")) {
                            for (EntityType type : EntityType.values()) {
                                Class<?> entityClass = type.getEntityClass();
                                if (entityClass != null && org.bukkit.entity.Enemy.class.isAssignableFrom(entityClass)) entities.add(type);
                            }
                        }
                        else entities.add(EntityType.valueOf(name.toUpperCase(Locale.ROOT)));
                    }
                    if (activity.usesMaterials() && !any && materials.isEmpty()) throw new IllegalArgumentException(activity.name().toLowerCase(Locale.ROOT) + " needs materials");
                    if (activity.usesEntities() && !any && entities.isEmpty()) throw new IllegalArgumentException(activity.name().toLowerCase(Locale.ROOT) + " needs entities");
                    if (activity == Activity.BOSS_KILL && entities.stream().anyMatch(type -> !BossRewards.isBoss(type)))
                        throw new IllegalArgumentException("boss-kill supports WITHER and ENDER_DRAGON");
                    if (activity == Activity.BIOME_DISCOVER && !any && biomes.isEmpty()) throw new IllegalArgumentException("biome-discover needs biomes");
                    rules.add(new Rule(activity, Set.copyOf(materials), Set.copyOf(entities), any, xp, money,
                            ConfigValues.text(rule, "description", "", 100).strip(), Set.copyOf(biomes), Set.copyOf(operations), bypass));
                }
                if (rules.isEmpty()) throw new IllegalArgumentException("Job needs at least one rule");
                jobs.add(new Job(id, entry.getString("name", id), entry.getString("description", ""), icon, color,
                        ConfigValues.bool(entry, "natural-only", true), ConfigValues.money(entry, "hourly-income-limit", null),
                        ConfigValues.integer(entry, "distance-per-reward", 500, 1, 1_000_000),
                        ConfigValues.integer(entry, "boss-bar-cooldown-seconds", 0, 0, 86400),
                        curve, List.copyOf(rules)));
            } catch (Exception e) { throw new IllegalArgumentException("Invalid job '" + id + "': " + e.getMessage(), e); }
        }
        return new Loaded(new JobRegistry(List.copyOf(jobs)), yaml, upgraded);
    }
}
