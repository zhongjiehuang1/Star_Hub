# Model settings

The browser and server share per-model parameter definitions. The backend rejects unknown fields, out-of-range values and invalid conditional combinations. Omitted values use provider defaults.

The current provider, model, task, reasoning effort and web-search choice are remembered in browser-local preferences. Non-file model parameter fields are retained for a refresh in the same tab; switching the account, model or task resets those fields. These tab-local parameter values can include text or URLs and are not synchronized to the account. File inputs are not restored as model parameter settings. If browser storage is unavailable, a save error is shown when a setting is changed.

| Capability | Implemented controls |
| --- | --- |
| Chat | Seven official providers; model-specific input, reasoning, output and search controls |
| Images | GPT Image 2.6 Sunburst; text-to-image and supported settings |
| Speech | xAI Voice API; voice and speed, xAI API key required |
| Transcription | GPT-Transcribe; audio upload |
| Embeddings | text-embedding-3-large; dimensions and encoding |
| Batch | GPT-6.1 Sol and text-embedding-3-large |
| Realtime voice | GPT-Realtime-2.1; voice and realtime controls |

The xAI Voice API contract was tested with local stubs, not a live billed request. Account availability and access must be verified separately.
