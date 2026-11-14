# Model settings

The browser and server share per-model parameter definitions. The backend rejects unknown fields, out-of-range values and invalid conditional combinations. Omitted values use provider defaults.

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
