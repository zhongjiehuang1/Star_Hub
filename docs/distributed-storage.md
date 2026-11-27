# Star Hub storage and recovery

This guide describes the current single-database deployment. For host-specific paths, startup, backup and restore, use `Linux_Server_*/SERVER_CURRENT_STATE.md` and `Linux_Server_*/Recovery_Tools/README.md` in local OneDrive. Confirm the installed release and its configuration before maintenance.

## Architecture and availability

Both hosts run Star Hub through their own local application entry. Server 2 runs the only live production MySQL instance; Server 1 connects through a restricted SSH tunnel and verified JDBC TLS. There is no live second database, automatic database takeover or automatic request forwarding to the other server. When the shared database is unavailable, database-backed operations on both hosts must stop and report an unavailable state. Eligible static pages can remain available while their local application is running. Existing private Tomcat session replication supports application and guest-session behaviour; it does not guarantee uninterrupted sessions or provide database replication.

The account library stores file bytes, metadata, ownership, trash, quota reservations and operation results in the shared database. Conversations and provider-operation receipts also use database-backed storage. Treat the databases, protected configuration and encryption keys as a coordinated recovery set; a file listing alone is not a file backup.

## File operations and capacity

Each account has a library quota of **2,000,000,000 bytes**, including trash and durable pending reservations. A normal upload is limited to **4 MiB** per file. Generated images have a separate per-image limit of **12 MiB**. The workspace reports account-wide usage and quota, including trash and pending reservations; file and pending-operation lists are paginated.

An upload uses a stable operation UUID, checks capacity and reserves space before accepting content. File data and required metadata are committed through guarded database transactions. Conflicting or stale requests are rejected. If a response is uncertain, retain the original operation ID and query its result before deciding whether a retry is safe; never infer rollback solely from a timeout or issue a new ID to force an uncertain operation. Recycle-bin actions and permanent deletion must follow the same ownership, quota and transaction checks.

Both hosts target at least **4,000,000,000 bytes** of disk safety reserve, subject only to filesystem block rounding. Capacity admission can reject a file before its account quota is full. Privileged system processes and kernel activity can cross an ordinary filesystem reservation, so monitoring and actual physical free space remain necessary. A full concurrent two-user, near-2-GB physical fill has not been established by the bounded file tests.

## Guest data and provider operations

Guest conversations, attachments and provider keys are encrypted in the shared SQL guest store and associated with the current server session and browser tab. Guest access expires after ten minutes of inactivity; a still-valid session can renew its guest lease. Encrypted rows and operation receipts are retained until explicit maintenance, so closing a tab does not promise immediate physical erasure. Guest data remains separate from account data unless the user chooses to import it.

Provider requests use durable operation IDs and recorded dispatch/result states. A request with an uncertain dispatch must be checked using its original ID; automatic replay could duplicate a provider action or charge. Stored results and guest fields use protected encryption material. Live realtime audio is a stream and cannot be reconstructed frame by frame after interruption. A provider's billing behaviour is outside the application's exactly-once guarantees.

## Backup, recovery and maintenance

Keep the current complete, authenticated encrypted database backup and the protected per-host configuration, TLS and recovery keys. The backup must include file payload bytes, metadata, reservations, guest and provider receipts, account data and transaction state. Keep recovery keys outside the project and OneDrive backup. Validate each replacement backup with an actual restore to a separate isolated MySQL instance before discarding the previous recoverable set; never overwrite the live database as a test.

During an outage, protect the original database files and pending operations. Stop affected writes, identify whether a transaction or external provider request is uncertain, and use its original ID and documented recovery checks. Do not delete transaction, staging, receipt, quota or encryption state to make a service appear healthy. After restoring the shared database, verify database identity and durability settings, applicable pending operations, file contents and permissions before opening writes. Recovery and the ability to rebuild a blank host must be reported according to the checks actually completed.

For the model catalogue, account workflow and build instructions, see [the project README](../README.md). The English server guide describes current unit names, configuration and the ordered operational steps; the old coordinator and deployment examples are not instructions for this system.
