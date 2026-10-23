package io.github.rokeydoess.justjobs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/** The journal lets a restart finish both replacements if a crash interrupts a config upgrade. */
final class ConfigFiles {
    private static final String JOURNAL = ".config-pending.yml";
    private ConfigFiles() { }
    static void save(Path folder, String settings, String jobs) throws Exception {
        for (String text : List.of(settings, jobs)) new YamlConfiguration().loadFromString(text);
        var pending = new YamlConfiguration();
        pending.set("version", 1); pending.set("config.yml", settings); pending.set("jobs.yml", jobs);
        AtomicFiles.write(folder.resolve(JOURNAL), pending.saveToString());
        recover(folder);
    }
    static void recover(Path folder) throws Exception {
        Path journal = folder.resolve(JOURNAL);
        if (!Files.exists(journal)) return;
        var pending = new YamlConfiguration(); pending.load(journal.toFile());
        if (pending.getInt("version") != 1) throw new IllegalArgumentException("Unsupported pending configuration update");
        // Validate both documents before changing either destination.
        for (String name : List.of("config.yml", "jobs.yml")) {
            if (!pending.isString(name)) throw new IllegalArgumentException("Pending configuration update is missing " + name);
            var parsed = new YamlConfiguration(); parsed.loadFromString(pending.getString(name));
        }
        for (String name : List.of("config.yml", "jobs.yml")) AtomicFiles.write(folder.resolve(name), pending.getString(name));
        Files.delete(journal);
    }
}
