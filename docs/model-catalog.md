# Official model catalogue

The catalogue lists reviewed, officially hosted integrations across seven providers. Each supported creation direction presents a flagship and, when a second documented compatible choice exists, an alternative. The alternative is preferred for a new selection; an existing usable choice remains selected. Labels describe reviewed selections, not a universal performance ranking or guaranteed price. An unavailable direction is hidden until an integrated model exists. DeepSeek currently has one verified selection.

The model library and task picker do not expose embeddings. Existing backend embedding and batch operations remain compatible, and ordinary batch jobs remain visible. Saved conversations and operation identities are not deleted or rewritten by this catalogue change.

OpenAI provides chat, image and realtime selections as well as the existing transcription and batch functions. xAI text-to-speech uses its official Voice API service and key, not a fabricated model ID. Capability and price depend on the user's official account, region and provider terms.

`research/official-models.json` is the editable source and `scripts/build-catalog.py` reproduces the runtime catalogue. Provider links accompany entries. Offline protocol tests and simulated browser checks do not establish live account entitlements or paid provider availability. No API credential or user data belongs in catalogue files.

Kimi K3 and K3.1 Pro use an opaque encrypted continuation bound to the account generation or guest tab, the model and the exact assistant answer. Raw provider reasoning is not displayed or stored in chat JSON. A prior conversation without a valid matching continuation, including one imported from a different owner, must start a new conversation; old text remains available. If the provider returns a valid answer without usable continuation, the answer is retained with an explicit warning and no automatic replay. An existing conversation size limit still applies, and encryption-key rotation may make earlier continuations unavailable. Local tests do not prove live provider behavior.
