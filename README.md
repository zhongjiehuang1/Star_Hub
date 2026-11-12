# Star Hub

A bilingual workspace for calling official AI model APIs from one website. Built with Java, plain HTML/CSS/JavaScript and a WAR deployed in an existing Tomcat 11 runtime.

[Open Star Hub](https://starhub.zhongjiehuang.com) · [Portfolio](https://www.zhongjiehuang.com/projects/star-hub/) · [Model catalogue](docs/model-catalog.md) · [Verification notes](docs/verification.md)

## What you can do

- Choose from 7 official providers and 62 curated integration entries: OpenAI, Anthropic Claude, Google Gemini, DeepSeek, Alibaba Qwen, Moonshot Kimi and xAI. The picker focuses on recent chat models and keeps selected image, speech, transcription, embedding and realtime tools.
- Chat with progressively revealed streaming replies, use image understanding, and enable provider-supported web search where available.
- Use a temporary guest session or sign in to an existing account to save conversations, files and encrypted provider keys. Registration is closed.
- Generate images or speech, transcribe audio and create embeddings through the implemented OpenAI endpoints.
- Submit OpenAI JSONL batch jobs, recover paginated job lists, inspect status, retrieve output/error files and export results.
- Use OpenAI realtime voice with microphone input, audio playback, interruption and explicit session controls.
- See only the settings supported by the selected model, with a second validation step on the server.
- Inspect official API addresses, key-management links and the actual Java/JavaScript call source in a normally closed, read-only viewer.

The current catalogue uses the manufacturers' own official APIs. Third-party model gateways, hosted copies of other manufacturers' models and custom endpoint URLs are rejected by the backend. Unsupported video, music, 3D and other former gateway-only integrations are not presented as callable features.

Every current integration requires the user's own provider API key. Account access, regional availability, free credits and billing are controlled by the provider. A catalogue entry does not guarantee that every account can call it or that every model is covered.

## Getting started

1. Open the website. English is the default; switch to Chinese in the guide or page header.
2. Choose an official provider, follow its key-management link and enter your own API key.
3. Check the connection and select a model. Connection details can stay collapsed while the API address and source viewer remain accessible.

Language, connection-panel state and the last model selected for each provider are remembered in the current browser. An unavailable saved model falls back to a current selection.

The connection check only requests the official model list; it does not generate paid text or media. Acceptance of a key does not establish model entitlement or sufficient balance. Qwen's Beijing model-list check needs an additional workspace address and is explicitly shown as unavailable. Network or unsupported-check responses can be skipped; invalid-key responses require a corrected key.

## Privacy and inspectable calls

The request path is **browser → this site's Java service → the selected manufacturer's official API**. An explicit destination allowlist is used and upstream redirects are disabled.

Guest conversations, attachments and provider keys are encrypted in the replicated SQL guest store and associated with the current server session and browser tab, so they can be restored on refresh. Tomcat replicates only an immutable random guest identity, while the shared SQL gate protects concurrent updates, staging, idempotent results and global capacity. Access expires after ten minutes of inactivity; encrypted rows and operation receipts remain until explicit maintenance and are not physically erased when a tab closes. A still-valid Tomcat session can explicitly renew its guest lease without discarding pending operations. The tab identifier and preferences are stored in the browser. Necessary cookies support sessions and sign-in. Legacy opt-in local chat history is migrated when present. Guest data is separate from account data unless the user chooses to import it.

Signed-in accounts can save conversations and files in the account workspace and store provider keys in an encrypted vault. The server needs its private database configuration and vault key to access this data. Generated images are saved to the account library when supported. File-library limits are 2 GB (2,000,000,000 bytes) per account, including trash and in-flight reservations, and 4 MiB per uploaded file. Generated images retain their separate 12 MiB per-image limit. The account quota returned by the workspace API drives the library display.

Every model/search/batch/realtime intent now carries a durable request UUID. Confirmed CLAIMED and SENT receipts precede external dispatch; SENT/UNKNOWN are queried rather than automatically replayed. Results are encrypted, bounded and recoverable by the same owner when retained. A browser keeps the original UUID until its displayed result is separately saved with account/guest durability confirmation. Realtime audio remains a live stream and is not replayed after a crash. This is not an exactly-once billing guarantee from a provider. See [the provider receipt and JVM startup requirements](docs/distributed-storage.md#durable-provider-operation-receipts).

The source viewer exposes 24 actual Java and JavaScript files with SHA-256 fingerprints. Maven bundles the current files unchanged under `WEB-INF/classes/call-source/`. `GET /api/source` lists them; `GET /api/source?file=server/ModelGateway.java` reads an allowlisted file. The endpoint does not read arbitrary server files, environment variables or credentials.

## Build and test

Requirements: JDK 17+, Python 3, Maven 3.9+ and Node.js 20+ for frontend tests. The build helper can use an installed PyCharm JBR and the included `.tools/apache-maven-3.9.9` distribution. These are local build tools; deployment shares the existing Tomcat JVM.

```sh
./scripts/build.sh
```

Output: `target/starhub.war`. The build regenerates the catalogue from `research/official-models.json`, validates official-provider ownership, runs tests and packages the application and inspectable source.

On 8 November 2026 the build passed **80 Java tests and 16 JavaScript tests**; one additional Java test was skipped because the local GeoIP database was not configured. Coverage includes distributed file placement and quota rules, recovery initialization, request deadlines, retry identities, protocol adapters, streaming, official routing, source access, connection checks and browser storage. Separate real MySQL tests exercised transaction crash recovery and migration preservation; production HTTPS checks verified both entrances and physical file placement using a temporary account. No paid provider requests were made. See [distributed storage verification](docs/distributed-storage.md#verification-on-8-november-2026) for historical evidence and [historical verification notes](docs/verification.md) for earlier browser checks.

## Current validation status (11 November 2026)

The source targets Jakarta Servlet 6.1, Jakarta WebSocket 2.2 and Tomcat 11 with Java 17 or later, using MySQL Connector/J 26.10.0. The file-library quota is exactly 2,000,000,000 bytes per account, including trash and durable pending uploads; the only free-space floor is 4,000,000,000 bytes. The current atomic candidate passed `mvn verify`: 173 Java tests, zero failures/errors and one existing GeoIP skip, plus 47 browser JavaScript tests. These include the unchanged 64 MiB logical file batch, exact byte readback, a 12,000,000 UTF-16-code-unit guest field rewrite and retained-budget cleanup, plus actual local SQL with explicitly simulated GR authority/GTID/capacity adapters; they do not establish real cross-host replication, physical quota capacity, fencing or automatic failover. Both isolated canonical schemas carry the durable reservation and encrypted guest migrations. The new provider/chunk migrations 011–013 and matching protocol-5 WAR require reviewed online deployment and real acceptance; the strict real-MySQL/controller harness is available. Keep formal writes closed until the actual two-host fault and capacity matrix passes.

Earlier isolated integration phases (`basic`, `random-integrity`, `rollback`, `quota-reservation`) passed separately on both servers against MySQL 26.10.0 and Connector/J 26.10.0 before the atomic redesign. They used synthetic capacity/health observers and legacy XA. Those results are historical and are not acceptance of the current multi-primary protocol.

## Deploy in the existing Tomcat 11

The deployment helper requires an existing Nginx, Tomcat 11, cron service, the domain certificate and a Tomcat HTTP connector bound exclusively to `127.0.0.1:8080`. Account features also require the existing MySQL database, migrations and private `/var/lib/starhub/account-config.properties` (including the vault key); preserve these across deployments. Check that other applications can use this connector before changing it: the helper refuses a non-loopback connector and does not modify the shared Tomcat configuration. Upload the current files to a staging directory:

```sh
scp target/starhub.war scripts/deploy-server.sh scripts/update-geoip.sh deploy/nginx.conf deploy/starhub-proxy.conf root@SERVER:/opt/starhub/staging/
ssh -t root@SERVER 'bash /opt/starhub/staging/deploy-server.sh /opt/starhub/staging'
```

The script downloads and validates the country database before changing the live release, then backs up the previous Star Hub deployment and affected configuration under `/var/backups/starhub/`. It installs the database updater and daily cron job, checks Nginx, restarts shared Tomcat, and verifies the region gate, login and WebSocket via HTTPS. Deployment and database refresh share a lock. The checks use fixed sample IPs for the UK, mainland China, Hong Kong and Macau; if their database classifications change, deployment fails and attempts rollback until the samples are updated. On failure it attempts to restore the previous files and service configuration. **It does not clear, truncate or delete Tomcat logs.** Restarting shared Tomcat temporarily interrupts its other applications; only the Star Hub WAR is replaced.

### Regional tester access

The server checks the visitor's IP against DB-IP Country Lite. Visitors identified as CN, HK or MO must enter the tester password; an unknown location also requires verification. The password is checked on the server and grants access for the current Tomcat session. Do not put it in source code, the WAR, or Nginx access logs.

The included Nginx configuration overwrites `X-Real-IP` with `$remote_addr` for both regular requests and `/realtime`. The deployment helper installs `scripts/update-geoip.sh`, initializes `/var/lib/starhub/dbip-country-lite.mmdb` and schedules a daily update; a failed scheduled download leaves the old database in place. The app reloads an atomically replaced file automatically. IP geolocation is approximate and can be affected by VPNs and proxies.

The helper preserves an existing `/etc/starhub/access.env` or, if absent, prompts twice for the tester password without echoing it. For the environment-file format it accepts 8–128 letters, digits, dots, underscores or hyphens. It sets root-only file permissions, configures a separate Tomcat systemd drop-in, and never stores the password in the project or WAR. Keep the environment file private. If the database cannot be read, the site requires tester verification instead of treating the location as unrestricted; if the password is missing, restricted or unknown locations receive HTTP 503. DB-IP Country Lite attribution is displayed in the site and verification page.

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

### Distributed file storage

The current two-member candidate requires the atomic local-InnoDB path, MySQL Group Replication multi-primary mode, durable upload reservations, controller permits, GTID persistence confirmation, reliable fencing and the migration/proxy contract in [distributed storage deployment](docs/distributed-storage.md). Both physical members accept coordinated writes; legacy XA is restricted to disposable loopback test schemas. Unknown operations retain their UUID and pending quota until explicit completion or cancellation. Keep formal writes closed until the real deployment and fault matrix pass.

### Deployment identity and browser origins

The private `/var/lib/starhub/account-config.properties` accepts `server.id=1` or `server.id=2` and `public.origins` as a comma-separated list of one or two exact HTTP(S) origins, without trailing slashes or paths. `GET api/server` returns only the numeric server ID and disables caching; missing or invalid identity configuration returns 503. HTTP actions and WebSocket handshakes use the same explicit origin allowlist. `STARHUB_SERVER_ID` and `STARHUB_PUBLIC_ORIGINS` are equivalent deployment environment overrides; the existing single-origin setting remains supported.

File lists are paginated with `page` and `pageSize` (maximum 100); responses include `page`, `pageSize`, `total`, `hasNext`, and the account-wide `used`/`quota` bytes, including trash and durable pending reservations. Pending uploads have a separate paginated list and explicit cancellation control.
