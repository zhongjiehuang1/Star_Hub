#!/usr/bin/env bash
# Install the current DB-IP Country Lite database without discarding the last good copy on failure.
set -euo pipefail
db_dir=${STARHUB_GEOIP_DIR:-/var/lib/starhub}
if [[ "$db_dir" == /var/lib/starhub ]]; then
  # Use the deployment lock so a manual or scheduled refresh cannot race a release.
  exec 8>/run/starhub-deploy.lock
  flock -n 8 || { echo 'Star Hub deployment or database update already running.' >&2; exit 1; }
fi
mkdir -p "$db_dir"
tmp=$(mktemp -d "$db_dir/.geoip.XXXXXXXX")
trap 'rm -rf "$tmp"' EXIT
month=$(date -u +%Y-%m)
url="https://download.db-ip.com/free/dbip-country-lite-${month}.mmdb.gz"
curl --fail --silent --show-error --location --connect-timeout 15 --max-time 180 "$url" -o "$tmp/database.gz"
gzip -t "$tmp/database.gz"
gzip -dc "$tmp/database.gz" > "$tmp/database.mmdb"
# MaxMind DB metadata follows this marker near the end of a valid database.
python3 - "$tmp/database.mmdb" <<'PY'
import os, sys
path = sys.argv[1]
if os.path.getsize(path) < 100000:
    raise SystemExit('GeoIP database is unexpectedly small')
with open(path, 'rb') as f:
    f.seek(-min(os.path.getsize(path), 131072), os.SEEK_END)
    if b'\xab\xcd\xefMaxMind.com' not in f.read():
        raise SystemExit('GeoIP database metadata marker is missing')
PY
chmod 644 "$tmp/database.mmdb"
mv -f "$tmp/database.mmdb" "$db_dir/dbip-country-lite.mmdb"
echo "Installed DB-IP Country Lite $month in $db_dir"
