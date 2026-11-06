#!/usr/bin/env python3
"""Exercise deployment and rollback against an isolated fake server, never real services."""
import os
import fcntl
from pathlib import Path
import pty
import re
import select
import signal
import subprocess
import sys
import tempfile
import termios
import time
import unittest

PROJECT = Path(__file__).resolve().parents[2]


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='starhub-deploy-test-', dir='/tmp')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.bin = self.root / 'bin'
        self.stage = self.root / 'stage'
        self.bin.mkdir(); self.stage.mkdir()
        for folder in ('etc/letsencrypt/live/starhub.zhongjiehuang.com', 'etc/tomcat9',
                       'etc/nginx/sites-available', 'etc/nginx/sites-enabled', 'etc/nginx/snippets',
                       'etc/systemd/system/tomcat9.service.d', 'etc/cron.d', 'etc/starhub',
                       'var/lib/tomcat9/webapps/starhub', 'var/lib/starhub', 'var/log/tomcat9',
                       'usr/local/sbin', 'tmp', 'run'):
            (self.root / folder).mkdir(parents=True, exist_ok=True)
        self.put('etc/letsencrypt/live/starhub.zhongjiehuang.com/fullchain.pem', 'certificate')
        self.put('etc/tomcat9/server.xml', '<Server><Connector port="8080" address="127.0.0.1"/></Server>')
        self.put('etc/nginx/sites-available/starhub', 'old nginx')
        self.put('etc/nginx/snippets/starhub-proxy.conf', 'old proxy')
        (self.root / 'etc/nginx/sites-enabled/starhub').symlink_to(self.root / 'etc/nginx/sites-available/starhub')
        self.put('var/lib/tomcat9/webapps/starhub.war', 'old war')
        self.put('var/lib/tomcat9/webapps/starhub/marker', 'old expanded')
        self.put('var/lib/starhub/dbip-country-lite.mmdb', 'old db')
        self.put('var/log/tomcat9/catalina.out', 'keep active log')
        self.put('var/log/tomcat9/old.gz', 'keep rotated log')
        self.put('etc/starhub/access.env', f'STARHUB_GEOIP_DB={self.root}/var/lib/starhub/dbip-country-lite.mmdb\nSTARHUB_TESTER_PASSWORD=Existing123\n')
        self.put('etc/systemd/system/tomcat9.service.d/starhub-region.conf', 'old dropin')
        self.put('etc/cron.d/starhub-geoip', 'old cron')
        self.put('usr/local/sbin/starhub-update-geoip', 'old updater')
        for name, content in [('starhub.war', 'new war'), ('nginx.conf', 'new nginx'), ('starhub-proxy.conf', 'new proxy')]:
            (self.stage / name).write_text(content)
        (self.stage / 'update-geoip.sh').write_text('#!/bin/bash\nmkdir -p "$STARHUB_GEOIP_DIR"\nprintf "new db" > "$STARHUB_GEOIP_DIR/dbip-country-lite.mmdb"\n')
        self.stub('systemctl', '''#!/bin/bash
echo "systemctl $*" >> "$MOCK_ROOT/calls"
case "$1" in
 is-active) if [[ "${*: -1}" == tomcat9 && -f "$MOCK_ROOT/stopped" ]]; then exit 3; fi ;;
 stop) touch "$MOCK_ROOT/stopped" ;;
 start) if [[ "${FAIL_START_ONCE:-}" == 1 && ! -f "$MOCK_ROOT/start-failed" ]]; then touch "$MOCK_ROOT/start-failed"; exit 1; fi; if [[ "${INTERRUPT_START:-}" == 1 && ! -f "$MOCK_ROOT/interrupted" ]]; then touch "$MOCK_ROOT/interrupted"; kill -TERM "$PPID"; fi; rm -f "$MOCK_ROOT/stopped" ;;
esac
''')
        self.stub('ss', '#!/bin/bash\nif [[ "${PUBLIC_LISTENER:-}" == 1 ]]; then echo "LISTEN 0 64 0.0.0.0:8080 0.0.0.0:*"; else echo "LISTEN 0 64 127.0.0.1:8080 0.0.0.0:*"; fi\n')
        self.stub('nginx', '''#!/bin/bash
echo "nginx $*" >> "$MOCK_ROOT/calls"
if [[ "${FAIL_NGINX_ONCE:-}" == 1 && ! -f "$MOCK_ROOT/nginx-failed" ]]; then touch "$MOCK_ROOT/nginx-failed"; exit 1; fi
''')
        self.stub('unzip', '#!/bin/bash\nexit 0\n')
        self.stub('flock', '#!/bin/bash\n[[ "${LOCK_BUSY:-}" != 1 ]]\n')
        self.stub('sleep', '#!/bin/bash\nexit 0\n')
        self.stub('curl', '''#!/bin/bash
if [[ "${FAIL_HEALTH:-}" == 1 ]]; then exit 1; fi
output=''; code=0
while (($#)); do
  case "$1" in -o) output=$2; shift 2;; -w) code=1; shift 2;; *) shift;; esac
done
if ((code)); then printf 'starhub_access_password 暂时无法确认 本系统不向这些地区的公众开放' > "$output"; if [[ "${FAIL_REGION:-}" == 1 ]]; then printf '200'; else printf '403'; fi; else printf '{"status":"ok","app":"Star Hub"}'; fi
''')
        # Delegate local validation to Python, but simulate the final HTTPS check.
        # No test may open a network connection on the actual machine.
        self.stub('python3', f'''#!{sys.executable}
import os, subprocess, sys
args=sys.argv[1:]
if args and args[0]=='-':
    data=sys.stdin.buffer.read()
    if b'http.client.HTTPSConnection' in data:
        sys.exit(1 if os.environ.get('FAIL_HTTPS')=='1' else 0)
    sys.exit(subprocess.run([{sys.executable!r}]+args, input=data).returncode)
os.execv({sys.executable!r}, [{sys.executable!r}]+args)
''')
        # Production uses root/tomcat ownership; files in this fixture belong to the test user.
        self.stub('install', '''#!/usr/bin/env python3
import os,sys
args=[]; i=1
while i < len(sys.argv):
    if sys.argv[i] in ('-o','-g'): i += 2
    else: args.append(sys.argv[i]); i += 1
os.execv('/usr/bin/install', ['install'] + args)
''')
        script = (PROJECT / 'scripts/deploy-server.sh').read_text()
        self.assertIn('[[ $EUID -eq 0 ]]', script)
        script = script.replace('[[ $EUID -eq 0 ]]', '[[ 0 -eq 0 ]]', 1)
        script = re.sub(r'/(?:etc|var|usr/local|run)(?=/)', lambda m: str(self.root) + m.group(), script)
        script = script.replace('/tmp/starhub-deploy.', str(self.root / 'tmp/starhub-deploy.'))
        self.script = self.root / 'deploy-server.sh'
        self.script.write_text(script)

    def put(self, path, value):
        (self.root / path).write_text(value)

    def stub(self, name, content):
        p = self.bin / name
        p.write_text(content); p.chmod(0o755)

    def run_deploy(self, **extra):
        env = os.environ.copy()
        env.update(PATH=str(self.bin) + os.pathsep + env['PATH'], MOCK_ROOT=str(self.root), **extra)
        return subprocess.run(['bash', str(self.script), str(self.stage)], env=env, text=True,
                              input='', stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)

    def run_interactive(self, first, confirm, interrupt=False):
        env = os.environ.copy()
        env.update(PATH=str(self.bin) + os.pathsep + env['PATH'], MOCK_ROOT=str(self.root))
        master, slave = pty.openpty()
        proc = None
        try:
            initial = termios.tcgetattr(master)
            def controlling_tty():
                os.setsid()
                fcntl.ioctl(0, termios.TIOCSCTTY, 0)
            proc = subprocess.Popen(['bash', str(self.script), str(self.stage)], env=env,
                                    stdin=slave, stdout=slave, stderr=slave, preexec_fn=controlling_tty)
            data = b''
            sent_first = sent_confirm = False
            deadline = time.monotonic() + 30
            while True:
                if time.monotonic() > deadline:
                    raise TimeoutError('Interactive deployment did not finish')
                readable, _, _ = select.select([master], [], [], 0.2)
                if readable:
                    try: chunk = os.read(master, 65536)
                    except OSError: break
                    if not chunk: break
                    data += chunk
                    if not sent_first and b'Tester password (' in data:
                        if interrupt: os.kill(proc.pid, signal.SIGTERM)
                        else: os.write(master, (first + '\n').encode())
                        sent_first = True
                    if sent_first and not sent_confirm and b'Confirm tester password:' in data:
                        os.write(master, (confirm + '\n').encode()); sent_confirm = True
                elif proc.poll() is not None:
                    break
            result = proc.wait(timeout=5)
            self.assertEqual(termios.tcgetattr(master), initial, 'Terminal settings were not restored')
            return result, data.decode('utf-8', errors='replace')
        finally:
            if proc is not None and proc.poll() is None:
                proc.kill(); proc.wait()
            if slave is not None: os.close(slave)
            os.close(master)

    def assert_logs_preserved(self):
        self.assertEqual((self.root / 'var/log/tomcat9/catalina.out').read_text(), 'keep active log')
        self.assertEqual((self.root / 'var/log/tomcat9/old.gz').read_text(), 'keep rotated log')

    def assert_rolled_back(self):
        for path, content in [('var/lib/tomcat9/webapps/starhub.war', 'old war'),
                              ('var/lib/tomcat9/webapps/starhub/marker', 'old expanded'),
                              ('etc/nginx/sites-available/starhub', 'old nginx'),
                              ('etc/nginx/snippets/starhub-proxy.conf', 'old proxy'),
                              ('etc/systemd/system/tomcat9.service.d/starhub-region.conf', 'old dropin'),
                              ('etc/cron.d/starhub-geoip', 'old cron'),
                              ('var/lib/starhub/dbip-country-lite.mmdb', 'old db'),
                              ('usr/local/sbin/starhub-update-geoip', 'old updater')]:
            self.assertEqual((self.root / path).read_text(), content, path)
        self.assertIn('STARHUB_TESTER_PASSWORD=Existing123', (self.root / 'etc/starhub/access.env').read_text())
        self.assert_logs_preserved()

    def test_success_preserves_logs_and_existing_password(self):
        result = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual((self.root / 'var/lib/tomcat9/webapps/starhub.war').read_text(), 'new war')
        self.assertEqual((self.root / 'var/lib/starhub/dbip-country-lite.mmdb').read_text(), 'new db')
        self.assertIn('STARHUB_TESTER_PASSWORD=Existing123', (self.root / 'etc/starhub/access.env').read_text())
        self.assertNotIn('Existing123', result.stdout)
        self.assert_logs_preserved()

    def test_missing_password_stops_before_changing_anything(self):
        (self.root / 'etc/starhub/access.env').unlink()
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('run interactively', result.stdout)
        self.assertEqual((self.root / 'var/lib/tomcat9/webapps/starhub.war').read_text(), 'old war')
        self.assert_logs_preserved()

    def test_first_deployment_creates_private_password_configuration(self):
        config = self.root / 'etc/starhub/access.env'
        config.unlink()
        status, output = self.run_interactive('NewTester123', 'NewTester123')
        self.assertEqual(status, 0, output)
        self.assertEqual(config.stat().st_mode & 0o777, 0o600)
        self.assertEqual(config.read_text(), f'STARHUB_GEOIP_DB={self.root}/var/lib/starhub/dbip-country-lite.mmdb\nSTARHUB_TESTER_PASSWORD=NewTester123\n')
        self.assertNotIn('NewTester123', output)
        self.assert_logs_preserved()

    def test_mismatched_first_password_does_not_deploy(self):
        config = self.root / 'etc/starhub/access.env'
        config.unlink()
        status, output = self.run_interactive('NewTester123', 'Different123')
        self.assertNotEqual(status, 0, output)
        self.assertIn('Passwords do not match', output)
        self.assertNotIn('NewTester123', output)
        self.assertNotIn('Different123', output)
        self.assertFalse(config.exists())
        self.assertEqual((self.root / 'var/lib/tomcat9/webapps/starhub.war').read_text(), 'old war')
        self.assert_logs_preserved()

    def test_password_prompt_interruption_restores_terminal(self):
        config = self.root / 'etc/starhub/access.env'
        config.unlink()
        status, output = self.run_interactive('Unused123', 'Unused123', interrupt=True)
        self.assertEqual(status, 143, output)
        self.assertFalse(config.exists())
        self.assertEqual((self.root / 'var/lib/tomcat9/webapps/starhub.war').read_text(), 'old war')
        self.assert_logs_preserved()

    def test_untrusted_tomcat_connector_is_rejected(self):
        self.put('etc/tomcat9/server.xml', '<Server><Connector port="8080" address="0.0.0.0"/></Server>')
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('must be configured to listen on 127.0.0.1', result.stdout)
        self.assert_rolled_back()

    def test_public_active_listener_is_rejected(self):
        result = self.run_deploy(PUBLIC_LISTENER='1')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('not bound exclusively', result.stdout)
        self.assert_rolled_back()

    def test_busy_lock_stops_before_changes(self):
        result = self.run_deploy(LOCK_BUSY='1')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('already running', result.stdout)
        self.assert_rolled_back()

    def test_missing_tls_certificate_stops_before_changes(self):
        (self.root / 'etc/letsencrypt/live/starhub.zhongjiehuang.com/fullchain.pem').unlink()
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('TLS configuration is missing', result.stdout)
        self.assert_rolled_back()

    def test_symlinked_database_is_rejected(self):
        database = self.root / 'var/lib/starhub/dbip-country-lite.mmdb'
        database.unlink()
        target = self.root / 'outside-target'
        target.write_text('untouched')
        database.symlink_to(target)
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('not a symlink', result.stdout)
        self.assertTrue(database.is_symlink())
        self.assertEqual(target.read_text(), 'untouched')
        self.assertEqual((self.root / 'var/lib/tomcat9/webapps/starhub.war').read_text(), 'old war')
        self.assert_logs_preserved()

    def test_database_download_failure_does_not_touch_services(self):
        (self.stage / 'update-geoip.sh').write_text('#!/bin/bash\nexit 1\n')
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assert_rolled_back()
        calls = (self.root / 'calls').read_text()
        self.assertNotIn('systemctl stop', calls)

    def test_nginx_failure_rolls_back(self):
        result = self.run_deploy(FAIL_NGINX_ONCE='1')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assert_rolled_back()

    def test_tomcat_start_failure_rolls_back(self):
        result = self.run_deploy(FAIL_START_ONCE='1')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assert_rolled_back()

    def test_health_failure_rolls_back(self):
        result = self.run_deploy(FAIL_HEALTH='1')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assert_rolled_back()

    def test_https_login_failure_rolls_back(self):
        result = self.run_deploy(FAIL_HTTPS='1')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assert_rolled_back()

    def test_region_check_failure_rolls_back(self):
        result = self.run_deploy(FAIL_REGION='1')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assert_rolled_back()

    def test_termination_during_deployment_rolls_back(self):
        result = self.run_deploy(INTERRUPT_START='1')
        self.assertEqual(result.returncode, 143, result.stdout)
        self.assert_rolled_back()


if __name__ == '__main__':
    unittest.main()
