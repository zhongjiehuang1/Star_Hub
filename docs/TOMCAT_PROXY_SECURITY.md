# Tomcat 11 proxy and tester credential configuration

Source checkpoint, 11 November 2026. The changes below still require the complete
real entry, session and failure acceptance before formal writes are opened.

The installed `RemoteIpValve` trusts only the loopback reverse proxy. Nginx must
overwrite `X-Forwarded-For` and `X-Real-IP` with its actual client address. The
valve changes the servlet's remote address; treating that rewritten address as
the original socket peer would incorrectly classify every public request as an
unknown country.

`GeoCountry` accepts a raw loopback proxy address, or Tomcat's Boolean
`org.apache.tomcat.request.forwarded` request attribute together with exactly
matching rewritten remote/client IP addresses. An HTTP header with that name
does not supply the container attribute. DNS names, address lists, scoped IPv6,
ports and inconsistent provenance fail closed. The existing country and tester
rules remain in `RegionAccess` and `SecurityFilter`.

Configure `STARHUB_GEOIP_DB` with the absolute readable path to the current
DB-IP Country Lite database. Preserve its attribution. The existing November
2026 database was copied through the private maintenance channel to both
isolated hosts, and known US/CN/HK/MO/GB, unknown-address and untrusted-peer
checks passed. This is separate from real HTTP acceptance through Nginx.

Prefer `STARHUB_TESTER_PASSWORD_FILE` to hold only the path to the credential.
The file must be an absolute regular file, not a symlink, owned by root or the
service user, with mode 0600 or 0640. Store the exact existing credential bytes
without adding a newline. No value or credential digest belongs in source,
documentation, a command argument or a log. A configured invalid/missing file
fails closed; it does not fall back to another credential. The historical
`STARHUB_TESTER_PASSWORD` environment input remains compatible when no file path
is configured.

The isolated host paths currently prepared are:

- `/var/lib/codex-validation/app-config/security/tester-password`
- `/var/lib/codex-validation/app-config/security/dbip-country-lite.mmdb`
- `/var/lib/codex-validation/app-config/starhub-security.json` (paths only)

The directory is root:tomcat 0750 and the two data files are root:tomcat 0640.
The existing tester credential was preserved. Host-user readability passed;
readability inside the final service namespace and real tester verification,
session rotation, peer session delivery and invalidation remain acceptance work.
The protected original is retained outside the servers. Do not print it to
check a deployment.

References: [Tomcat 11.0.27 runtime attributes](https://tomcat.apache.org/tomcat-11.0-doc/config/runtime-attributes.html),
[RemoteIpValve](https://tomcat.apache.org/tomcat-11.0-doc/api/org/apache/catalina/valves/RemoteIpValve.html),
and [the exact installed-version source](https://github.com/apache/tomcat/blob/11.0.27/java/org/apache/catalina/valves/RemoteIpValve.java).
