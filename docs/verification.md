# Connection experience and bilingual release verification — 28 October 2026

This document records the checks and catalogue as they were on 28 October 2026; it is historical evidence, not a description of the current release. For current behaviour and the latest local test results, see the README.

## Release behaviour

A three-step first-connection guide, collapsed configuration with remembered state, remembered provider/model selection and English-by-default language switching are implemented. All 60 official integration entries have English descriptions. Advanced settings, catalogue, batch controls, realtime voice, errors and source-viewer labels support both languages. Official request URLs and all 24 source files remain accessible when configuration is collapsed.

Chat persistence is off by default. Opt-in storage retains text, titles and drafts; disabling it removes the local saved copy without clearing the active page. API keys, attachments, raw results and temporary objects are excluded. Recovery explains missing attachments and unfinished replies. Storage failures or size limits do not overwrite the previous successful record.

Connection checks only request official model lists. Qwen Beijing explicitly skips the check because a workspace URL is required. HTTP 401/403 rejects the key; 404/405 and network failures allow retry or continuing without verification. Third-party providers, custom destinations and redirects are rejected. Upstream error bodies are not forwarded.

## Automated checks

`./scripts/build.sh` passed 39 Java tests and 6 Node.js tests. Coverage includes official protocols, SSE, media, batches, realtime bridging, model parameters, destination allowlists, bundled-source equality, non-generating connection checks, authentication, error redaction, storage opt-in and field allowlists, corrupt/oversized data, model memory, default language and English catalogue coverage.

All browser JavaScript files passed syntax checks. The WAR contains 7 official providers, 60 integration entries and 24 source files matching the local source.

## Local browser checks

- First visit showed the English guide and correct DeepSeek key-management link.
- Simulated invalid credentials prevented completion; corrected simulated credentials opened the collapsed main interface.
- Chinese switching, Qwen's unavailable-check explanation and the continue action worked.
- Simulated streaming replies appeared progressively. Switching language retained original chat text, draft, key, model and parameter values.
- DeepSeek V4 Pro with a 500-token setting retained that value across language switching.
- Opt-in saving followed by refresh restored conversations, draft, model and Chinese language, while clearing the API key.
- Delete and undo worked. Disabling saving kept the current page's conversation but did not restore it after refresh.
- The initial 24 English catalogue cards, filters and help were inspected; tests traversed labels across all 60 entries.
- Official URLs and source access remained usable with settings collapsed. The source viewer returned file content and SHA-256 fingerprints.

## Live deployment evidence

On 28 October 2026 (UTC), Tomcat stopped at 12:06:23, file logs were cleared and installation began at 12:06:24, startup followed the 30-second wait at 12:06:54, and health passed at 12:07:13.

Local and server WAR SHA-256:

```text
095a718428d9fc0bfcc860e9cdfe914b3f68e578a77622d5e8f77cf3f2069d46
```

Tomcat and Nginx were active with one Java process. ROOT, Star Hub, Marginloom, OutcomeLab and Investment_and_Financial_Management returned HTTP 200. Deployed HTML/styles/scripts matched the local build; all 60 entries had English descriptions and all 24 exposed source files matched their local contents and fingerprints.

Live `/api/check` rejected removed providers and empty keys with HTTP 400; Qwen returned explicit `unsupported`. These checks did not generate content or validate real credentials. The production browser showed the English guide, Chinese switching and correct DeepSeek key link. Refresh retained DeepSeek V4 Pro while clearing the key, leaving configuration collapsed, saving disabled and the source viewer closed. The source viewer showed 24 files and hashes; no browser errors were captured.

## Evidence limits

Interaction checks used simulated upstreams. This release's verification did not use a real API key or issue billed generation, voice or batch requests. A successful key check does not prove every model's permission, balance or availability. Language switching translates interface labels, not user/model content or source files.
