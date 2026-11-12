# Generated image receipts and binary reads (candidate gate 5)

This is the candidate protocol. Unit tests and synthetic provider streams do not establish real two-server acceptance. Formal writes remain closed until the matching migration, controller, capacity model and HTTP fault tests have passed.

A native image generation request makes one external HTTP request. Its `parameters.n` is an integer from 1 through 4. Each decoded image may contain at most 12 MiB (12,582,912 bytes). Four images may therefore contain 48 MiB of decoded image data. The existing file batch limit remains 64 MiB. The streaming JSON parser bounds encoded image data separately from a 64 KiB JSON envelope. That envelope is a response protocol bound; it is not additional disk safety space. No second provider request is made to recover a missing or uncertain result.

The image parser decodes one Base64 field at a time into a bounded binary value. It does not retain the complete Base64 response or a JSON tree containing those strings. Provider result receipts retain at most 32 MiB of JSON; images in these receipts are small references rather than Base64 strings.

## Account quota and publication

Before an account image operation can become `SENT`, its original operation ID reserves `n * 12 MiB` in the same account quota ledger used by ordinary uploads. The 2,000,000,000-byte account limit includes that reservation, existing files and trash. Retrying the same request does not reserve again. A known quota rejection occurs before external dispatch.

The same guarded transaction that creates the file library reservation transfers the provider's maximum logical reservation to the exact actual file sizes. Both the quota delta and fixed file IDs commit together. The physical future-allocation reservation is transferred in that transaction as well. The provider stores and confirms a small result recipe containing those fixed IDs before staging file content. It publishes the final receipt only after the original upload operation has committed the same file IDs for the same account. A crash after file commit can therefore be resolved by querying the original provider operation in another JVM. It does not dispatch the provider again.

An unknown file outcome is not treated as permission to create a fallback copy. Account images are not retained in an uncharged provider gallery. A known non-retained terminal failure uses `COMPLETED_NO_RESULT`. Unknown operations retain the relevant quota and physical reservations. The library response exposes `pendingImageGenerations` alongside ordinary pending uploads so these reservations are visible.

## Guest publication

Guest images use immutable encrypted `PROVIDER_IMAGE` payloads. Their fixed plans, source hashes, MIME types, parent dispatch and the small result plan are inserted in one immutable parent intent before any image chunk is staged. Each chunk is at most 2 MiB. The aggregate chunk mutation bound is 3 MiB per guarded transaction; this does not claim to bound every unrelated SQL row image.

All guest image payloads and the small result must be complete and hash-verified before one small transaction publishes the entire receipt. A partial image or result keeps the operation `SENT` or `UNKNOWN`, keeps its physical reservation, and is never permission to dispatch again. Status recovery can publish a fully staged original intent after acknowledgement loss. Age alone does not release or delete it.

## JSON contract

A completed result contains `media` entries with one of these forms:

```json
{"type":"image","mime":"image/png","fileId":"<library-file-uuid>"}
```

```json
{"type":"image","mime":"image/png","mediaRef":{"operationId":"<provider-operation-uuid>","mediaId":"<immutable-image-uuid>","mimeType":"image/png"}}
```

There is no image `url`, Base64 string or internal `imageBytes` field in the durable HTTP result. Existing provider result fields and the confirmed `operationId`, `operationState` and current `durability` remain present.

## Reading one image

Send `POST /api/provider-media` with the same trusted `Origin`, session cookie, `Content-Type: application/json` and `X-Starhub-Client: web` used for provider result queries. This endpoint uses the existing Origin, region and tester protections; it does not add a separate CSRF-token flow.

```json
{"operationId":"<provider-operation-uuid>","ownerTab":"<current-tab-id>","expectedEmail":"<current-account-email-or-empty>","mediaId":"<fileId-or-mediaRef.mediaId>"}
```

The request is limited to 16 KiB. A successful response is one image, at most 12 MiB, with these headers:

- `Content-Type: image/png`, `image/jpeg` or `image/webp`;
- `Content-Length` and `X-Content-SHA256` (lowercase hexadecimal SHA-256);
- `X-Starhub-Operation-ID` matching the original operation;
- `X-Starhub-Operation-State: COMPLETED`;
- `X-Four-Project-Durability: dual` or `single` from this read's actual confirmation;
- `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.

A guest must retain the original session guest identity and tab owner. A random image or operation UUID does not recreate that identity. An account may read its already saved library image from another tab or after logging in again: the current active account, actual library ownership and original completed receipt reference are checked; the original dispatch tab is not required for this read. This exception does not authorize redispatch or relax result-query identity.

A missing, trashed or deleted library image returns 404 and is not generated again. Unknown or unpublished operations return 409 without bytes. Identity changes reject the read. The client should read images sequentially, verify the operation headers, MIME and SHA, and create an in-memory Blob URL. It persists only the original small references, never Blob URLs or Base64 image data. A response error does not retire the original provider operation ID.

## Importing a guest image into an account

Login and logout change the session ID but retain its server-side guest identity string. For a direct read needed during an explicit import, the binary read request may set `guestImport: true`. The server additionally validates the current active account and reads only the previous guest identity already present in that same session. It requires the original tab and a completed source receipt, does not accept a client-supplied guest identity, and does not renew an expired guest. This exception is available only on the media endpoint; it never authorizes dispatch.

The ordinary upload endpoint remains limited to 4 MiB. A generated guest image as large as 12 MiB is imported inside the server using the existing generated-file transaction path:

```json
{"action":"import","guestImport":true,"operationId":"<source-provider-uuid>","mediaId":"<source-image-uuid>","importOperationId":"<new-stable-import-uuid>","ownerTab":"<original-tab>","expectedEmail":"<current-account>"}
```

Persist `importOperationId` before sending the first request. The original source operation ID stays unchanged. A successful JSON response contains `operationId`, `mediaId`, `importOperationId`, `state: "COMMITTED"`, `fileId`, `mime`, `size` and the current confirmed `durability`. It also returns `X-Starhub-Import-Operation-ID`, `X-Starhub-Import-State: COMMITTED` and `X-Four-Project-Durability`. A changed source under the same import ID returns 409.

Use the same request with `action: "import-status"` to query the original ID. Its state can be `ABSENT`, `RESERVED`, `CANCELLING`, `CANCELLED` or `COMMITTED`. An uncertain HTTP result retains the original import ID. A retry first checks the current account's durable file operation; an already committed result can replay even after the guest expires. An unfinished import still needs its authorized source. Expiry does not cancel or release an existing file reservation. Quota or physical-capacity rejection does not count as a completed import.

The file plan binds the account, immutable account generation, destination import operation, original source operation and media ID in a canonical server-generated filename with a private HMAC. A caller-selected ordinary filename or a copied filename from another destination operation cannot establish import provenance. Content, quota, fixed destination ID and the import receipt use the normal certified file reservation and chunk publication path. The source guest image is retained, and no provider request is made.

After confirmed import, persist a small account media reference containing `fileId` and `importOperationId`. To read it after a new login or in another tab, send a normal binary read request with `mediaId` equal to that new file ID, `importOperationId` equal to the import ID, and `operationId` equal to the original source provider operation. The server validates the current account, its committed import plan, exact source/file binding and actual library ownership. This read does not need the old guest session or tab. Successful binary responses additionally include `X-Starhub-Import-Operation-ID` and `X-Starhub-Import-State: COMMITTED`. A trashed or deleted imported file returns 404.

## Heap admission

All public HTTP provider dispatches (`run`, `chat`, `check`, `batch`), provider-operation queries, binary image reads and imports share one static heavy-task slot per webapp JVM. It is acquired before request-body parsing. A busy request receives 429 with no new claim, `SENT` transition or quota charge, and must keep its original client operation ID. The slot is released in `finally`, including timeouts and disconnected SSE responses. Internal recovery checks do not use this slot. This is an explicit throughput limit for the current 512 MiB Tomcat heap; it does not shrink image, result, file or guest payload bounds. Ordinary guest and file work remain separate, so the full combined workload still requires actual server memory tests.
