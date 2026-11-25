package com.starhub;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.sql.Connection;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@WebListener
public final class IntelligenceRuntime implements ServletContextListener {
    private ScheduledExecutorService executor;
    private long nextWarning;

    @Override public void contextInitialized(ServletContextEvent event) {
        executor = Executors.newSingleThreadScheduledExecutor(work -> {
            Thread thread = new Thread(work, "starhub-intelligence");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                Properties properties = IntelligenceConfig.deploymentProperties();
                IntelligenceConfig config = IntelligenceConfig.read(properties);
                if (config == null || !config.worker) return;
                FileStorage files = storage();
                LongSupplier clock = System::currentTimeMillis;
                new IntelligenceWorker(config, new Live(files, config, clock), clock,
                        "true".equals(properties.getProperty("intelligence.bootstrap.enabled"))).tick();
            } catch (Exception failure) {
                long now = System.currentTimeMillis();
                if (!Thread.currentThread().isInterrupted() && now >= nextWarning) {
                    // The status surface carries safe, specific codes. SQL and upstream bodies can contain private data.
                    event.getServletContext().log("Star Intelligence scheduler could not confirm its work; it will retry.");
                    nextWarning = now + 10 * IntelligenceStore.MINUTE;
                }
            } finally { AtomicDatabase.clearConfirmation(); }
        }, 8, 20, TimeUnit.SECONDS);
    }

    @Override public void contextDestroyed(ServletContextEvent event) {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                event.getServletContext().log("Star Intelligence worker will recover from its persisted lease.");
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    static FileStorage storage() throws Exception {
        FileStorage files = FileStorage.get();
        if (files.atomic == null || !SingleDatabase.enabled(files.config)) throw StorageFailure.unavailable();
        return files;
    }

    static <T> T transaction(FileStorage files, boolean mutation, AtomicDatabase.Work<T> work) throws Exception {
        return files.atomic.database.run(IntelligenceConfig.id(), (Connection c) -> {
            if (mutation) files.atomic.transientCapacity(c, 320 * 1024);
            return work.run(c);
        });
    }

    private static final class Live implements IntelligenceWorker.Backend {
        private final FileStorage files;
        private final IntelligenceConfig config;
        private final IntelligenceModels models;

        Live(FileStorage files, IntelligenceConfig config, LongSupplier clock) {
            this.files = files;
            this.config = config;
            models = new IntelligenceModels(files.atomic, new IntelligenceStore(config), config, clock);
        }

        @Override public <T> T read(AtomicDatabase.Work<T> work) throws Exception { return files.atomic.database.readHint(work); }
        @Override public <T> T write(AtomicDatabase.Work<T> work) throws Exception { return transaction(files, true, work); }
        @Override public ArrayNode portfolio(long now) throws Exception { return IntelligenceInputs.portfolio(now); }
        @Override public ObjectNode search(IntelligenceStore.Cycle cycle, ObjectNode input) throws Exception {
            return models.search(cycle, input);
        }
        @Override public ObjectNode decide(IntelligenceStore.Cycle cycle, ObjectNode input, String model, String effort) throws Exception {
            return models.decide(cycle, input, model, effort);
        }
        @Override public AtomicFileStorage.BinaryFile file(String id) throws Exception {
            return files.atomic.readBinary(config.user, id);
        }
        @Override public IntelligenceMail.Result send(IntelligenceStore.Mail mail, IntelligenceConfig.Mail transport) throws Exception {
            return IntelligenceMail.send(mail, transport);
        }
        @Override public String delivery(IntelligenceStore.Mail mail, IntelligenceConfig.Mail transport) throws Exception {
            return IntelligenceMail.status(mail, transport);
        }
        @Override public void stillAuthorized(IntelligenceConfig.Mail transport) throws Exception {
            var latest = IntelligenceConfig.read(IntelligenceConfig.deploymentProperties());
            if (!config.sameOwner(latest) || !latest.worker || latest.mail() == null
                    || !transport.transportId.equals(latest.mail().transportId) || Thread.currentThread().isInterrupted()) {
                throw new IntelligenceStore.Unready("STALE");
            }
        }
    }
}
