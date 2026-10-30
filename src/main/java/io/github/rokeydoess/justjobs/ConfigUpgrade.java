package io.github.rokeydoess.justjobs;

import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

final class ConfigUpgrade {
    private ConfigUpgrade() { }
    static boolean settings(YamlConfiguration yaml) {
        long version = ConfigValues.integer(yaml, "config-version", 0, 0, 4);
        if (version == 4) return false;
        if (version == 0) {
            yaml.set("progression.growth", null);
            if (!yaml.contains("progression.first-level-xp") || yaml.get("progression.first-level-xp") instanceof Number number && number.doubleValue() == 10)
                yaml.set("progression.first-level-xp", Math.min(25, yaml.getDouble("progression.plateau-xp", 2500)));
            if (!yaml.contains("progression.early-level-xp"))
                yaml.set("progression.early-level-xp", Math.max(yaml.getDouble("progression.first-level-xp", 25), Math.min(150, yaml.getDouble("progression.plateau-xp", 2500))));
        }
        if (version < 2 && (!yaml.contains("rules.reward-spawner-mobs") || Boolean.TRUE.equals(yaml.get("rules.reward-spawner-mobs"))))
            yaml.set("rules.reward-spawner-mobs", false);
        if (version < 3 && yaml.contains("economy.hourly-income-limit-per-job")
                && ConfigValues.money(yaml, "economy.hourly-income-limit-per-job", null).compareTo(new java.math.BigDecimal("100")) == 0)
            yaml.set("economy.hourly-income-limit-per-job", "25");
        boolean retuned = matchesNumber(yaml, "progression.first-level-xp", 25)
                && matchesNumber(yaml, "progression.early-level-xp", 150)
                && matchesNumber(yaml, "progression.plateau-xp", 2500)
                && matchesNumber(yaml, "progression.early-level", 10) && matchesNumber(yaml, "progression.plateau-level", 50);
        if (retuned) {
            yaml.set("progression.first-level-xp", 10); yaml.set("progression.early-level-xp", 40); yaml.set("progression.plateau-xp", 200);
        }
        yaml.set("config-version", 4);
        return retuned;
    }
    static boolean jobs(YamlConfiguration yaml) {
        return jobs(yaml, Progression.defaults(), new Progression(25, 150, 2500, 10, 50, 1));
    }
    static boolean jobs(YamlConfiguration yaml, Progression shared, Progression former) {
        long version = ConfigValues.integer(yaml, "config-version", 0, 0, 14);
        if (version == 14) return false;
        if (version < 2) upgradeOriginalJobs(yaml, version);
        var jobs = yaml.getConfigurationSection("jobs");
        if (jobs != null && !jobs.getKeys(false).isEmpty()) {
            var defaults = bundledJobs();
            if (version < 3) for (String id : java.util.List.of("farming", "fishing", "engineering", "building"))
                if (!jobs.contains(id)) jobs.createSection(id, defaults.getConfigurationSection("jobs." + id).getValues(false));
            if (version < 4) for (String id : jobs.getKeys(false)) {
                var job = jobs.getConfigurationSection(id);
                if (job == null || !job.isList("rules") || job.getList("rules").size() != job.getMapList("rules").size()) continue;
                var rules = new java.util.ArrayList<Map<String, Object>>();
                for (var raw : job.getMapList("rules")) {
                    var rule = new java.util.LinkedHashMap<String, Object>();
                    raw.forEach((key, value) -> rule.put(key.toString(), value));
                    if (!rule.containsKey("description")) for (var candidate : defaults.getMapList("jobs." + id + ".rules")) {
                        if (java.util.Objects.equals(rule.get("activity"), candidate.get("activity"))
                                && java.util.Objects.equals(selectors(rule, "materials"), selectors(candidate, "materials"))
                                && java.util.Objects.equals(selectors(rule, "entities"), selectors(candidate, "entities"))) {
                            rule.put("description", candidate.get("description")); break;
                        }
                    }
                    rules.add(rule);
                }
                job.set("rules", rules);
            }
            if (version < 5) upgradePlacementJobs(yaml, defaults);
            retuneDefaults(yaml, defaults, version);
            if (version < 10) expandJobs(yaml, defaults);
            if (version < 11) addProcessingJobs(yaml, defaults);
            if (version < 12) addFeedbackAndPots(yaml, defaults);
            if (version < 13) retuneXp(yaml, defaults, shared, former);
            if ("Make and improve potions with ingredients you load by hand.".equals(yaml.getString("jobs.brewing.description")))
                yaml.set("jobs.brewing.description", defaults.getString("jobs.brewing.description"));
        }
        yaml.set("config-version", 14);
        return true;
    }
    private static void retuneXp(YamlConfiguration yaml, YamlConfiguration defaults, Progression shared, Progression former) {
        var aliases = Map.of("digger", "digging", "miner", "mining", "lumberjack", "treefelling", "hunter", "hunting",
                "gladiator", "dueling", "trader", "trading", "explorer", "exploring", "sailor", "sailing", "enchanter", "enchanting");
        var jobs = yaml.getConfigurationSection("jobs");
        boolean standard = shared.first() == 10 && shared.early() == 40 && shared.plateau() == 200
                && shared.earlyLevel() == 10 && shared.plateauLevel() == 50;
        for (String id : jobs.getKeys(false)) {
            var job = jobs.getConfigurationSection(id);
            if (job == null) continue;
            String canonical = aliases.getOrDefault(id, id);
            var preset = defaults.getConfigurationSection("jobs." + canonical);
            boolean custom = job.contains("progression") && (!job.isConfigurationSection("progression")
                    || job.getConfigurationSection("progression").getKeys(false).stream().anyMatch(key -> !key.equals("xp-multiplier")
                    && !(canonical.equals("dueling") && key.equals("first-level-xp") && job.get("progression." + key) instanceof Number n && n.doubleValue() == 10)));
            if (custom && former != null && job.isConfigurationSection("progression")) {
                // Keep inherited parts of a customized curve when the shared defaults change.
                double first = job.getDouble("progression.first-level-xp", former.first());
                double plateau = job.getDouble("progression.plateau-xp", former.plateau());
                if (!job.contains("progression.first-level-xp")) job.set("progression.first-level-xp", first);
                if (!job.contains("progression.early-level-xp")) job.set("progression.early-level-xp", Math.min(plateau, Math.max(first, former.early())));
                if (!job.contains("progression.plateau-xp")) job.set("progression.plateau-xp", plateau);
                if (!job.contains("progression.early-level")) job.set("progression.early-level", former.earlyLevel());
                if (!job.contains("progression.plateau-level")) job.set("progression.plateau-level", former.plateauLevel());
            } else if (!custom && standard && preset != null && preset.isConfigurationSection("progression")) {
                for (String key : preset.getConfigurationSection("progression").getKeys(false))
                    if (!job.contains("progression." + key)) job.set("progression." + key, preset.get("progression." + key));
            }
            if (!job.isList("rules") || job.getList("rules").size() != job.getMapList("rules").size()) continue;
            var rules = new java.util.ArrayList<Map<?, ?>>(job.getMapList("rules"));
            for (int i = 0; i < rules.size(); i++) {
                var raw = rules.get(i);
                String activity = String.valueOf(raw.get("activity")).toLowerCase(java.util.Locale.ROOT).replace('_', '-');
                String expected = canonical.equals("exploring") && activity.equals("travel") ? "0.01"
                        : (canonical.equals("cooking") && activity.equals("cook") || canonical.equals("smithing") && activity.equals("smelt")) ? "0.5" : null;
                if (expected == null || preset == null) continue;
                for (var candidate : preset.getMapList("rules")) try {
                    if (activity.equals(candidate.get("activity")) && java.util.Objects.equals(selectors(raw, "materials"), selectors(candidate, "materials"))
                            && new java.math.BigDecimal(String.valueOf(raw.get("xp"))).compareTo(new java.math.BigDecimal(expected)) == 0) {
                        var rule = new java.util.LinkedHashMap<String, Object>(); raw.forEach((key, value) -> rule.put(key.toString(), value));
                        rule.put("xp", candidate.get("xp")); rules.set(i, rule); break;
                    }
                } catch (NumberFormatException ignored) { /* Normal validation reports invalid XP. */ }
            }
            job.set("rules", rules);
        }
    }
    private static boolean matchesNumber(YamlConfiguration yaml, String path, double expected) {
        return !yaml.contains(path) || yaml.get(path) instanceof Number n && n.doubleValue() == expected;
    }
    private static void addFeedbackAndPots(YamlConfiguration yaml, YamlConfiguration defaults) {
        for (String id : java.util.List.of("cooking", "smithing")) {
            var job = yaml.getConfigurationSection("jobs." + id);
            if (job != null && !job.contains("boss-bar-cooldown-seconds"))
                job.set("boss-bar-cooldown-seconds", defaults.get("jobs." + id + ".boss-bar-cooldown-seconds"));
        }
        var archaeology = yaml.getConfigurationSection("jobs.archaeology");
        if (archaeology == null || !archaeology.isList("rules") || archaeology.getList("rules").size() != archaeology.getMapList("rules").size()) return;
        if (archaeology.getMapList("rules").stream().noneMatch(rule -> "block-break".equalsIgnoreCase(String.valueOf(rule.get("activity")).replace('_', '-')))) {
            var rules = new java.util.ArrayList<Map<?, ?>>(archaeology.getMapList("rules"));
            rules.addAll(defaults.getMapList("jobs.archaeology.rules").stream().filter(rule -> "block-break".equals(rule.get("activity"))).toList());
            archaeology.set("rules", rules);
        }
        if ("Brush buried finds out of suspicious sand and gravel.".equals(archaeology.getString("description")))
            archaeology.set("description", defaults.getString("jobs.archaeology.description"));
    }
    private static void addProcessingJobs(YamlConfiguration yaml, YamlConfiguration defaults) {
        var jobs = yaml.getConfigurationSection("jobs");
        for (String id : java.util.List.of("cooking", "brewing"))
            if (!jobs.contains(id)) jobs.createSection(id, defaults.getConfigurationSection("jobs." + id).getValues(false));
        var smithing = jobs.getConfigurationSection("smithing");
        if (smithing == null || !smithing.isList("rules") || smithing.getList("rules").size() != smithing.getMapList("rules").size()) return;
        if (smithing.getMapList("rules").stream().noneMatch(rule -> "smelt".equals(rule.get("activity")))) {
            var rules = new java.util.ArrayList<Map<?, ?>>(smithing.getMapList("rules"));
            rules.addAll(defaults.getMapList("jobs.smithing.rules").stream().filter(rule -> "smelt".equals(rule.get("activity"))).toList());
            smithing.set("rules", rules);
        }
        if ("Make, upgrade, trim and repair armor and weapons.".equals(smithing.getString("description")))
            smithing.set("description", defaults.getString("jobs.smithing.description"));
    }
    private static void expandJobs(YamlConfiguration yaml, YamlConfiguration defaults) {
        var formerRates = Map.of("mining", java.util.List.of("0.0075", "0.03", "0.10", "0.50"),
                "hunting", java.util.List.of("0.1667"), "dueling", java.util.List.of("5"),
                "exploring", java.util.List.of("0.001"), "enchanting", java.util.List.of("0.50"));
        var aliases = Map.of("miner", "mining", "hunter", "hunting", "gladiator", "dueling", "explorer", "exploring", "enchanter", "enchanting");
        var jobs = yaml.getConfigurationSection("jobs");
        for (String id : jobs.getKeys(false)) {
            String canonical = aliases.getOrDefault(id, id);
            var job = jobs.getConfigurationSection(id);
            if (job == null || !job.isList("rules") || job.getList("rules").size() != job.getMapList("rules").size()) continue;
            var rules = new java.util.ArrayList<Map<String, Object>>();
            var candidates = defaults.getMapList("jobs." + canonical + ".rules");
            for (var raw : job.getMapList("rules")) {
                var rule = new java.util.LinkedHashMap<String, Object>(); raw.forEach((key, value) -> rule.put(key.toString(), value));
                var rates = formerRates.getOrDefault(canonical, java.util.List.of());
                for (int i = 0; i < rates.size(); i++) {
                    var candidate = candidates.get(i);
                    try {
                        if (java.util.Objects.equals(rule.get("activity"), candidate.get("activity"))
                                && java.util.Objects.equals(selectors(rule, "materials"), selectors(candidate, "materials"))
                                && java.util.Objects.equals(selectors(rule, "entities"), selectors(candidate, "entities"))
                                && new java.math.BigDecimal(String.valueOf(rule.get("money"))).compareTo(new java.math.BigDecimal(rates.get(i))) == 0)
                            rule.put("money", candidate.get("money"));
                    } catch (NumberFormatException ignored) { /* Normal validation reports invalid amounts. */ }
                }
                rules.add(rule);
            }
            if (java.util.Set.of("farming", "building", "exploring").contains(canonical)) {
                var present = rules.stream().map(rule -> rule.get("activity")).collect(java.util.stream.Collectors.toSet());
                var additions = switch (canonical) {
                    case "farming" -> java.util.Set.of("shear", "beehive-harvest", "tame");
                    case "building" -> java.util.Set.of("stonecut", "loom");
                    default -> java.util.Set.of("biome-discover", "cartography");
                };
                for (var candidate : candidates) if (additions.contains(candidate.get("activity")) && !present.contains(candidate.get("activity"))) {
                    var added = new java.util.LinkedHashMap<String, Object>(); candidate.forEach((key, value) -> added.put(key.toString(), value));
                    rules.add(added);
                }
                String former = switch (canonical) {
                    case "farming" -> "Harvest mature crops and breed animals.";
                    case "building" -> "Craft brickwork, bookshelves, flower pots and jack o'lanterns.";
                    default -> "Explore on foot.";
                };
                if (former.equals(job.getString("description"))) job.set("description", defaults.getString("jobs." + canonical + ".description"));
            }
            job.set("rules", rules);
        }
        for (String id : java.util.List.of("archaeology", "smithing"))
            if (!jobs.contains(id)) jobs.createSection(id, defaults.getConfigurationSection("jobs." + id).getValues(false));
    }
    private static void retuneDefaults(YamlConfiguration yaml, YamlConfiguration defaults, long version) {
        for (String id : java.util.List.of("digging", "digger", "dueling", "gladiator", "fishing", "hunting", "hunter")) {
            String canonical = switch (id) {
                case "digger" -> "digging";
                case "gladiator" -> "dueling";
                case "hunter" -> "hunting";
                default -> id;
            };
            if (version >= switch (canonical) {
                case "digging" -> 6;
                case "fishing" -> 7;
                case "dueling" -> 8;
                default -> 9;
            }) continue;
            var job = yaml.getConfigurationSection("jobs." + id);
            if (job == null || !job.isList("rules") || job.getList("rules").size() != job.getMapList("rules").size()) continue;
            var expected = defaults.getMapList("jobs." + canonical + ".rules").getFirst();
            var previous = new java.math.BigDecimal(switch (canonical) {
                case "digging" -> "0.0075";
                case "dueling" -> version < 6 ? "12.50" : "10";
                case "hunting" -> "0.40";
                default -> "0.75";
            });
            var rules = new java.util.ArrayList<Map<String, Object>>();
            for (var raw : job.getMapList("rules")) {
                var rule = new java.util.LinkedHashMap<String, Object>(); raw.forEach((key, value) -> rule.put(key.toString(), value));
                try {
                    if (java.util.Objects.equals(rule.get("activity"), expected.get("activity"))
                            && java.util.Objects.equals(selectors(rule, "materials"), selectors(expected, "materials"))
                            && java.util.Objects.equals(selectors(rule, "entities"), selectors(expected, "entities"))
                            && new java.math.BigDecimal(String.valueOf(rule.get("money"))).compareTo(previous) == 0)
                        rule.put("money", expected.get("money"));
                } catch (NumberFormatException ignored) { /* Normal validation reports invalid amounts. */ }
                rules.add(rule);
            }
            job.set("rules", rules);
        }
        if (version < 7) for (String id : java.util.List.of("exploring", "explorer", "sailing", "sailor")) {
            String key = "jobs." + id + ".distance-per-reward";
            String canonical = id.equals("explorer") ? "exploring" : id.equals("sailor") ? "sailing" : id;
            if (yaml.get(key) instanceof Number n && (n.doubleValue() == 200
                    || version < 6 && canonical.equals("exploring") && n.doubleValue() == 150))
                yaml.set(key, defaults.get("jobs." + canonical + ".distance-per-reward"));
        }
    }
    private static void upgradePlacementJobs(YamlConfiguration yaml, YamlConfiguration defaults) {
        // Fingerprints identify the old bundled selectors without replacing an owner's custom list.
        var fingerprints = Map.of("engineering", java.util.List.of(
                "ef07ad9908774b59d3939714bfff3c831eb28f0a0153734b938702736856ab9d",
                "4aa4eebb96a7e4f769f7600d4f5cf043962d16903319f7834cf814773e553812",
                "a2692176c9d0faa43ae20abd29019bfc542b8b546e2a4a8840b4a0c7aa6219de"),
                "building", java.util.List.of("ac1e59b480a4843780110078adb512bd1db21be7698e8812dbe83f401cb222f8"));
        for (String id : fingerprints.keySet()) {
            var job = yaml.getConfigurationSection("jobs." + id);
            if (job == null || !job.isList("rules") || job.getList("rules").size() != job.getMapList("rules").size()) continue;
            var rules = new java.util.ArrayList<Map<String, Object>>();
            for (var raw : job.getMapList("rules")) {
                var rule = new java.util.LinkedHashMap<String, Object>(); raw.forEach((key, value) -> rule.put(key.toString(), value));
                if ("block-place".equals(rule.get("activity"))) {
                    int tier = fingerprints.get(id).indexOf(selectorFingerprint(rule));
                    if (tier >= 0) {
                        var next = defaults.getMapList("jobs." + id + ".rules").get(tier);
                        rule.put("materials", next.get("materials"));
                        if (!rule.containsKey("description") || java.util.Set.of("wiring or ordinary rail", "powered, detector or activator rail",
                                "component (piston, hopper, observer, etc.)", "building block placed").contains(String.valueOf(rule.get("description"))))
                            rule.put("description", next.get("description"));
                    } else if (rule.get("materials") instanceof java.util.List<?> names && names.stream().allMatch(String.class::isInstance)) {
                        rule.put("materials", names.stream().map(name -> switch (name.toString().toUpperCase(java.util.Locale.ROOT)) {
                            case "REDSTONE_WIRE" -> "REDSTONE";
                            case "REDSTONE_WALL_TORCH" -> "REDSTONE_TORCH";
                            default -> name;
                        }).distinct().toList());
                    }
                    rule.put("activity", "craft");
                }
                rules.add(rule);
            }
            job.set("rules", rules);
            String oldDescription = id.equals("engineering") ? "Place redstone components and railway blocks." : "Build with wood, stone, glass, and decorative blocks.";
            if (oldDescription.equals(job.getString("description"))) job.set("description", defaults.getString("jobs." + id + ".description"));
        }
    }
    private static String selectorFingerprint(Map<String, Object> rule) {
        if (!(rule.get("materials") instanceof java.util.List<?> names) || names.stream().anyMatch(n -> !(n instanceof String))) return "";
        String canonical = names.stream().map(n -> n.toString().toLowerCase(java.util.Locale.ROOT)).distinct().sorted().collect(java.util.stream.Collectors.joining("\n"));
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Object selectors(Map<?, ?> rule, String key) {
        var value = rule.get(key);
        if (value instanceof java.util.List<?> names && names.stream().allMatch(String.class::isInstance))
            return names.stream().map(name -> name.toString().toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        return value;
    }
    private static void upgradeOriginalJobs(YamlConfiguration yaml, long version) {
        var names = Map.of("digger", "Digging", "miner", "Mining", "lumberjack", "Treefelling", "hunter", "Hunting",
                "gladiator", "Dueling", "trader", "Trading", "explorer", "Exploring", "sailor", "Sailing", "enchanter", "Enchanting");
        var previous = Map.of("digger", "Digger", "miner", "Miner", "lumberjack", "Lumberjack", "hunter", "Hunter",
                "gladiator", "Gladiator", "trader", "Trader", "explorer", "Explorer", "sailor", "Sailor", "enchanter", "Enchanter");
        var rewards = Map.of("digger", Map.of("0.04", "0.0075", "0.08", "0.0075"), "miner", Map.of("0.05", "0.0075", "0.10", "0.0075"),
                "lumberjack", Map.of("0.25", "0.075", "0.35", "0.075"), "hunter", Map.of("0.75", "0.40", "1.00", "0.40"), "gladiator", Map.of("20", "12.50", "25", "12.50"),
                "trader", Map.of("0.50", "0.10", "0.75", "0.10"), "explorer", Map.of("0.002", "0.001", "0.003", "0.001"), "sailor", Map.of("0.001", "0.0005", "0.0015", "0.0005"), "enchanter", Map.of("1", "0.50", "1.25", "0.50"));
        var jobs = yaml.getConfigurationSection("jobs");
        if (jobs != null) for (String id : jobs.getKeys(false)) {
            var job = jobs.getConfigurationSection(id);
            if (job == null) continue;
            if (job.contains("progression") && !job.isConfigurationSection("progression")) continue;
            if (version == 0 && previous.getOrDefault(id, "").equals(job.getString("name"))) job.set("name", names.get(id));
            if (version == 0) job.set("progression.growth", null);
            if (job.getConfigurationSection("progression") != null && job.getConfigurationSection("progression").getKeys(false).isEmpty()) job.set("progression", null);
            if (!job.isList("rules") || job.getList("rules").size() != job.getMapList("rules").size()) continue;
            var rules = new java.util.ArrayList<Map<String, Object>>();
            for (var raw : job.getMapList("rules")) {
                var copy = new java.util.LinkedHashMap<String, Object>();
                raw.forEach((key, value) -> copy.put(key.toString(), value)); rules.add(copy);
            }
            var updated = new java.util.ArrayList<Map<String, Object>>();
            for (var rule : rules) {
                if (id.equals("miner") && splitOres(rule, updated)) continue;
                Object amount = rule.get("money");
                try {
                    for (var replacement : rewards.getOrDefault(id, Map.<String, String>of()).entrySet())
                        if (amount != null && new java.math.BigDecimal(amount.toString()).compareTo(new java.math.BigDecimal(replacement.getKey())) == 0) {
                            rule.put("money", replacement.getValue()); break;
                        }
                } catch (NumberFormatException ignored) { /* Normal validation reports invalid rewards. */ }
                updated.add(rule);
            }
            job.set("rules", updated);
            if (version == 0 && id.equals("gladiator") && !job.contains("progression.first-level-xp")) job.set("progression.first-level-xp", 10);
            if (id.equals("explorer") || id.equals("sailor")) {
                int oldInterval = id.equals("sailor") ? 500 : 250;
                if (!job.contains("distance-per-reward") || job.get("distance-per-reward") instanceof Number n && n.doubleValue() == oldInterval)
                    job.set("distance-per-reward", id.equals("sailor") ? 200 : 150);
            }
        }
    }
    private static boolean splitOres(Map<String, Object> rule, java.util.List<Map<String, Object>> output) {
        if (!"block-break".equals(rule.get("activity")) || !(rule.get("materials") instanceof java.util.List<?> materials)) return false;
        try {
            var amount = new java.math.BigDecimal(String.valueOf(rule.get("money")));
            if (amount.compareTo(new java.math.BigDecimal("0.50")) != 0 && amount.compareTo(new java.math.BigDecimal("0.75")) != 0) return false;
        } catch (NumberFormatException ignored) { return false; }
        var defaults = bundledJobs();
        var tiers = defaults.getMapList("jobs.mining.rules").subList(1, 4);
        var expected = new java.util.HashSet<Object>();
        for (var tier : tiers) expected.addAll((java.util.List<?>) tier.get("materials"));
        if (materials.size() != expected.size() || !expected.equals(new java.util.HashSet<>(materials))) return false;
        for (var tier : tiers) {
            var copy = new java.util.LinkedHashMap<>(rule);
            copy.put("materials", tier.get("materials")); copy.put("money", tier.get("money"));
            output.add(copy);
        }
        return true;
    }
    private static YamlConfiguration bundledJobs() {
        try (var stream = ConfigUpgrade.class.getResourceAsStream("/jobs.yml")) {
            if (stream == null) throw new IllegalStateException("Missing bundled jobs.yml");
            return YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read default jobs", e); }
    }
}
