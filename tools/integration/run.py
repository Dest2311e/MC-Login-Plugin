#!/usr/bin/env python3
"""Start an isolated loopback dev server, run protocol checks, and stop it.
Requires npm install in tools/integration (or NODE_PATH pointing to its modules).
Never use with a production world. Refuses to replace unmarked server settings.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('version')
p.add_argument('loader', choices=['Fabric', 'Forge', 'Paper'])
p.add_argument('--gradle', type=Path)
p.add_argument('--scenario', choices=['authentication', 'conflict', 'minimal'], default='authentication')
p.add_argument('--port', type=int, default=25611)
p.add_argument('--jdk25', type=Path)
p.add_argument('--production-dir', type=Path, help='Isolated server directory with built jar installed')
p.add_argument('--launch', nargs=argparse.REMAINDER, help='Production server command; must be last option')
p.add_argument('--jdk8', type=Path, help='ForgeGradle 7 development launcher bootstrap toolchain')
a = p.parse_args()
project = ROOT / f'{a.version}+{a.loader}'
run = a.production_dir.resolve() if a.production_dir else project / 'run'
run.mkdir(parents=True, exist_ok=True)
if bool(a.production_dir) != bool(a.launch):
    raise SystemExit('--production-dir and --launch must be used together')
marker = run / '.mclogin-test-server'
props = run / 'server.properties'
if props.exists() and not marker.exists() and 'MCLogin local integration test' not in props.read_text():
    raise SystemExit('Refusing to replace existing server settings: ' + str(props))
marker.touch()
password = secrets.token_hex(20)
props.write_text(f'''server-ip=127.0.0.1
server-port={a.port}
online-mode=false
enforce-secure-profile=false
view-distance=2
simulation-distance=2
max-players=5
difficulty=peaceful
spawn-monsters=false
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
motd=MCLogin local integration test
enable-rcon=true
rcon.port={a.port + 1}
rcon.password={password}
broadcast-rcon-to-ops=false
''')
props.write_text(props.read_text().replace('level-type=minecraft:flat\n', 'level-type=minecraft:flat\ngenerator-settings=' + json.dumps({'layers': [{'block':'minecraft:bedrock','height':1},{'block':'minecraft:dirt','height':2},{'block':'minecraft:grass_block','height':1}], 'biome':'minecraft:plains'}) + '\n'))
(run / 'eula.txt').write_text('eula=true\n')
evidence = ROOT / '.codex-work' / 'runtime'
evidence.mkdir(parents=True, exist_ok=True)
name = f'{a.version}-{a.loader.lower()}' + ('-production' if a.production_dir else '')
if a.scenario != 'authentication':
    name += '-' + a.scenario
server_log = evidence / f'{name}-server.log'
probe_log = evidence / f'{name}-probe.log'
cmd = [str(a.gradle.resolve() if a.gradle else project / 'gradlew'), 'runServer', '--console=plain', '--no-daemon']
if a.loader == 'Forge' and a.version not in ['1.21.8', '1.21.11']:
    cmd += ['-x', 'downloadAssets']  # Dedicated server does not need client sounds/textures.
toolchains = [str(jdk.resolve()) for jdk in [a.jdk8, a.jdk25] if jdk]
if toolchains:
    cmd.append('-Porg.gradle.java.installations.paths=' + ','.join(toolchains))
if a.production_dir:
    cmd = a.launch
result = {'version': a.version, 'loader': a.loader, 'mode': 'production-jar' if a.production_dir else 'development-server', 'passed': False}
if a.production_dir:
    artifact = run / 'plugins/mclogin-paper-spigot-1.1.0.jar' if a.loader == 'Paper' else run / 'mods' / f'mclogin-{a.version}-{a.loader.lower()}-1.1.0.jar'
    result['artifact_sha256'] = hashlib.sha256(artifact.read_bytes()).hexdigest()
started = time.time()
with server_log.open('w') as log:
    server = subprocess.Popen(cmd, cwd=run if a.production_dir else project, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        deadline = time.time() + 480
        while time.time() < deadline:
            text = server_log.read_text(errors='replace')
            if 'Done (' in text and 'For help' in text:
                break
            if server.poll() is not None:
                raise RuntimeError('Server exited before ready; see ' + str(server_log))
            time.sleep(1)
        else:
            raise TimeoutError('Server startup timeout')
        env = dict(os.environ, MCLOGIN_EXPECT_CONFLICT='1' if a.scenario == 'conflict' else '0', MCLOGIN_RCON_PORT=str(a.port+1), MCLOGIN_RCON_PASSWORD=password)
        with probe_log.open('w') as out:
            probe = subprocess.run(['node', str(ROOT / 'tools/integration/probe.cjs'), a.version, str(a.port)], env=env, stdout=out, stderr=subprocess.STDOUT, timeout=150)
        output = probe_log.read_text()
        result['passed'] = probe.returncode == 0 and 'PartialReadError' not in output and 'Error:' not in output
        if result['passed']:
            details = json.loads(output)
            result['checks_passed'] = details['passed']
        if not result['passed']:
            result['error'] = 'Probe failed; see ' + str(probe_log)
    except Exception as error:
        result['passed'] = False
        result['error'] = str(error)
    finally:
        if server.poll() is None:
            try:
                server.stdin.write(b'stop\n'); server.stdin.flush()
                server.wait(timeout=25)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                os.killpg(server.pid, signal.SIGTERM)
                try:
                    server.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    os.killpg(server.pid, signal.SIGKILL)
                    server.wait()
        result['seconds'] = round(time.time() - started)
        (evidence / f'{name}.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
raise SystemExit(0 if result['passed'] else 1)
