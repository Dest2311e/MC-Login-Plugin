#!/usr/bin/env python3
"""Package the tested Bukkit plugin, with version-pinned local integration evidence."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import shutil
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.codex-work'
PROJECT = ROOT / 'Paper-Spigot'
ARTIFACT = PROJECT / 'build/libs/mclogin-paper-spigot-1.1.0.jar'
VERSIONS = ['1.20.4', '1.20.6', '1.21.1', '1.21.4', '1.21.8', '1.21.11']
WORLDGUARD = dict(zip(VERSIONS, ['7.0.9-dist', '7.0.10-dist', '7.0.12-dist', '7.0.13-dist', '7.0.14-dist', '7.0.15']))
CORE = ['data/PlayerAccount.java', 'data/YamlStorage.java', 'security/PasswordHasher.java',
        'security/HashExecutor.java', 'security/BruteForceGuard.java', 'security/ResetTickets.java',
        'api/PasswordResetVerifier.java']


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def evidence(path):
    return {'path': str(path.relative_to(ROOT)), 'sha256': sha(path)}


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    digest = sha(ARTIFACT)
    with zipfile.ZipFile(ARTIFACT) as jar:
        classes = [n for n in jar.namelist() if n.endswith('.class')]
        require(classes and all(n.startswith('com/dsh/mclogin/') for n in classes), 'Unexpected bundled classes')
        require(all(struct.unpack('>H', jar.read(n)[6:8])[0] == 61 for n in classes), 'Java 17 class target mismatch')
        require('plugin.yml' in jar.namelist(), 'Missing Bukkit plugin descriptor')
    for relative in CORE:
        path = Path('src/main/java/com/dsh/mclogin') / relative
        require(sha(ROOT / path) == sha(PROJECT / path), 'Shared core differs: ' + relative)
    build_log = WORK / 'bukkit-build.log'
    text = build_log.read_text()
    for marker in ['BUILD SUCCESSFUL', 'ResetTickets: 20 checks passed', 'Storage: 29 checks passed', 'CommandPolicy: 36 checks passed']:
        require(marker in text, 'Missing build verification: ' + marker)
    inputs = sorted(set(PROJECT.glob('src/**/*')) | {PROJECT / f for f in ['build.gradle', 'settings.gradle', 'gradle/wrapper/gradle-wrapper.properties']})
    source_digest = hashlib.sha256(''.join(f'{sha(p)}  {p.relative_to(PROJECT)}\n' for p in inputs if p.is_file()).encode()).hexdigest()
    downloads = json.loads((ROOT / 'tools/integration/bukkit-artifacts.json').read_text())
    by_file = {row['file']: row for row in downloads}
    runs = []
    for version, scenario, count in [(v, 'authentication', 56) for v in VERSIONS] + [('1.20.4', 'minimal', 18), ('1.20.4', 'conflict', 4)]:
        suffix = '' if scenario == 'authentication' else '-' + scenario
        name = f'{version}-paper-production{suffix}'
        result_path = WORK / f'runtime/{name}.json'
        result = json.loads(result_path.read_text())
        require(result['passed'] and result['checks_passed'] == count, 'Failed or incomplete test: ' + name)
        require(result['artifact_sha256'] == digest, 'Tested artifact mismatch: ' + name)
        probe = WORK / f'runtime/{name}-probe.log'
        server = WORK / f'runtime/{name}-server.log'
        details = json.loads(probe.read_text())
        require(len(details['checks']) == count, 'Probe detail count mismatch: ' + name)
        log = server.read_text(errors='replace')
        for password in ['Test-password-42', 'Wrong-password-42', 'Reset-password-84', 'Revoked-password-90', 'Final-password-95']:
            require(password not in log, 'Test credential leaked to standard server log: ' + name)
        files = []
        if scenario == 'authentication':
            files = ['LuckPerms-Bukkit-5.5.71.jar', 'EssentialsX-2.22.0.jar', 'Vault.jar', 'PlaceholderAPI-2.11.6.jar',
                     'worldedit-bukkit-' + ('7.2.20' if version == '1.20.4' else '7.3.9' if version in ['1.20.6', '1.21.1'] else '7.4.0') + '.jar',
                     f'worldguard-bukkit-{WORLDGUARD[version]}.jar']
            if version == '1.21.11':
                files += ['Geyser-Spigot.jar', 'floodgate-spigot.jar', 'ViaVersion-5.12.1.jar']
            for plugin in ['LuckPerms', 'Essentials', 'Vault', 'PlaceholderAPI', 'WorldEdit', 'WorldGuard', 'MCLogin', 'MCLoginTestFixture']:
                require(f'[{plugin}] Enabling ' in log, f'{plugin} not enabled: {name}')
            if version == '1.21.11':
                for plugin in ['Geyser-Spigot', 'floodgate', 'ViaVersion']:
                    require(f'[{plugin}] Enabling ' in log, f'{plugin} not enabled: {name}')
                require('Started Geyser on' in log, 'Geyser listener not started')
        elif scenario == 'conflict':
            files = ['AuthMe-6.0.1-Spigot-Legacy.jar']
        artifacts = [by_file[f] for f in [f'paper-{version}.jar'] + files]
        server_dir = WORK / 'bukkit' / ('server-' + (version if scenario == 'authentication' else scenario))
        for row in artifacts:
            installed = server_dir / ('paper.jar' if row['file'].startswith('paper-') else 'plugins/' + row['file'])
            require(sha(installed) == row['sha256'], 'Installed dependency checksum mismatch: ' + row['file'])
            require(sha(WORK / 'bukkit/downloads' / row['file']) == row['sha256'], 'Dependency checksum mismatch: ' + row['file'])
        runs.append({**result, 'scenario': scenario, 'java_major': 17 if version == '1.20.4' else 21,
                     'checks': details['checks'], 'test_artifacts': artifacts,
                     'evidence': [evidence(p) for p in [result_path, probe, server]], 'standard_server_log_contains_test_passwords': False})
    record = {'schema_version': 1, 'validated_at_utc': datetime.now(timezone.utc).isoformat(),
              'project': 'Paper-Spigot', 'artifact': ARTIFACT.name, 'artifact_sha256': digest,
              'source_digest': source_digest,
              'source_digest_definition': 'SHA-256 of sorted UTF-8 lines: <sha256>  <project-relative path>\\n for src/**, build.gradle, settings.gradle, gradle/wrapper/gradle-wrapper.properties',
              'environment': {'platform': platform.platform(), 'bytecode_java': 17, 'protocol_client': 'minecraft-protocol 1.68.0'},
              'shared_core_files_identical_to_root': CORE,
              'build': {'security_assertions': 49, 'compatibility_assertions': 36, 'evidence': evidence(build_log)},
              'test_fixture': evidence(ROOT / 'tools/integration/bukkit-fixture/build/libs/mclogin-compatibility-test-fixture.jar'),
              'probe_source': evidence(ROOT / 'tools/integration/probe.cjs'),
              'harness_source': evidence(ROOT / 'tools/integration/run.py'),
              'runs': runs,
              'limits': ['Spigot API compilation only; no standalone Spigot runtime acceptance',
                         'Geyser/Floodgate startup and Java player authentication only; no real Bedrock client',
                         'WorldGuard global block-break event probe; not an exhaustive region configuration test',
                         'No Folia, hybrid Forge/Bukkit, cross-server shared sessions or load testing',
                         'Log suppression covers standard server log; third-party spy/audit logs are outside this guarantee']}
    validation = ROOT / 'docs/plugin-validation.json'
    validation.write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
    dist = ROOT / 'dist'
    target = dist / 'Paper-Spigot' / ARTIFACT.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ARTIFACT, target)
    manifest = {'name': 'MCLogin Bukkit plugin compatibility', 'version': '1.1.0', 'artifact': str(target.relative_to(dist)),
                'sha256': digest, 'paper_versions_tested': VERSIONS, 'java_bytecode': 17,
                'security_assertions': 49, 'compatibility_assertions': 36, 'integration_checks_per_version': 56}
    manifest_path = dist / 'plugin-manifest.json'
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    readme = '''# MCLogin 1.1.0 Bukkit 插件兼容包\n\n把 Paper-Spigot/mclogin-paper-spigot-1.1.0.jar 放入服务端 plugins/ 后重启。\nPaper 六个目标版本已使用同一份 JAR 实测；原 Forge / Fabric JAR 在独立的 ports 包中。\n\n安装与命令：Paper-Spigot/README.md\n实测矩阵：docs/PLUGIN_COMPATIBILITY.md\n开发接口：docs/BUKKIT_API.md\n机器可读证据：docs/plugin-validation.json\n\n本包不包含第三方插件或测试夹具。没有独立 Spigot 运行验收、真实基岩客户端验收或混合核心支持声明。\n'''
    entries = {'README.md': readme.encode(), 'manifest.json': manifest_path.read_bytes(),
               str(target.relative_to(dist)): target.read_bytes(),
               'Paper-Spigot/README.md': (PROJECT / 'README.md').read_bytes()}
    entries['SECURITY.md'] = (ROOT / 'SECURITY.md').read_bytes()
    for f in ['PLUGIN_COMPATIBILITY.md', 'BUKKIT_API.md', 'plugin-validation.json', 'VERSIONS.md']:
        entries['docs/' + f] = (ROOT / 'docs' / f).read_bytes()
    sums = ''.join(f'{hashlib.sha256(content).hexdigest()}  {name}\n' for name, content in sorted(entries.items()))
    entries['SHA256SUMS'] = sums.encode()
    (dist / 'PLUGIN-SHA256SUMS').write_text(sums)
    archive = dist / 'MCLogin-1.1.0-plugin-compat.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, content in entries.items():
            z.writestr(name, content)
    print(json.dumps({'archive': str(archive.relative_to(ROOT)), 'archive_sha256': sha(archive),
                      'artifact_sha256': digest, 'runtime_scenarios': len(runs), 'runtime_checks': sum(len(r['checks']) for r in runs)}, indent=2))


if __name__ == '__main__':
    main()
