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
        BLOCK_BREAK, BLOCK_PLACE, CRAFT, CROP_HARVEST, MOB_KILL, BREED, FISH, PLAYER_KILL, TRADE, TRAVEL, SAIL, ENCHANT;
        static Activity parse(String text) { return valueOf(text.toUpperCase(Locale.ROOT).replace('-', '_')); }
        boolean usesMaterials() { return this == BLOCK_BREAK || this == BLOCK_PLACE || this == CRAFT || this == CROP_HARVEST || this == FISH; }
        boolean usesEntities() { return this == MOB_KILL || this == BREED; }
    }
    public record Rule(Activity activity, Set<Material> materials, Set<EntityType> entities,
                       boolean any, double xp, BigDecimal money, String description) {
        public boolean matches(Activity type, Material material, EntityType entity) {
            return activity == type && (any || (material != null && materials.contains(material))
                    || (entity != null && entities.contains(entity)));
        }
    }
    public record Job(String id, String name, String description, Material icon, BossBar.Color color, boolean naturalOnly,
                      BigDecimal hourlyLimit, double distancePerReward, Progression progression, List<Rule> rules) { }

    public Job get(String id) { return jobs.stream().filter(j -> j.id.equals(id)).findFirst().orElse(null); }
    public boolean rewards(Activity activity, Material material, EntityType entity) {
        return jobs.stream().flatMap(j -> j.rules().stream())
                .anyMatch(r -> r.matches(activity, material, entity) && (r.xp() > 0 || r.money().signum() > 0));
    }

    public record Loaded(JobRegistry registry, YamlConfiguration configuration, boolean upgraded) { }
    public static JobRegistry load(File file, Progression defaults) throws Exception {
        var loaded = prepare(file, defaults);
        if (loaded.upgraded()) AtomicFiles.write(file.toPath(), loaded.configuration().saveToString());
        return loaded.registry();
    }
    public static Loaded prepare(File file, Progression defaults) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.load(file);
        boolean upgraded = ConfigUpgrade.jobs(yaml);
        ConfigValues.keys(yaml, Set.of("config-version", "jobs"));
        var section = yaml.getConfigurationSection("jobs");
        if (section == null || section.getKeys(false).isEmpty()) throw new IllegalArgumentException("jobs.yml needs a jobs section");
        var jobs = new ArrayList<Job>();
        for (String id : section.getKeys(false)) {
            try {
                if (!id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("Use a lowercase ID with letters, numbers, underscores or hyphens");
                var entry = section.getConfigurationSection(id);
                if (entry == null) throw new IllegalArgumentException("Expected a job section");
                ConfigValues.keys(entry, Set.of("enabled", "name", "description", "icon", "color", "natural-only", "hourly-income-limit", "distance-per-reward", "progression", "rules"));
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
                    ConfigValues.keys(rule, Set.of("activity", "materials", "entities", "xp", "money", "description"));
                    double xp = ConfigValues.number(rule, "xp", 0);
                    var money = ConfigValues.money(rule, "money", BigDecimal.ZERO);
                    if (!Double.isFinite(xp) || xp < 0 || xp > 1_000_000 || money.signum() < 0 || money.compareTo(new BigDecimal("1000000")) > 0 || money.scale() > 8)
                        throw new IllegalArgumentException("Rewards must be nonnegative, at most 1 million, with at most 8 money decimal places");
                    Set<Material> materials = new HashSet<>();
                    Set<EntityType> entities = new HashSet<>();
                    if (rule.contains("materials") && !activity.usesMaterials())
                        throw new IllegalArgumentException("materials is only supported by block-break, block-place, craft, crop-harvest, and fish rules");
                    if (rule.contains("entities") && !activity.usesEntities())
                        throw new IllegalArgumentException("entities is only supported by mob-kill and breed rules");
                    boolean any = !activity.usesMaterials() && !activity.usesEntities();
                    for (String name : ConfigValues.strings(rule, "materials")) {
                        if (name.equals("*")) any = true;
                        else if (name.startsWith("#")) {
                            if (activity == Activity.FISH) throw new IllegalArgumentException("fish rules use item names, not block tags");
                            var key = NamespacedKey.fromString(name.substring(1));
                            Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                            if (tag == null) throw new IllegalArgumentException("Unknown block tag: " + name);
                            tag.getValues().stream().filter(m -> activity != Activity.CRAFT || m.isItem()).forEach(materials::add);
                        } else {
                            var material = Material.valueOf(name.toUpperCase(Locale.ROOT));
                            boolean itemActivity = activity == Activity.FISH || activity == Activity.CRAFT;
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
                    rules.add(new Rule(activity, Set.copyOf(materials), Set.copyOf(entities), any, xp, money,
                            ConfigValues.text(rule, "description", "", 100).strip()));
                }
                if (rules.isEmpty()) throw new IllegalArgumentException("Job needs at least one rule");
                jobs.add(new Job(id, entry.getString("name", id), entry.getString("description", ""), icon, color,
                        ConfigValues.bool(entry, "natural-only", true), ConfigValues.money(entry, "hourly-income-limit", null),
                        ConfigValues.integer(entry, "distance-per-reward", 200, 1, 1_000_000),
                        curve, List.copyOf(rules)));
            } catch (Exception e) { throw new IllegalArgumentException("Invalid job '" + id + "': " + e.getMessage(), e); }
        }
        return new Loaded(new JobRegistry(List.copyOf(jobs)), yaml, upgraded);
    }
}
