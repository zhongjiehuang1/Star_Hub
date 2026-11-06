#!/usr/bin/env bash
# Deploy only Star Hub in an existing shared Tomcat 9. Run as root on the server.
set -Eeuo pipefail
stage=${1:?Usage: deploy-server.sh STAGING_DIRECTORY}
[[ $EUID -eq 0 ]] || { echo 'Run as root.' >&2; exit 1; }
stage=$(realpath "$stage")
for f in starhub.war nginx.conf starhub-proxy.conf update-geoip.sh; do
  [[ -s "$stage/$f" ]] || { echo "Missing staged file: $f" >&2; exit 1; }
done
for cmd in curl gzip python3 unzip systemctl nginx ss flock stty; do
  command -v "$cmd" >/dev/null || { echo "Missing required command: $cmd" >&2; exit 1; }
done
exec 9>/run/starhub-deploy.lock
flock -n 9 || { echo 'Another Star Hub deployment is already running.' >&2; exit 1; }
[[ -s /etc/letsencrypt/live/starhub.zhongjiehuang.com/fullchain.pem && -d /var/lib/tomcat9/webapps ]] || { echo 'Tomcat or TLS configuration is missing.' >&2; exit 1; }
systemctl is-active --quiet tomcat9 && systemctl is-active --quiet nginx && systemctl is-active --quiet cron || { echo 'Tomcat, Nginx and cron must be running before deployment.' >&2; exit 1; }
unzip -t "$stage/starhub.war" >/dev/null

# The WebSocket handshake and HTTP filter depend on a trusted local proxy.
# Do not reconfigure the connector of the shared Tomcat service automatically.
python3 - <<'PY'
import ipaddress, subprocess, sys, xml.etree.ElementTree as ET
root = ET.parse('/etc/tomcat9/server.xml').getroot()
connectors = [c for c in root.iter('Connector') if c.get('port') == '8080']
if len(connectors) != 1 or connectors[0].get('address') != '127.0.0.1':
    sys.exit('Tomcat 8080 must be configured to listen on 127.0.0.1; configure and restart it before deploying.')
lines = subprocess.check_output(['ss', '-H', '-ltn', '( sport = :8080 )'], text=True).splitlines()
if not lines:
    sys.exit('Tomcat 8080 is not listening.')
for line in lines:
    local = line.split()[3].rsplit(':', 1)[0].strip('[]')
    try:
        ip = ipaddress.ip_address(local)
        if getattr(ip, 'ipv4_mapped', None): ip = ip.ipv4_mapped
    except ValueError:
        sys.exit('Cannot verify the active Tomcat listener: ' + local)
    if str(ip) != '127.0.0.1':
        sys.exit('Tomcat 8080 is not bound exclusively to 127.0.0.1; restart after securing its connector.')
PY
site_link=/etc/nginx/sites-enabled/starhub
if [[ -e "$site_link" || -L "$site_link" ]]; then
  [[ -L "$site_link" && $(readlink -f "$site_link") == /etc/nginx/sites-available/starhub ]] || { echo 'Existing Star Hub Nginx site link is unexpected; resolve it before deploying.' >&2; exit 1; }
fi
for target in /var/lib/tomcat9/webapps/starhub.war /etc/nginx/sites-available/starhub /etc/nginx/snippets/starhub-proxy.conf /etc/starhub/access.env /etc/systemd/system/tomcat9.service.d/starhub-region.conf /var/lib/starhub/dbip-country-lite.mmdb /usr/local/sbin/starhub-update-geoip /etc/cron.d/starhub-geoip; do
  if [[ -e "$target" || -L "$target" ]]; then
    [[ ! -L "$target" && -f "$target" ]] || { echo "Expected a regular file, not a symlink or directory: $target" >&2; exit 1; }
  fi
done
for dir in /etc/starhub /var/lib/starhub /etc/systemd/system/tomcat9.service.d /var/lib/tomcat9/webapps/starhub; do
  if [[ -e "$dir" || -L "$dir" ]]; then
    [[ ! -L "$dir" && -d "$dir" ]] || { echo "Expected a directory, not a symlink or file: $dir" >&2; exit 1; }
  fi
done

work=$(mktemp -d /tmp/starhub-deploy.XXXXXXXX)
chmod 700 "$work"
geo_tmp=
terminal_state=
restore_terminal() {
  if [[ -n "$terminal_state" ]]; then
    stty "$terminal_state" </dev/tty || echo 'Warning: could not restore terminal settings.' >&2
    terminal_state=
  fi
}
cleanup() { restore_terminal; if [[ -n "$geo_tmp" ]]; then rm -f -- "$geo_tmp"; fi; rm -rf -- "$work"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
umask 077
if [[ -e /etc/starhub/access.env ]]; then
  [[ -f /etc/starhub/access.env ]] || { echo 'Star Hub environment file is not a regular file.' >&2; exit 1; }
  cp /etc/starhub/access.env "$work/access.env"
else
  [[ -t 0 ]] || { echo 'No tester password configured; run interactively to set one.' >&2; exit 1; }
  terminal_state=$(stty -g </dev/tty)
  stty -echo </dev/tty
  printf 'Tester password (8–128 letters, digits, dot, underscore or hyphen): ' >&2
  IFS= read -r pass1 </dev/tty
  printf '\nConfirm tester password: ' >&2
  IFS= read -r pass2 </dev/tty
  printf '\n' >&2
  restore_terminal
  [[ "$pass1" == "$pass2" && "$pass1" =~ ^[A-Za-z0-9._-]{8,128}$ ]] || { unset pass1 pass2; echo 'Passwords do not match or use unsupported characters.' >&2; exit 1; }
  printf 'STARHUB_GEOIP_DB=/var/lib/starhub/dbip-country-lite.mmdb\nSTARHUB_TESTER_PASSWORD=%s\n' "$pass1" > "$work/access.env"
  unset pass1 pass2
fi
# Validate without sourcing or printing secrets. Preserve any unrelated existing entries.
python3 - "$work/access.env" <<'PY'
import re, sys
lines = open(sys.argv[1], encoding='utf-8').read().splitlines()
def one(key):
    values = [x[len(key)+1:] for x in lines if x.startswith(key+'=')]
    if len(values) != 1: sys.exit('Expected exactly one ' + key + ' entry in the environment file.')
    return values[0]
if one('STARHUB_GEOIP_DB') != '/var/lib/starhub/dbip-country-lite.mmdb':
    sys.exit('STARHUB_GEOIP_DB must be /var/lib/starhub/dbip-country-lite.mmdb.')
if not re.fullmatch(r'[A-Za-z0-9._-]{8,128}', one('STARHUB_TESTER_PASSWORD')):
    sys.exit('The tester password must use 8–128 letters, digits, dot, underscore or hyphen.')
PY
# Download and validate before touching live services; the updater preserves the old DB on failure.
STARHUB_GEOIP_DIR="$work/geoip" bash "$stage/update-geoip.sh"

install -d -m 700 /var/backups/starhub
backup=$(mktemp -d /var/backups/starhub/region-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXXXX)
snapshot() {
  local name=$1 target=$2
  if [[ -e "$target" || -L "$target" ]]; then
    cp -a -- "$target" "$backup/$name"
    : > "$backup/$name.existed"
  fi
}
snapshot war /var/lib/tomcat9/webapps/starhub.war
snapshot nginx /etc/nginx/sites-available/starhub
snapshot proxy /etc/nginx/snippets/starhub-proxy.conf
snapshot link "$site_link"
snapshot environment /etc/starhub/access.env
snapshot dropin /etc/systemd/system/tomcat9.service.d/starhub-region.conf
snapshot database /var/lib/starhub/dbip-country-lite.mmdb
snapshot updater /usr/local/sbin/starhub-update-geoip
snapshot cron /etc/cron.d/starhub-geoip
restore() {
  local name=$1 target=$2
  rm -f -- "$target" || return 1
  if [[ -f "$backup/$name.existed" ]]; then cp -a -- "$backup/$name" "$target" || return 1; fi
}
service_touched=0
application_touched=0
old_expanded=0
war_attempted=0
rollback() {
  local status=${1:-$?} failed=0 stopped=1
  trap - ERR INT TERM
  set +e
  echo "Deployment failed; restoring previous state. Backup: $backup" >&2
  if (( service_touched )); then systemctl stop tomcat9 || { stopped=0; failed=1; echo 'Cannot stop Tomcat during rollback; application restoration requires manual intervention.' >&2; }; fi
  if (( application_touched && stopped )); then
    local can_restore_expanded=1
    if [[ -e "$backup/old-expanded" ]] || (( ! old_expanded && war_attempted )); then
      if [[ -e /var/lib/tomcat9/webapps/starhub ]]; then mv /var/lib/tomcat9/webapps/starhub "$backup/failed-expanded" || { failed=1; can_restore_expanded=0; }; fi
      if [[ -e "$backup/old-expanded" ]] && (( can_restore_expanded )); then mv "$backup/old-expanded" /var/lib/tomcat9/webapps/starhub || failed=1; fi
    fi
    if (( war_attempted )); then restore war /var/lib/tomcat9/webapps/starhub.war || failed=1; fi
  fi
  restore nginx /etc/nginx/sites-available/starhub || failed=1
  restore proxy /etc/nginx/snippets/starhub-proxy.conf || failed=1
  restore link "$site_link" || failed=1
  restore environment /etc/starhub/access.env || failed=1
  restore dropin /etc/systemd/system/tomcat9.service.d/starhub-region.conf || failed=1
  if [[ -f "$backup/database.existed" ]]; then
    geo_tmp=$(mktemp /var/lib/starhub/.dbip-rollback.XXXXXXXX) && cp -a -- "$backup/database" "$geo_tmp" && mv -f -- "$geo_tmp" /var/lib/starhub/dbip-country-lite.mmdb && geo_tmp= || failed=1
  else
    rm -f -- /var/lib/starhub/dbip-country-lite.mmdb || failed=1
  fi
  restore updater /usr/local/sbin/starhub-update-geoip || failed=1
  restore cron /etc/cron.d/starhub-geoip || failed=1
  systemctl daemon-reload || failed=1
  if (( service_touched && stopped )); then systemctl start tomcat9 || { echo 'Tomcat restart failed during rollback.' >&2; failed=1; }; fi
  if nginx -t; then systemctl reload nginx || failed=1; else echo 'Restored Nginx configuration did not validate.' >&2; failed=1; fi
  if (( failed )); then echo "Rollback was incomplete; inspect services and backup $backup immediately." >&2; fi
  exit "$status"
}
trap rollback ERR
trap 'rollback 130' INT
trap 'rollback 143' TERM
[[ -d /etc/starhub ]] || install -d -m 700 /etc/starhub
[[ -d /var/lib/starhub ]] || install -d -m 755 /var/lib/starhub
[[ -d /etc/systemd/system/tomcat9.service.d ]] || install -d -m 755 /etc/systemd/system/tomcat9.service.d
install -m 600 -o root -g root "$work/access.env" /etc/starhub/access.env
geo_tmp=$(mktemp /var/lib/starhub/.dbip-deploy.XXXXXXXX)
install -m 644 "$work/geoip/dbip-country-lite.mmdb" "$geo_tmp"
mv -f -- "$geo_tmp" /var/lib/starhub/dbip-country-lite.mmdb
geo_tmp=
install -m 755 "$stage/update-geoip.sh" /usr/local/sbin/starhub-update-geoip
printf '23 3 * * * root /usr/local/sbin/starhub-update-geoip >> /var/log/starhub-geoip-update.log 2>&1\n' > /etc/cron.d/starhub-geoip
chmod 644 /etc/cron.d/starhub-geoip
printf '[Service]\nEnvironmentFile=/etc/starhub/access.env\n' > /etc/systemd/system/tomcat9.service.d/starhub-region.conf
chmod 644 /etc/systemd/system/tomcat9.service.d/starhub-region.conf
install -m 644 "$stage/nginx.conf" /etc/nginx/sites-available/starhub
install -m 644 "$stage/starhub-proxy.conf" /etc/nginx/snippets/starhub-proxy.conf
if [[ ! -L "$site_link" ]]; then ln -s /etc/nginx/sites-available/starhub "$site_link"; fi
nginx -t
systemctl daemon-reload
service_touched=1
systemctl stop tomcat9
application_touched=1
if [[ -e /var/lib/tomcat9/webapps/starhub ]]; then old_expanded=1; mv /var/lib/tomcat9/webapps/starhub "$backup/old-expanded"; fi
war_attempted=1
install -o tomcat -g tomcat -m 640 "$stage/starhub.war" /var/lib/tomcat9/webapps/starhub.war
systemctl start tomcat9
# An unlocated loopback client is intentionally challenged, so health requires a known GB test IP.
ready=0
for attempt in {1..60}; do
  if curl -fsS --noproxy '*' --max-time 3 -H 'X-Real-IP: 81.2.69.142' http://127.0.0.1:8080/starhub/api/health > "$work/health" 2>/dev/null && python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d.get("status")=="ok" and d.get("app")=="Star Hub"' "$work/health"; then ready=1; break; fi
  sleep 2
done
[[ $ready == 1 ]] || { echo 'Star Hub health check failed.' >&2; false; }
for ip in 223.5.5.5 202.181.243.1 202.175.3.8 127.0.0.1; do
  code=$(curl -sS --noproxy '*' --max-time 5 -o "$work/gate" -w '%{http_code}' -H "X-Real-IP: $ip" http://127.0.0.1:8080/starhub/)
  [[ "$code" == 403 ]] && grep -q 'starhub_access_password' "$work/gate" || { echo 'Regional verification check failed.' >&2; false; }
  if [[ "$ip" == 127.0.0.1 ]]; then phrase='暂时无法确认'; else phrase='本系统不向这些地区的公众开放'; fi
  grep -q "$phrase" "$work/gate" || { echo 'Unexpected location lookup result.' >&2; false; }
done
systemctl reload nginx
https_ready=0
for attempt in {1..20}; do
  code=$(curl -sS --noproxy '*' --max-time 5 --resolve starhub.zhongjiehuang.com:443:127.0.0.1 -o "$work/https" -w '%{http_code}' https://starhub.zhongjiehuang.com/ 2>/dev/null || true)
  if [[ "$code" == 403 ]] && grep -q 'starhub_access_password' "$work/https"; then https_ready=1; break; fi
  sleep 1
done
[[ $https_ready == 1 ]] || { echo 'HTTPS proxy verification check failed.' >&2; false; }
python3 - <<'PY'
import base64, http.client, os, re, socket, ssl, sys, urllib.parse
host = 'starhub.zhongjiehuang.com'
def request(path, method='GET', body=None, cookie=None):
    conn = http.client.HTTPSConnection(host, 443, timeout=10)
    conn.sock = ssl.create_default_context().wrap_socket(socket.create_connection(('127.0.0.1', 443), timeout=10), server_hostname=host)
    headers = {'Host': host, 'X-Real-IP': '81.2.69.142'}
    if cookie: headers['Cookie'] = cookie
    if body is not None: headers['Content-Type'] = 'application/x-www-form-urlencoded'
    try:
        conn.request(method, path, body, headers)
        response = conn.getresponse()
        return response.status, dict(response.getheaders()), response.read().decode('utf-8')
    finally:
        conn.close()
try:
    status, headers, body = request('/api/health?deployment_check=1')
    assert status == 403 and '暂时无法确认' in body
    cookie_header = headers['Set-Cookie']
    assert all(x in cookie_header for x in ('Secure', 'HttpOnly', 'Path=/'))
    cookie = cookie_header.split(';', 1)[0]
    token = re.search(r'name="starhub_access_token" value="([^"]+)"', body).group(1)
    with open('/etc/starhub/access.env', encoding='utf-8') as f:
        password = next(line.rstrip('\n').split('=', 1)[1] for line in f if line.startswith('STARHUB_TESTER_PASSWORD='))
    form = urllib.parse.urlencode({'starhub_access_token': token, 'starhub_access_password': password})
    status, headers, _ = request('/_access', 'POST', form, cookie)
    assert status == 303 and headers['Location'] == 'https://' + host + '/api/health?deployment_check=1'
    verified_cookie = headers['Set-Cookie'].split(';', 1)[0]
    assert verified_cookie != cookie
    assert request('/api/health', cookie=verified_cookie)[0] == 200
    def websocket(cookie=None):
        sock = ssl.create_default_context().wrap_socket(socket.create_connection(('127.0.0.1', 443), timeout=10), server_hostname=host)
        try:
            key = base64.b64encode(os.urandom(16)).decode('ascii')
            lines = ['GET /realtime HTTP/1.1', 'Host: ' + host, 'Connection: Upgrade', 'Upgrade: websocket', 'Sec-WebSocket-Version: 13', 'Sec-WebSocket-Key: ' + key, 'Origin: https://' + host, 'X-Real-IP: 81.2.69.142']
            if cookie: lines.append('Cookie: ' + cookie)
            sock.sendall(('\r\n'.join(lines) + '\r\n\r\n').encode('ascii'))
            return int(sock.recv(4096).split(b'\r\n', 1)[0].split()[1])
        finally:
            sock.close()
    assert websocket() == 403 and websocket(verified_cookie) == 101
except Exception:
    sys.exit('HTTPS tester login verification failed; deployment will be rolled back.')
PY
trap - ERR INT TERM
printf 'Deployment complete. Shared Tomcat logs were preserved. Backup: %s\n' "$backup"
