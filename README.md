# Star Hub

> **Current deployment.** Both application hosts share the single production MySQL instance on Server 2. Each entrance serves its local application, with no automatic application or database failover. See local OneDrive `Linux_Server_2026-11-13/REBUILD_AND_OPERATIONS.md` for current operations.

A bilingual workspace for calling official AI model APIs from one website. Built with Java, plain HTML/CSS/JavaScript and a WAR deployed in an existing Tomcat 11 runtime.

[Open Star Hub](https://starhub.zhongjiehuang.com) · [Portfolio](https://www.zhongjiehuang.com/projects/star-hub/) · [Model catalogue](docs/model-catalog.md)

## What you can do

- Choose from 7 official providers and 15 curated integration entries: OpenAI, Anthropic Claude, Google Gemini, DeepSeek, Alibaba Qwen, Moonshot Kimi and xAI. The picker focuses on recent chat models and keeps selected image, speech, transcription, embedding and realtime tools.
- Chat with progressively revealed streaming replies, use image understanding, and enable provider-supported web search where available.
- Use a temporary guest session or sign in to an existing account to save conversations, files and encrypted provider keys. Registration is closed.
- Generate images or speech, transcribe audio and create embeddings through the implemented OpenAI endpoints.
- Submit OpenAI JSONL batch jobs, recover paginated job lists, inspect status, retrieve output/error files and export results.
- Use OpenAI realtime voice with microphone input, audio playback, interruption and explicit session controls.
- See only the settings supported by the selected model, with a second validation step on the server.
- Access provider key-management links and supported model controls. The home page keeps API call code out of the main interface; separately available source remains inspectable.

The current catalogue uses the manufacturers' own official APIs. Third-party model gateways, hosted copies of other manufacturers' models and custom endpoint URLs are rejected by the backend. Unsupported video, music, 3D and other former gateway-only integrations are not presented as callable features.

Every current integration requires the user's own provider API key. Account access, regional availability, free credits and billing are controlled by the provider. A catalogue entry does not guarantee that every account can call it or that every model is covered.

## Getting started

1. Open the website. English is the default; switch to Chinese in the guide or page header.
2. Choose an official provider, follow its key-management link and enter your own API key.
3. Check the connection and select a model. Connection details can stay collapsed while the main workspace remains focused on use.

Language, connection-panel state and the last model selected for each provider are remembered in the current browser. An unavailable saved model falls back to a current selection.

The connection check only requests the official model list; it does not generate paid text or media. Acceptance of a key does not establish model entitlement or sufficient balance. Qwen's Beijing model-list check needs an additional workspace address and is explicitly shown as unavailable. Network or unsupported-check responses can be skipped; invalid-key responses require a corrected key.

## Privacy and inspectable calls

The request path is **browser → this site's Java service → the selected manufacturer's official API**. An explicit destination allowlist is used and upstream redirects are disabled.

Guest conversations, attachments and provider keys are encrypted in the shared SQL guest store and associated with the current server session and browser tab, so they can be restored on refresh. Tomcat replicates only an immutable random guest identity, while the shared SQL gate protects concurrent updates, staging, idempotent results and global capacity. Access expires after ten minutes of inactivity; encrypted rows and operation receipts remain until explicit maintenance and are not physically erased when a tab closes. A still-valid Tomcat session can explicitly renew its guest lease without discarding pending operations. The tab identifier and preferences are stored in the browser. Necessary cookies support sessions and sign-in. Legacy opt-in local chat history is migrated when present. Guest data is separate from account data unless the user chooses to import it.

Signed-in accounts can save conversations and files in the account workspace and store provider keys in an encrypted vault. The server needs its private database configuration and vault key to access this data. Generated images are saved to the account library when supported. File-library limits are 2 GB (2,000,000,000 bytes) per account, including trash and in-flight reservations, and 4 MiB per uploaded file. Generated images retain their separate 12 MiB per-image limit. The account quota returned by the workspace API drives the library display.

Every model/search/batch/realtime intent now carries a durable request UUID. Confirmed CLAIMED and SENT receipts precede external dispatch; SENT/UNKNOWN are queried rather than automatically replayed. Results are encrypted, bounded and recoverable by the same owner when retained. A browser keeps the original UUID until its displayed result is separately saved with account/guest durability confirmation. Realtime audio remains a live stream and is not replayed after a crash. This is not an exactly-once billing guarantee from a provider. Keep provider receipts and encryption configuration with the database and its protected backup.

The source viewer exposes 24 actual Java and JavaScript files with SHA-256 fingerprints. Maven bundles the current files unchanged under `WEB-INF/classes/call-source/`. `GET /api/source` lists them; `GET /api/source?file=server/ModelGateway.java` reads an allowlisted file. The endpoint does not read arbitrary server files, environment variables or credentials.

## Build and test

Requirements: JDK 17+, Python 3, Maven 3.9+ and Node.js 20+ for frontend tests. The build helper can use an installed PyCharm JBR and the included Maven distribution.

```sh
./scripts/build.sh
```

The output is `target/starhub.war`. The current Java verification passed 297 tests with one skip, and 149 JavaScript tests passed. Current verification covers the actual single-database deployment and synthetic file lifecycle; it does not establish that two users can each fill their entire 2 GB quota concurrently, a blank-machine rebuild, or automatic database failover.

## Deployment and transactions

Both hosts run the application against Server 2's single production MySQL; Server 1 uses the restricted SSH tunnel and JDBC TLS identity verification. Each entry serves its local Tomcat. The existing private Tomcat session replication is retained for session and guest identity behavior, but it is not automatic application routing or database failover. Database unavailability affects database-backed operations on both hosts.

Files and their bytes, metadata, trash, quota reservations and operation state are stored in the shared database. Atomic transactions, a shared guard, immutable identity generations and operation UUIDs protect concurrent work. Unknown outcomes retain their original IDs and are queried before any retry; provider billing is not guaranteed exactly once. The bounded per-JVM connection pool has at most four physical connections with a 64-entry prepared-statement cache.

Each account has a 2,000,000,000-byte library quota including trash and pending reservations. The disk safety floor is 4,000,000,000 bytes, with physical availability checked by the capacity admission mechanism; privileged system activity may cross a filesystem reservation. Uploads can be rejected before the account quota if disk capacity is insufficient. Two simultaneously near-full quotas have not been validated by a full physical fill test.

See [storage and recovery](docs/distributed-storage.md) for the current data and operation contract. Use the current `Linux_Server_2026-11-13/REBUILD_AND_OPERATIONS.md` in local OneDrive and the reviewed IAFM `ops/single-database` tools. Preserve protected database configuration, vault keys, TLS, identity/session state and a verified recovery copy; do not place credentials in the source or deployment archive.

### Regional tester access

The server checks the visitor's IP against DB-IP Country Lite. Visitors identified as CN, HK or MO must enter the tester password; an unknown location also requires verification. The password is checked on the server and grants access for the current Tomcat session. Do not put it in source code, the WAR, or Nginx access logs.

The current entry configuration controls trusted client-IP handling. The application uses its privately configured country database and reloads an atomically replaced file. IP geolocation is approximate and can be affected by VPNs and proxies.

The tester credential is maintained in the protected server configuration and is not stored in the project or WAR. If the country database cannot be read, the site requires tester verification; if the password is missing, restricted or unknown locations receive HTTP 503. DB-IP Country Lite attribution is displayed in the site and verification page.

Health: `GET /api/health`. JSON endpoints `POST /api/run`, `POST /api/batch` and `POST /api/check` require `X-Starhub-Client: web`. `STARHUB_PUBLIC_ORIGIN` can configure the permitted site origin; it cannot add third-party API destinations.

## Repository contents

| Path | Purpose |
| --- | --- |
| `src/main/java/com/starhub/` | Servlets, provider protocols, streaming, media, batches, realtime voice, parameter validation and source access |
| `src/main/webapp/` | Bilingual interface, styles and browser interactions |
| `src/main/resources/` | Generated catalogue and official platform links |
| `src/test/` | Java and JavaScript regression tests |
| `research/official-models.json` | Reproducible model catalogue with per-model settings and references |
| `scripts/` | Catalogue generation, build and deployment |
| `deploy/` | Nginx and certificate-renewal configuration |
| `docs/` | Current catalogue, settings and verification notes |
| `.tools/` | Maven build distribution, including its third-party notices |
| `target/starhub.war` | Current deployment artifact |

This repository mirrors the current local project files, including the build tools and latest WAR. Transient build output, operating-system metadata, logs and private environment files are excluded. English documentation accompanies the deliberately bilingual application.

### File storage and recovery

Account file content and metadata live in the shared MySQL-backed storage. Quota includes trash and pending reservations; an uncertain operation retains its original ID and reservation until its state is confirmed or reconciled. Keep data, operation receipts and required encryption keys together when taking a backup. A verified encrypted backup is a point-in-time recovery copy, not a second live replica.

### Deployment identity and browser origins

The private `/var/lib/codex-validation/app-config/starhub.properties` accepts `server.id=1` or `server.id=2` and `public.origins` as a comma-separated list of one or two exact HTTP(S) origins, without trailing slashes or paths. `GET api/server` returns only the numeric server ID and disables caching; missing or invalid identity configuration returns 503. HTTP actions and WebSocket handshakes use the same explicit origin allowlist. `STARHUB_SERVER_ID` and `STARHUB_PUBLIC_ORIGINS` are equivalent deployment environment overrides; the existing single-origin setting remains supported.

File lists are paginated with `page` and `pageSize` (maximum 100); responses include `page`, `pageSize`, `total`, `hasNext`, and the account-wide `used`/`quota` bytes, including trash and durable pending reservations. Pending uploads have a separate paginated list and explicit cancellation control.


## Identifier reuse

See [identifier reuse policy](docs/identifier-reuse.md) for the current safe numeric-ID scope, transaction and generation requirements, and identifiers that must not be recycled.
