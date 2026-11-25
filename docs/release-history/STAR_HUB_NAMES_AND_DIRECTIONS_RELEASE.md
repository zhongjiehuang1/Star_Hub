> Historical release record. Paths beginning `OneDrive root/` are relative to the `Zhongjie_Huang_s_Database` folder in OneDrive. Verify current settings and recovery material before acting on historical details.

# Star Hub account names and model categories release

- Release run: f70dcdd8-642f-40d8-87db-47f6e0440a21
- WAR SHA-256: 6ecb56300a061f59ce2b0af4e45c45add4cc3a19ec7680703604c5e3529cfb31
- Database migration: 015_account_display_name.sql; SHA-256 26a2a0f5b26ad42c9682aef6cd22ee59bc5c8be8ad42b6a93e43826468acd9d8
- Updated display names on two existing accounts; checked existing account fields remained unchanged.
- Pre-change encrypted backup: OneDrive root/Historical_Archives/Database_Backups/backup-f70dcdd8-642f-40d8-87db-47f6e0440a21. Isolated restore verification de0bc4d1-49bd-4f7e-bdf2-220a5669e835 passed. This check does not establish a full blank-host rebuild or restoration of database grants and routines.
- Prior application archives and migration receipt: /var/lib/codex-validation/starhub-ui-preimages/f70dcdd8-642f-40d8-87db-47f6e0440a21 on the respective hosts.
- Both hosts and all four application instances passed admission checks. Production account rows and public assets were verified.
- Automated verification: 175 JavaScript tests passed; 313 Java tests run, one skipped. A guest browser check confirmed Claude shows only its available category while OpenAI shows voice among its categories. Authenticated display of the names was verified in automated tests, not in a logged-in production browser session.
