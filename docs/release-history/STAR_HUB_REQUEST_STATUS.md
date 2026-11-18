> Historical release record. Paths beginning `OneDrive root/` are relative to the `Zhongjie_Huang_s_Database` folder in OneDrive. Verify current settings and recovery material before acting on historical details.

> **Current recovery paths:** The original local `Server_Recovery_Work` has been retired to an encrypted OneDrive archive. Use the new OneDrive `Recovery_Tools` and local custody location documented in `SERVER_RECOVERY_ONEDRIVE_MIGRATION.md` (see `OneDrive root/Linux_Server_2026-11-14/SERVER_RECOVERY_ONEDRIVE_MIGRATION.md`); paths below describe the historical release.

> The WAR hash and database snapshot in this historical request-status note have been superseded. See `RICH_TEXT_AND_PROFILE.md` for the current artifacts and backup status.

# Star Hub request status: single database

A newly dispatched provider request is normal while it is in progress. Its ID remains in the browser session and its server-side operation record remains protected. If the request is interrupted or the result is unknown, use the original-result action before creating a separate request, because the provider may already have received and billed the original. An observed absent or claimed request may be retried only through the application’s existing original-identity checks. Do not delete unknown receipts to force a retry.

Server 2 is the sole production MySQL host. A single-copy provider result is expected in this topology and does not display the old dual-database degraded-mode message. Genuine storage errors, provider warnings and unconfirmed results remain visible.

On 14 November the reviewed update was deployed to both application servers. The deployed `starhub.war` SHA-256 is `b062d6dd7fe6ff9e166a0e53b2077390d1ee95f53de572f1fec7d379edabf060`. Only five browser-related WAR entries changed from the prior production artifact; server-side classes were preserved. The two existing Server 2 capacity-proof references were rebound to this WAR without changing the safety thresholds. Both nodes passed local application admission checks; a real Star Hub message returned successfully after deployment.

The comprehensive encrypted configuration archives in this folder predate this release and contain the earlier Server 2 monitor artifact hashes. The current database backup includes a newer limited-scope configuration capture from both hosts, including the current monitor binding. When rebuilding or rolling back, use a capacity proof that matches the actual deployed WAR and current safety settings; do not simply disable or bypass the monitor to make mismatched artifacts start. The archived configuration is a recovery reference, not proof of a clean-machine rebuild. No new full-machine restore was performed for this update.

The current production database snapshot was captured on 14 November at 09:14 UTC with applications stopped. Its contents were authenticated with both recovery keys and its source digest was stable across the capture. This new snapshot has not been independently restored; the previous independently restore-validated snapshot is retained outside OneDrive in the local `Documents/Server_Recovery_Work/Single_Database_Backup` area. Do not treat an earlier restore test as proof that the new snapshot has been restored.
