# Official model catalogue

The current catalogue contains 62 integration entries using the manufacturers' own APIs. Ordinary and batch entries are listed separately. Entitlements, free credits and billing depend on the official account.

| Official provider | Entries | Base URL |
| --- | ---: | --- |
| OpenAI | 22 | https://api.openai.com/v1 |
| Anthropic Claude | 9 | https://api.anthropic.com/v1 |
| Google Gemini | 14 | https://generativelanguage.googleapis.com/v1beta |
| DeepSeek | 2 | https://api.deepseek.com/v1 |
| Alibaba Qwen | 7 | https://dashscope.aliyuncs.com/compatible-mode/v1 |
| Moonshot Kimi | 6 | https://api.moonshot.cn/v1 |
| xAI | 2 | https://api.x.ai/v1 |

Third-party hosting platforms, model gateways and custom URLs are excluded. Implemented capabilities include text, image understanding, image generation, speech synthesis, audio transcription, embeddings, OpenAI batch processing and realtime voice. Other former gateway-only modalities are not displayed as supported integrations.

`research/official-models.json` is the editable source. Each entry carries official reference links and a review date. The builder checks that the provider matches the model's manufacturer. The same Java `ApiRoutes` definitions drive outbound requests and displayed addresses.

OpenAI media, batch and realtime additions were reviewed against official documentation on 28 October 2026. They were not individually validated with real credentials. Existing chat entries retain their own review dates. The catalogue excludes the officially retired gpt-4.1-nano and uses GPT Image 2.5 for image generation.

English is the default interface language, with Chinese available. Every integration has both descriptions: `descriptionEn` contains English and `description` contains Chinese. Model IDs, request URLs and parameter values remain unchanged when switching languages.
