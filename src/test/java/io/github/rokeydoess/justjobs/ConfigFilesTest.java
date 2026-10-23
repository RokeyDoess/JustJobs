package io.github.rokeydoess.justjobs;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigFilesTest {
    @TempDir Path folder;
    @Test void successfulUpdateReplacesBothFilesAndRemovesItsJournal() throws Exception {
        ConfigFiles.save(folder, "currency: dollar\n", "jobs: {}\n");
        assertEquals("currency: dollar\n", Files.readString(folder.resolve("config.yml")));
        assertEquals("jobs: {}\n", Files.readString(folder.resolve("jobs.yml")));
        assertFalse(Files.exists(folder.resolve(".config-pending.yml")));
    }
    @Test void interruptedSecondReplacementCanFinishOnTheNextStart() throws Exception {
        Files.createDirectory(folder.resolve("jobs.yml")); Files.writeString(folder.resolve("jobs.yml/lock"), "fixture");
        assertThrows(Exception.class, () -> ConfigFiles.save(folder, "currency: dollar\n", "jobs: {}\n"));
        assertTrue(Files.exists(folder.resolve(".config-pending.yml")));
        Files.delete(folder.resolve("jobs.yml/lock")); Files.delete(folder.resolve("jobs.yml"));
        ConfigFiles.recover(folder);
        assertEquals("jobs: {}\n", Files.readString(folder.resolve("jobs.yml")));
        assertEquals("currency: dollar\n", Files.readString(folder.resolve("config.yml")));
    }
    @Test void invalidPendingYamlDoesNotReplaceExistingFiles() throws Exception {
        Files.writeString(folder.resolve("config.yml"), "original: true\n");
        assertThrows(Exception.class, () -> ConfigFiles.save(folder, "original: false\n", "broken: [\n"));
        assertEquals("original: true\n", Files.readString(folder.resolve("config.yml")));
        assertFalse(Files.exists(folder.resolve(".config-pending.yml")));
    }
}
