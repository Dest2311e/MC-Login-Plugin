#!/usr/bin/env python3
"""Build standalone ports sequentially and collect their production jars and hashes."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from generate_ports import VERSIONS

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--version', choices=VERSIONS, action='append')
p.add_argument('--loader', choices=['Forge', 'Fabric'], action='append')
p.add_argument('--gradle8', type=Path, help='Use an existing Gradle 8 installation executable')
p.add_argument('--gradle9', type=Path, help='Use an existing Gradle 9 installation executable')
p.add_argument('--jdk17', type=Path)
p.add_argument('--jdk21', type=Path)
p.add_argument('--jdk25', type=Path)
p.add_argument('--output', type=Path, default=ROOT / 'dist')
a = p.parse_args()
output = a.output.resolve()
output.mkdir(parents=True, exist_ok=True)
results = []
for version in a.version or VERSIONS:
    for loader in a.loader or ['Forge', 'Fabric']:
        name = f'{version}+{loader}'
        project = ROOT / name
        newer = version in ['1.21.8', '1.21.11']
        gradle = a.gradle9 if newer else a.gradle8
        cmd = [str(gradle.resolve() if gradle else project / 'gradlew'), 'build', '--console=plain', '--no-daemon']
        toolchains = [str(jdk.resolve()) for jdk in [a.jdk17, a.jdk21, a.jdk25] if jdk]
        if toolchains:
            cmd.append('-Porg.gradle.java.installations.paths=' + ','.join(toolchains))
        env = dict(os.environ)
        runtime = a.jdk17 if version == '1.20.4' and loader == 'Forge' else a.jdk21
        if runtime:
            env['JAVA_HOME'] = str(runtime.resolve())
        started = time.monotonic()
        logs = ROOT / '.codex-work/build-logs'
        logs.mkdir(parents=True, exist_ok=True)
        log = logs / f'{name}.log'
        target = output / name
        target.mkdir(exist_ok=True)
        jar = target / f'mclogin-{version}-{loader.lower()}-1.1.0.jar'
        # A failed rebuild must not leave an older jar looking like a new success.
        jar.unlink(missing_ok=True)
        with log.open('w') as stream:
            built = subprocess.run(cmd, cwd=project, env=env, stdout=stream, stderr=subprocess.STDOUT)
        result = {'minecraft': version, 'loader': loader, 'exit_code': built.returncode, 'seconds': round(time.monotonic() - started), 'log': str(log.relative_to(ROOT))}
        text = log.read_text(errors='replace')
        if built.returncode == 0 and 'BUILD SUCCESSFUL' in text:
            source = project / 'build/libs' / jar.name
            shutil.copy2(source, jar)
            result.update(artifact=str(jar.relative_to(output)), sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
        results.append(result)
        (output / 'manifest.json').write_text(json.dumps({'generated_at': datetime.now(timezone.utc).isoformat(), 'builds': results}, ensure_ascii=False, indent=2) + '\n')
        print(f'{name}: {"PASS" if "sha256" in result else "FAIL"} ({result["seconds"]}s)', flush=True)
(output / 'SHA256SUMS').write_text(''.join(f'{r["sha256"]}  {r["artifact"]}\n' for r in results if 'sha256' in r))
sys.exit(1 if any('sha256' not in r for r in results) else 0)
