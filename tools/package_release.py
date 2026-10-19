#!/usr/bin/env python3
"""Collect already validated production JARs and docs into GitHub Release assets.

This checks recorded source/artifact digests; it does not replace runtime testing.
Run tools/package_plugin.py first when regenerating its integration evidence.
"""
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / 'dist'
VERSION = '1.1.0'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_source(project, expected, names):
    files = sorted(set(project.glob('src/**/*')) | {project / n for n in names})
    digest = hashlib.sha256(''.join(f'{sha(p)}  {p.relative_to(project)}\n' for p in files if p.is_file()).encode()).hexdigest()
    if digest != expected:
        raise SystemExit(f'Source differs from validation record: {project.name}')


def main():
    ports = json.loads((ROOT / 'docs/port-validation.json').read_text())
    plugin = json.loads((ROOT / 'docs/plugin-validation.json').read_text())
    builds = json.loads((DIST / 'manifest.json').read_text())
    if len(ports['ports']) != 12 or len(builds['builds']) != 12:
        raise SystemExit('Expected twelve validated ports and builds')
    entries = {}
    artifacts = []
    for row in ports['ports']:
        project = ROOT / row['project']
        check_source(project, row['source_digest'], ['build.gradle', 'settings.gradle', 'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties'])
        jar = DIST / row['build']['artifact']
        if not row['build']['passed'] or sha(jar) != row['build']['sha256']:
            raise SystemExit(f'Artifact differs from validation record: {jar}')
        matches = [b for b in builds['builds'] if b.get('artifact') == row['build']['artifact'] and b.get('sha256') == row['build']['sha256'] and b['exit_code'] == 0]
        if len(matches) != 1:
            raise SystemExit(f'Build manifest mismatch: {jar}')
        entries[str(jar.relative_to(DIST))] = jar.read_bytes()
        entries[f'{project.name}/README.md'] = (project / 'README.md').read_bytes()
        artifacts.append(jar)
    for name in ['MOD_GUIDE.md', 'VERSIONS.md', 'PORTING.md', 'PASSWORD_RESET_API.md', 'port-validation.json']:
        entries['docs/' + name] = (ROOT / 'docs' / name).read_bytes()
    entries['SECURITY.md'] = (ROOT / 'SECURITY.md').read_bytes()
    entries['manifest.json'] = (DIST / 'manifest.json').read_bytes()
    entries['README.md'] = '''# MCLogin 1.1.0 · Forge / Fabric 模组包

本包包含六个 Minecraft 版本各自的 Forge / Fabric 成品，共 12 个 JAR。
按“游戏版本+加载器”文件夹选择对应 JAR，放入服务端 mods/；Fabric 另装对应 Fabric API。
1.20.4 使用 Java 17，其余目标版本使用 Java 21。客户端无需 MCLogin。

每个目录的 README.md 说明固定依赖、文件名、配置路径和验证范围。
通用安装和配置：docs/MOD_GUIDE.md；版本总览：docs/VERSIONS.md。
部署身份、账号与备份要求：SECURITY.md；检查范围：docs/PORTING.md。
SHA256SUMS 校验本包内各文件；manifest.json 记录构建，docs/port-validation.json 记录验证。

根 Forge 1.20.1 工程及 Paper 插件属于独立交付，源码与完整文档在：
https://github.com/Dest2311e/MC-Login-Plugin

未包含第三方依赖、账号库、运行日志和测试夹具。协议检查不代表所有组合与人工外观已验收。
'''.encode()
    entries['SHA256SUMS'] = ''.join(f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name, data in sorted(entries.items())).encode()
    archive = DIST / f'MCLogin-{VERSION}-ports.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(entries.items()):
            z.writestr(name, data)
    archive.with_suffix('.zip.sha256').write_text(f'{sha(archive)}  {archive.name}\n')
    check_source(ROOT / 'Paper-Spigot', plugin['source_digest'], ['build.gradle', 'settings.gradle', 'gradle/wrapper/gradle-wrapper.properties'])
    plugin_jar = DIST / 'Paper-Spigot/mclogin-paper-spigot-1.1.0.jar'
    if sha(plugin_jar) != plugin['artifact_sha256']:
        raise SystemExit('Plugin JAR differs from runtime-tested artifact')
    with zipfile.ZipFile(DIST / 'MCLogin-1.1.0-plugin-compat.zip') as z:
        if z.testzip() or hashlib.sha256(z.read('Paper-Spigot/' + plugin_jar.name)).hexdigest() != plugin['artifact_sha256']:
            raise SystemExit('Invalid plugin archive')
    assets = DIST / 'release'
    assets.mkdir(exist_ok=True)
    files = artifacts + [plugin_jar, archive, DIST / 'MCLogin-1.1.0-plugin-compat.zip']
    for path in files:
        shutil.copy2(path, assets / path.name)
    (assets / 'SHA256SUMS').write_text(''.join(f'{sha(p)}  {p.name}\n' for p in sorted(assets.iterdir()) if p.name != 'SHA256SUMS' and p.is_file()))
    print(f'Prepared {len(files)} release artifacts + SHA256SUMS in {assets}')


if __name__ == '__main__':
    main()
