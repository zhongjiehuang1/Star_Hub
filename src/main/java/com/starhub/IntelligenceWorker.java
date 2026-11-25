package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.function.LongSupplier;

import static com.starhub.IntelligenceStore.HOUR;
import static com.starhub.IntelligenceStore.MINUTE;
import static com.starhub.ModelGateway.JSON;

/** Each tick is recoverable from SQL. Network calls never hold a database transaction open. */
final class IntelligenceWorker {
    interface Backend {
        <T> T read(AtomicDatabase.Work<T> work) throws Exception;
        <T> T write(AtomicDatabase.Work<T> work) throws Exception;
        ArrayNode portfolio(long now) throws Exception;
        ObjectNode search(IntelligenceStore.Cycle cycle, ObjectNode input) throws Exception;
        ObjectNode decide(IntelligenceStore.Cycle cycle, ObjectNode input, String model, String effort) throws Exception;
        AtomicFileStorage.BinaryFile file(String id) throws Exception;
        IntelligenceMail.Result send(IntelligenceStore.Mail mail, IntelligenceConfig.Mail transport) throws Exception;
        String delivery(IntelligenceStore.Mail mail, IntelligenceConfig.Mail transport) throws Exception;
        void stillAuthorized(IntelligenceConfig.Mail transport) throws Exception;
    }

    private final IntelligenceConfig config;
    private final IntelligenceStore store;
    private final IntelligenceInputs inputs;
    private final Backend backend;
    private final LongSupplier clock;
    private final boolean bootstrap;

    IntelligenceWorker(IntelligenceConfig config, Backend backend, LongSupplier clock, boolean bootstrap) {
        this.config = config;
        store = new IntelligenceStore(config);
        inputs = new IntelligenceInputs(config, store);
        this.backend = backend;
        this.clock = clock;
        this.bootstrap = bootstrap;
    }

    void tick() throws Exception {
        long now = clock.getAsLong();
        IntelligenceStore.State hint = backend.read(c -> store.ownerActive(c) ? store.load(c, false, false) : null);
        if (hint == null && bootstrap) {
            hint = backend.write(c -> {
                if (!store.ownerActive(c)) return null;
                var current = store.load(c, true, false);
                return current == null ? store.create(c, true, store.selectedKey(c)) : current;
            });
        }
        if (hint == null || !hint.enabled()) return;
        boolean mailReady = config.mail() != null;
        if (hint.number("heartbeat") + 2 * MINUTE <= now
                || IntelligenceStore.reviewDue(hint, now) && hint.number("leaseUntil") <= now
                || mailReady != hint.control().path("mailConfigured").asBoolean()) {
            IntelligenceStore.Cycle cycle = backend.write(c -> {
                if (!store.ownerActive(c)) return null;
                var current = store.load(c, true, false);
                if (current == null || !current.enabled()) return null;
                current.control().put("mailConfigured", mailReady);
                var claimed = store.claim(c, current, now);
                if (claimed == null) {
                    current.control().put("heartbeat", now);
                    store.save(c, current, false);
                }
                return claimed;
            });
            if (cycle != null) assess(cycle);
        }
        IntelligenceConfig.Mail transport = config.mail();
        if (transport != null && backend.read(c -> store.hasDueMail(c, clock.getAsLong()))) processMail(transport);
    }

    private void assess(IntelligenceStore.Cycle cycle) throws Exception {
        try {
            var state = backend.write(c -> {
                store.requireCycle(c, cycle, clock.getAsLong());
                return store.load(c, true, true);
            });
            ObjectNode snapshot = state.snapshot();
            refreshPortfolio(cycle, state);
            ObjectNode world = refreshWorld(cycle, state);
            var context = backend.write(c -> {
                var current = store.requireCycle(c, cycle, clock.getAsLong());
                ObjectNode personal = inputs.privateInputs(c, current);
                List<String> fileIds = current.control().path("files").asBoolean() ? inputs.fileIds(c) : List.of();
                return new Context(personal, fileIds);
            });
            ObjectNode prompt = context.personal();
            ArrayNode documents = prompt.putArray("files");
            for (String id : context.files()) {
                try { IntelligenceInputs.addFile(documents, backend.file(id)); }
                catch (StorageFailure moved) { if (moved.status != 404) throw moved; }
            }
            long now = clock.getAsLong();
            prompt.put("localDate", IntelligenceStore.day(now));
            prompt.set("portfolio", snapshot.path("portfolio"));
            if (now - world.path("at").asLong() <= 72 * HOUR) {
                prompt.put("worldBriefing", world.path("text").asText());
                prompt.set("worldSources", world.path("sources"));
            }
            String inputHash = evidenceHash(prompt);
            JsonNode prior = snapshot.path("assessment");
            // A disabled account-memory source also stops reuse of its derived context.
            boolean memoryRevoked = state.control().path("memory").asBoolean() && !prompt.has("memoryRevision")
                    && snapshot.path("usedMemory").asBoolean();
            if (!memoryRevoked && unchanged(state, inputHash, now)) {
                deferUntilNewEvidence(cycle, now);
                return;
            }
            if (!memoryRevoked) {
                prompt.set("currentAction", snapshot.path("action"));
                prompt.set("previousOutcome", prior.path("reportedOutcome"));
                prompt.put("priorLearning", prior.path("learning").asText());
                var previousSignals = prompt.putArray("previousObservations");
                for (JsonNode observation : prior.path("observations")) {
                    if (previousSignals.size() >= 5) break;
                    previousSignals.addObject().put("kind", observation.path("kind").asText())
                            .put("scope", observation.path("scope").asText()).put("text", observation.path("text").asText());
                }
            }
            boolean sameRules = prior.path("decisionVersion").asInt() == IntelligenceDecision.VERSION;
            String model = sameRules && "defer".equals(prior.path("verdict").asText()) && prompt.has("worldBriefing")
                    && !IntelligenceModels.ASTRA.equals(prior.path("model").asText()) ? IntelligenceModels.ASTRA : IntelligenceModels.SOL;
            String effort = !prompt.path("feedback").isEmpty()
                    || sameRules && prior.path("researchNeeded").asBoolean() ? "high" : "medium";
            ObjectNode response;
            try {
                response = backend.decide(cycle, prompt, model, effort);
            } catch (IntelligenceStore.Unready unavailable) {
                if (!IntelligenceModels.ASTRA.equals(model) || !"BUDGET".equals(unavailable.code)) throw unavailable;
                model = IntelligenceModels.SOL;
                response = backend.decide(cycle, prompt, model, effort);
            }
            ObjectNode result;
            try { result = IntelligenceStore.object(response.path("text").asText()); }
            catch (Exception invalid) { throw new IntelligenceStore.Unready("DECISION_INVALID"); }
            IntelligenceDecision.Review review = IntelligenceDecision.evaluate(result, prompt, clock.getAsLong());
            review.assessment().put("model", model).put("estimatedMicros", response.path("estimatedMicros").asLong())
                    .put("decisionVersion", IntelligenceDecision.VERSION);
            publish(cycle, review, prompt, inputHash);
        } catch (IntelligenceStore.Unready unfinished) {
            recordFailure(cycle, unfinished.code);
        } catch (Exception failure) {
            if (Thread.currentThread().isInterrupted()) throw failure;
            recordFailure(cycle, "CYCLE_UNAVAILABLE");
        }
    }

    private static String evidenceHash(ObjectNode prompt) {
        ObjectNode evidence = prompt.deepCopy();
        evidence.remove("localDate");
        // Collection timestamps describe freshness, while identical portfolio text is the same input.
        for (JsonNode page : evidence.path("portfolio")) ((ObjectNode) page).remove("at");
        return java.util.HexFormat.of().formatHex(FileStorage.hash(
                AtomicHttp.canonical(evidence).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static boolean unchanged(IntelligenceStore.State state, String hash, long now) {
        var action = state.snapshot().path("action");
        String deadline = action.path("deadline").asText();
        return action.isObject() && (deadline.isBlank() || deadline.compareTo(IntelligenceStore.day(now)) > 0)
                && state.snapshot().path("assessment").path("decisionVersion").asInt() == IntelligenceDecision.VERSION
                && now - state.number("lastReview") < 72 * HOUR && hash.equals(state.snapshot().path("inputHash").asText());
    }

    private void deferUntilNewEvidence(IntelligenceStore.Cycle cycle, long now) throws Exception {
        backend.write(c -> {
            var state = store.requireCycle(c, cycle, clock.getAsLong());
            long next = Math.min(state.number("profileDue"), state.number("searchDue"));
            state.control().put("due", Math.max(now + HOUR, Math.min(now + 24 * HOUR, next)))
                    .put("status", "READY").put("heartbeat", now).put("failures", 0)
                    .remove(List.of("lease", "leaseUntil", "error"));
            store.save(c, state, false);
            return null;
        });
    }

    private void refreshPortfolio(IntelligenceStore.Cycle cycle, IntelligenceStore.State state) throws Exception {
        long now = clock.getAsLong();
        if (state.number("profileDue") > now) return;
        ArrayNode pages;
        try {
            pages = backend.portfolio(now);
        } catch (IOException unavailable) {
            backend.write(c -> {
                var current = store.requireCycle(c, cycle, now);
                current.control().put("profileState", "UNAVAILABLE").put("profileDue", now + 2 * HOUR);
                store.save(c, current, false);
                return null;
            });
            return;
        }
        state.snapshot().set("portfolio", pages);
        backend.write(c -> {
            var current = store.requireCycle(c, cycle, clock.getAsLong());
            var data = store.load(c, true, true).snapshot();
            data.set("portfolio", pages);
            current.control().put("profileState", "OK").put("profileAt", now).put("profileDue", now + 24 * HOUR);
            store.save(c, new IntelligenceStore.State(current.control(), data), true);
            return null;
        });
    }

    private ObjectNode refreshWorld(IntelligenceStore.Cycle cycle, IntelligenceStore.State state) throws Exception {
        long now = clock.getAsLong();
        ObjectNode previous = state.snapshot().path("worldResearch") instanceof ObjectNode object ? object : JSON.createObjectNode();
        if (state.number("searchDue") > now) return previous;

        ObjectNode query = JSON.createObjectNode().put("date", IntelligenceStore.day(now))
                .put("region", "Worldwide, with UK and European developments when relevant")
                .put("priorPublicBriefing", previous.path("text").asText())
                .put("scanDepth", state.snapshot().path("assessment").path("researchNeeded").asBoolean() ? "verify leads and gaps" : "survey material new developments");
        ArrayNode publicTopics = query.putArray("publicPortfolioTopics");
        for (JsonNode page : state.snapshot().path("portfolio")) {
            if (!List.of("P3", "P4").contains(page.path("id").asText())) continue;
            publicTopics.add(IntelligenceInputs.bounded(page.path("text").asText()
                    .replaceAll("(?i)zhongjie\\s+huang|huang\\s+zhongjie", "[profile owner]"), 550));
        }
        ObjectNode result;
        try { result = IntelligenceInputs.publicScan(backend.search(cycle, query), now); }
        catch (IntelligenceStore.Unready unavailable) {
            if ("STALE".equals(unavailable.code) || "BUSY".equals(unavailable.code)) throw unavailable;
            backend.write(c -> {
                var current = store.requireCycle(c, cycle, clock.getAsLong());
                current.control().put("searchState", unavailable.code).put("searchDue", now + 3 * HOUR);
                store.save(c, current, false);
                return null;
            });
            // A paid request can fail while a recent confirmed briefing remains usable.
            return previous;
        }
        backend.write(c -> {
            var current = store.requireCycle(c, cycle, clock.getAsLong());
            var data = store.load(c, true, true).snapshot();
            data.set("worldResearch", result);
            current.control().put("searchState", "OK").put("searchAt", now).put("searchDue", now + 20 * HOUR);
            store.save(c, new IntelligenceStore.State(current.control(), data), true);
            return null;
        });
        return result;
    }

    private void publish(IntelligenceStore.Cycle cycle, IntelligenceDecision.Review review, ObjectNode prompt, String inputHash) throws Exception {
        backend.write(c -> {
            long now = clock.getAsLong();
            var guard = store.requireCycle(c, cycle, now);
            var state = store.load(c, true, true);
            long memoryRevision = prompt.path("memoryRevision").asLong(-1);
            if (memoryRevision >= 0) AccountMemories.requireEnabledRevision(c, config.user, config.generation, memoryRevision);
            ObjectNode assessment = review.assessment();
            JsonNode previous = state.snapshot().path("assessment");
            String previousAction = state.snapshot().path("action").path("id").asText();
            String nextAction = review.action() == null ? "" : review.action().path("id").asText();
            if (!previousAction.equals(nextAction)) store.cancelPendingMail(c);

            String learning = assessment.path("learning").asText();
            String priorLearning = previous.path("learning").asText();
            long privacy = state.number("privacyRevision");
            String version = previous.path("policyVersion").asText();
            boolean priorMemory = state.snapshot().path("usedMemory").asBoolean();
            boolean permissionChanged = priorMemory && !prompt.has("memoryRevision");
            if (!learning.equals(priorLearning) || permissionChanged) {
                version = IntelligenceConfig.id();
                store.event(c, new IntelligenceStore.Event(version, "POLICY", now,
                        JSON.createObjectNode().put("learning", learning).put("privacyRevision", privacy)
                                .put("usedMemory", prompt.has("memoryRevision"))));
            }
            assessment.put("policyVersion", version);
            state.snapshot().set("assessment", assessment);
            state.snapshot().put("usedMemory", prompt.has("memoryRevision")).put("inputHash", inputHash);
            if (review.action() == null) state.snapshot().remove("action");
            else state.snapshot().set("action", review.action());

            if (!previousAction.equals(nextAction) || !IntelligenceStore.day(now).equals(state.value("loggedReviewDay"))
                    || !assessment.path("reportedOutcome").equals(previous.path("reportedOutcome"))) {
                ObjectNode event = JSON.createObjectNode().set("assessment", assessment);
                if (review.action() != null) event.set("action", review.action());
                store.event(c, new IntelligenceStore.Event(cycle.id(), "REVIEW", now, event));
                guard.control().put("loggedReviewDay", IntelligenceStore.day(now));
            }

            if (guard.control().path("email").asBoolean() && review.action() != null && !"none".equals(review.email())) {
                ObjectNode payload = IntelligenceMail.action(review.action(), config)
                        .put("urgent", "urgent".equals(review.email()));
                store.queue(c, nextAction, now, payload);
            }
            long interval = (review.researchNeeded() ? Math.min(12, review.hours()) : review.hours()) * HOUR;
            guard.control().put("status", "READY").put("lastReview", now).put("due", now + interval)
                    .put("failures", 0).put("heartbeat", now).remove(List.of("lease", "leaseUntil", "error"));
            store.save(c, new IntelligenceStore.State(guard.control(), state.snapshot()), true);
            return null;
        });
    }

    private void recordFailure(IntelligenceStore.Cycle cycle, String error) throws Exception {
        backend.write(c -> {
            long now = clock.getAsLong();
            var state = store.load(c, true, false);
            if (!store.valid(state, cycle, now) || !store.ownerActive(c)) return null;
            int failures = Math.min(10, state.control().path("failures").asInt() + 1);
            long delay = switch (error) {
                case "BUSY", "STALE" -> 3 * MINUTE;
                case "MODEL_KEY" -> 6 * HOUR;
                case "BUDGET" -> Math.max(MINUTE, Instant.ofEpochMilli(now).atZone(IntelligenceConfig.ZONE)
                        .toLocalDate().plusDays(1).atStartOfDay(IntelligenceConfig.ZONE).toInstant().toEpochMilli() - now);
                default -> Math.min(6 * HOUR, (15L << (failures - 1)) * MINUTE);
            };
            if (failures == 3 && !List.of("BUSY", "STALE", "BUDGET").contains(error)
                    && state.control().path("email").asBoolean()) {
                store.queue(c, cycle.id(), now, IntelligenceMail.incident(config).put("incident", true));
            }
            state.control().put("status", "WAITING").put("error", error).put("failures", failures)
                    .put("due", now + delay).put("heartbeat", now).remove(List.of("lease", "leaseUntil"));
            store.save(c, state, false);
            return null;
        });
    }

    private record Context(ObjectNode personal, List<String> files) {}

    private void processMail(IntelligenceConfig.Mail transport) throws Exception {
        long now = clock.getAsLong();
        IntelligenceStore.Mail send = backend.write(c -> {
            var state = store.load(c, true, true);
            if (state == null || !state.enabled() || !state.control().path("email").asBoolean() || !store.ownerActive(c)
                    || state.number("leaseUntil") > now) return null;
            var mail = store.dueMail(c, now, true);
            if (mail == null) return null;
            boolean incident = mail.payload().path("incident").asBoolean();
            var action = state.snapshot().path("action");
            String deadline = action.path("deadline").asText();
            boolean expired = !deadline.isBlank() && deadline.compareTo(IntelligenceStore.day(now)) < 0;
            if (incident ? state.number("failures") < 3 : expired || !mail.action().equals(action.path("id").asText())) {
                updateMail(c, mail, mail.attempts() == 0 ? "CANCELLED" : "UNKNOWN", now);
                return null;
            }
            long first = mail.firstAttempt() == 0 ? now : mail.firstAttempt();
            if (mail.credentialHash() != null && !transport.transportId.equals(mail.credentialHash())) {
                updateMail(c, mail, "UNKNOWN", now);
                return null;
            }
            // A process can exit after QQ accepted DATA and before our receipt committed.
            if (transport.qqMailbox && "DISPATCHING".equals(mail.state())) {
                updateMail(c, mail, "VERIFYING", now);
                return null;
            }
            if (mail.attempts() >= 3 || now - first >= IntelligenceMail.RETRY_WINDOW) {
                updateMail(c, mail, "UNKNOWN", now);
                return null;
            }
            long allowed = IntelligenceMail.allowedAt(now, mail.attempts() == 0 ? state.number("lastMailAttempt") : 0,
                    mail.payload().path("urgent").asBoolean());
            if (allowed > now) {
                updateMail(c, mail, mail.state(), allowed);
                return null;
            }
            store.setMail(c, mail, "DISPATCHING", now + 5 * MINUTE, first, mail.attempts() + 1, null, transport.transportId);
            if (mail.attempts() == 0) {
                state.control().put("lastMailAttempt", now);
                store.save(c, state, false);
            }
            return new IntelligenceStore.Mail(mail.id(), mail.action(), "DISPATCHING", mail.created(), now + 5 * MINUTE,
                    first, mail.attempts() + 1, null, transport.transportId, mail.payload());
        });
        if (send != null) dispatch(send, transport);
        else checkDelivery(transport, now);
    }

    private void dispatch(IntelligenceStore.Mail mail, IntelligenceConfig.Mail transport) throws Exception {
        IntelligenceMail.Result result;
        try {
            backend.stillAuthorized(transport);
            boolean stillPending = backend.read(c -> {
                var state = store.load(c, false, false);
                return state != null && state.enabled() && state.control().path("email").asBoolean()
                        && store.ownerActive(c) && store.dispatchCurrent(c, mail);
            });
            if (!stillPending || Thread.currentThread().isInterrupted()) return;
            result = backend.send(mail, transport);
        } catch (IntelligenceStore.Unready revoked) {
            return;
        } catch (Exception unknown) {
            result = new IntelligenceMail.Result(transport.qqMailbox ? "VERIFYING" : "RETRY", null);
        }
        var receipt = result;
        backend.write(c -> {
            String next = "RETRY".equals(receipt.state()) && mail.attempts() >= 3 ? "UNKNOWN" : receipt.state();
            long delay = "RETRY".equals(next) ? (mail.attempts() == 1 ? 5 : 25) * MINUTE
                    : transport.qqMailbox ? 20_000 : 15 * MINUTE;
            store.setMail(c, mail, next, clock.getAsLong() + delay, mail.firstAttempt(), mail.attempts(),
                    receipt.provider(), mail.credentialHash());
            return null;
        });
    }

    private void checkDelivery(IntelligenceConfig.Mail transport, long now) throws Exception {
        var mail = backend.write(c -> {
            var state = store.load(c, true, false);
            if (state == null || !state.enabled() || !state.control().path("email").asBoolean() || !store.ownerActive(c)) return null;
            var next = store.dueMail(c, now, false);
            if (next == null) return null;
            if (!transport.transportId.equals(next.credentialHash())) {
                updateMail(c, next, "UNCONFIRMED", now);
                return null;
            }
            updateMail(c, next, next.state(), now + HOUR);
            return next;
        });
        if (mail == null) return;
        String status;
        try {
            backend.stillAuthorized(transport);
            status = backend.delivery(mail, transport);
        } catch (Exception unavailable) { status = mail.state(); }
        long age = now - mail.firstAttempt();
        String receipt = age > 3 * 24 * HOUR && List.of("ACCEPTED", "DELAYED", "VERIFYING").contains(status)
                ? "UNCONFIRMED" : status;
        long interval = transport.qqMailbox && age < 15 * MINUTE ? MINUTE : HOUR;
        backend.write(c -> { updateMail(c, mail, receipt, now + interval); return null; });
    }

    private void updateMail(java.sql.Connection c, IntelligenceStore.Mail mail, String status, long due) throws Exception {
        store.setMail(c, mail, status, due, mail.firstAttempt(), mail.attempts(), mail.provider(), mail.credentialHash());
    }
}
