package checks;

import io.github.rokeydoess.justjobs.*;
import org.bukkit.Bukkit;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.*;

/** Measures idle polling overhead without fabricating gameplay or timing assertions. */
public final class IdlePerformanceChecks extends ReleaseChecks {
    @Override void start() throws Exception {
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(jobs != null && jobs.isEnabled(), "idle performance checks start on Paper");
        var marker = getDataFolder().toPath().resolve("restart");
        if (Files.exists(marker)) { finish(2); return; }
        var activity = (ActivityListener) field(jobs, "activities");
        var payments = jobs.getClass().getDeclaredMethod("flushPayouts"); payments.setAccessible(true);
        var feedback = jobs.getClass().getDeclaredMethod("expireBars"); feedback.setAccessible(true);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        boolean allocations = bean.isThreadAllocatedMemorySupported();
        if (allocations) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        var timings = new ArrayList<Double>(); var bytes = new ArrayList<Long>();
        Object[] noArgs = new Object[0];
        for (int pass = 0; pass < 6; pass++) {
            long before = allocations ? bean.getThreadAllocatedBytes(thread) : 0;
            long start = System.nanoTime();
            for (int i = 0; i < 100_000; i++) { payments.invoke(jobs, noArgs); feedback.invoke(jobs, noArgs); activity.sampleTravel(); }
            long allocated = allocations ? bean.getThreadAllocatedBytes(thread) - before : 0;
            if (pass > 0) { timings.add((System.nanoTime() - start) / 1e6); bytes.add(allocated); }
        }
        timings.sort(Double::compare); bytes.sort(Long::compare);
        getLogger().info(String.format(Locale.ROOT, "IDLE PERFORMANCE: 100000 payment/feedback/sailing cycles median=%.3f ms; allocated=%.2f bytes/cycle",
                timings.get(2), bytes.get(2) / 100_000.0));
        check(((Map<?, ?>) field(jobs, "players")).isEmpty(), "idle maintenance creates no player profiles");
        Files.createDirectories(getDataFolder().toPath()); Files.writeString(marker, "done"); finish(1);
    }
    void finish(int phase) { getLogger().info("RELEASE CHECKS PHASE " + phase + " PASS: " + checks + " idle performance checks"); Bukkit.shutdown(); }
}
