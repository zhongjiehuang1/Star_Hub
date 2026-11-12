# Model settings — 28 October 2026

The browser and server share per-model parameter definitions from the catalogue. The interface shows supported fields only. The backend rejects unknown fields, out-of-range values and invalid conditional combinations. Changing models or tasks resets settings; omitted values use provider defaults.

| Capability | Implemented controls |
| --- | --- |
| Chat | Seven providers; native authentication, reasoning, sampling, output limits and structured output, subject to each model's constraints |
| Images | GPT Image 2.5 Sunburst / Flare; size, quality, background, output format, compression and count; text-to-image only |
| Speech | gpt-4o-mini-tts; official voices, speed, style and audio format |
| Transcription | gpt-4o-transcribe, gpt-4o-mini-transcribe and whisper-1; language, prompt, temperature and per-model formats |
| Embeddings | text-embedding-3-small / large; dimensions and encoding |
| Batches | GPT-4.1 / Mini and text-embedding-3-small / large; corresponding model settings |
| Realtime voice | gpt-realtime-2.1; voice, instructions, output limit and noise reduction; fixed PCM format |

The source viewer includes `Parameters.java`, the actual request adapters and `parameters.js`. Catalogue details provide official parameter references. Parameter-contract tests do not establish an account's live model entitlements.
