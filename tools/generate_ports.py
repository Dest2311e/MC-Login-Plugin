#!/usr/bin/env python3
"""Regenerate standalone ports from the audited Forge 1.20.1 sources and adapters."""
from pathlib import Path
import json
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
TEMPLATES = ROOT / 'tools/port-templates'
VERSIONS = {
    '1.20.4': ('49.2.0', '0.97.3+1.20.4', 17, 22),
    '1.20.6': ('50.2.1', '0.100.8+1.20.6', 21, 32),
    '1.21.1': ('52.1.2', '0.116.17+1.21.1', 21, 34),
    '1.21.4': ('54.1.6', '0.119.4+1.21.4', 21, 46),
    '1.21.8': ('58.1.26', '0.136.1+1.21.8', 21, 81),
    '1.21.11': ('61.2.4', '0.141.6+1.21.11', 21, 94),
}

def port_readme(version, loader):
    forge, fabric_api, java, _ = VERSIONS[version]
    artifact = f'mclogin-{version}-{loader.lower()}-1.1.0.jar'
    dependencies = (f'Forge **{forge}**' if loader == 'Forge' else f'Fabric Loader **0.19.5** + Fabric API **{fabric_api}**')
    config = ('`<世界目录>/serverconfig/mclogin-server.toml`' if loader == 'Forge' else '`config/mclogin-server.properties`')
    extra = ('\nForgeGradle 7 构建还需 JDK 25，开发启动器另外解析 JDK 8；游戏服务器运行仍使用 Java 21。详见[构建工具链](../docs/PORTING.md#构建)。\n' if loader == 'Forge' and version in ['1.21.8', '1.21.11'] else '')
    production = ('本版本另已完成正式成品 JAR 的独立服务端协议检查。' if version in ['1.20.4', '1.21.11'] else '本版本尚未逐一完成正式成品 JAR 的独立服务端协议检查。')
    return f'''# MCLogin 1.1.0 · Minecraft {version} / {loader}

[返回项目首页](../README.md) · [全部版本](../docs/VERSIONS.md) · [模组安装与配置](../docs/MOD_GUIDE.md)

服务端登录验证模组，提供注册、登录、改密、注销、原版菜单与铁砧输入。仅在服务端安装；MCLogin 本身不要求客户端模组或资源包，其他服务端模组可能有各自的客户端要求。

## 版本与文件

| 项目 | 本工程 |
| --- | --- |
| Minecraft | **{version}** |
| 固定验证依赖 | {dependencies} |
| 游戏运行 Java | **{java}** |
| 交付文件 | `{artifact}` |
| 安装目录 | 服务端 `mods/` |
| 配置文件 | {config} |
| 账号库 | `config/accounts.yml` |

只选择与 Minecraft 和加载器同时对应的 JAR；不推断相邻游戏补丁版本兼容。Fabric 需要另外安装上述对应版本的 Fabric API。

## 安装与使用

1. 停服并备份世界、配置及账号库。
2. 安装表中的服务端加载器和依赖，将 `{artifact}` 放入 `mods/`。
3. 重启，按需调整生成的配置。配置路径中的世界目录由服务端世界设置决定。
4. 玩家进服后使用菜单或 `/register <密码> <确认密码>`、`/login <密码>` 完成认证；管理命令为 `/mclogin status` 与 `/mclogin reload`。

未登录时默认限制移动、聊天、交互和非认证命令，默认 120 秒超时。密码找回须接入外部身份验证器，默认不可用。完整命令、配置和迁移步骤见[使用指南](../docs/MOD_GUIDE.md)，部署身份要求见[安全说明](../SECURITY.md)。

## 构建

在本目录运行，先安装可被 Gradle 检测到的 JDK {java}：

```bash
./gradlew build --console=plain
# Windows: gradlew.bat build --console=plain
```

成品为 `build/libs/{artifact}`，不要安装 `-sources` 或 `-dev` 文件。`build` 包含 49 项安全回归断言。
{extra}
## 验证范围

已完成构建、安全断言及开发服务端协议检查。{production}各项检查、依赖指纹和边界见[移植与验证说明](../docs/PORTING.md)及[验证数据](../docs/port-validation.json)。协议测试不替代人工界面、音效或所有模组组合的验收。

本目录由 [generate_ports.py](../tools/generate_ports.py) 生成。修改公共逻辑时应同步生成器或模板；重新生成前先停止构建与测试服务端。
'''


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')


def adapt_java(text, version, loader):
    modern = version != '1.20.4'
    if modern:
        text = text.replace('.setHoverName(', '.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, ')
        text = text.replace('import net.minecraft.nbt.ListTag;', '').replace('import net.minecraft.nbt.StringTag;', '')
        text = text.replace('ListTag lore = new ListTag();', 'java.util.List<Component> lore = new java.util.ArrayList<>();')
        text = text.replace('lore.add(StringTag.valueOf(Component.Serializer.toJson(MessageService.component(loreKey))));', 'lore.add(MessageService.component(loreKey));')
        text = text.replace('stack.getOrCreateTagElement("display").put("Lore", lore);', 'stack.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));')
    if tuple(map(int, version.split('.'))) >= (1, 21, 4):
        text = text.replace('sound.getLocation()', 'sound.location()')
    if version in ['1.21.8', '1.21.11']:
        text = text.replace('player.getServer()', 'player.level().getServer()')
        text = text.replace('@Mixin(net.minecraft.world.entity.player.Player.class)', '@Mixin(ServerPlayer.class)')
        text = text.replace('import net.minecraftforge.eventbus.api.IEventBus;', '')
        text = text.replace('import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;', '')
        text = text.replace('net.minecraftforge.eventbus.api.SubscribeEvent', 'net.minecraftforge.eventbus.api.listener.SubscribeEvent')
        text = text.replace('if (!event.isCancelable())', 'if (!(event instanceof net.minecraftforge.eventbus.api.event.characteristic.Cancellable))')

    if loader == 'Forge' and version == '1.21.11':
        text = text.replace('final TickEvent.PlayerTickEvent event', 'final TickEvent.PlayerTickEvent.Pre event')
        phase = 'Post' if 'class LoginUiListener' in text else 'Pre'
        text = text.replace('final TickEvent.ServerTickEvent event', f'final TickEvent.ServerTickEvent.{phase} event')
        text = re.sub(r'        if \(event\.phase != TickEvent\.Phase\.(START|END)\) \{\n            return;\n        \}\n', '', text)
        text = text.replace('event.player instanceof', 'event.player() instanceof')
        if 'class LoginUiListener' in text:
            text = text.replace('event.getServer()', 'event.server()')
    if version == '1.21.11':
        text = text.replace('.requires(source -> source.hasPermission(3))', '.requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_ADMINS))')
        text = text.replace('player.playNotifySound(sound, SoundSource.MASTER,', 'player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.core.Holder.direct(sound), SoundSource.MASTER, player.getX(), player.getY(), player.getZ(),')
        text = text.replace('(float) (config.getSoundPitch() * pitchMultiplier));', '(float) (config.getSoundPitch() * pitchMultiplier), java.util.concurrent.ThreadLocalRandom.current().nextLong()));')
    # Avoid the removed global mod loading context in newer Forge versions.
    text = text.replace('        final IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();', '')
    text = re.sub(r'        if \(modBus == null\) \{.*?\n        \}', '', text, flags=re.S)
    if loader == 'Forge' and version in ['1.21.8', '1.21.11'] and 'class LoginRestrictionListener' in text:
        text = eventbus7_restrictions(text)
    return text


def eventbus7_restrictions(text):
    # EventBus 7 cancellation is the listener's boolean result, not mutable event state.
    for method in ['onPlayerInteract', 'onBlockBreak', 'onAttackEntity', 'onItemToss', 'onItemPickup', 'onServerChat', 'onCommand']:
        start = text.index('    @SubscribeEvent\n    public void ' + method + '(')
        opening = text.index('{', start)
        pos, depth = opening + 1, 1
        while depth:
            if text[pos] == '{': depth += 1
            if text[pos] == '}': depth -= 1
            pos += 1
        if method == 'onPlayerInteract':
            replacement = '\n'.join(
                '    @SubscribeEvent\n    public boolean on' + event + '(final PlayerInteractEvent.' + event + ' event) {\n'
                '        return event.getEntity() instanceof ServerPlayer player\n'
                '            && !mod.getSessions().isLoggedIn(player.getUUID()) && !mod.getConfig().isAllowInteract();\n'
                '    }' for event in ['RightClickBlock', 'RightClickItem', 'LeftClickBlock', 'EntityInteract', 'EntityInteractSpecific'])
        else:
            header = text[start:opening].replace('public void ', 'public boolean ')
            body = text[opening+1:pos-1].replace('return;', 'return canceled;').replace('event.setCanceled(true);', 'canceled = true;')
            replacement = header + '{\n        boolean canceled = false;' + body + '\n        return canceled;\n    }'
        text = text[:start] + replacement + text[pos:]
    text = text.replace('{@link #onPlayerInteract}', '{@code RightClickBlock}')
    return text


def generate(version, loader):
    forge, api, java, pack = VERSIONS[version]
    dest = ROOT / f'{version}+{loader}'
    # Only replace generated sources; retain build output and local run/config files.
    if (dest / 'src').exists(): shutil.rmtree(dest / 'src')
    shutil.copytree(ROOT / 'src', dest / 'src')
    for path in (dest / 'src').rglob('*.java'):
        write(path, adapt_java(path.read_text(), version, loader))
    for name in ['gradlew', 'gradlew.bat']:
        shutil.copy2(ROOT / name, dest / name)
    shutil.copytree(ROOT / 'gradle', dest / 'gradle', dirs_exist_ok=True)
    wrapper = (dest / 'gradle/wrapper/gradle-wrapper.properties').read_text().replace('gradle-8.1.1-bin.zip', 'gradle-8.14.3-bin.zip')
    if version in ['1.21.8', '1.21.11']:
        wrapper = wrapper.replace('gradle-8.14.3-bin.zip', 'gradle-9.5.0-bin.zip')
    write(dest / 'gradle/wrapper/gradle-wrapper.properties', wrapper)
    props = f'''minecraft_version={version}
forge_version={forge}
fabric_api_version={api}
fabric_loader_version=0.19.5
mod_id=mclogin
mod_version=1.1.0
mod_group_id=com.dsh.mclogin
java_version={java}
org.gradle.jvmargs=-Xmx2G
org.gradle.workers.max=2
org.gradle.daemon=false
'''
    write(dest / 'gradle.properties', props)
    write(dest / 'settings.gradle', '''pluginManagement {
    repositories {
        maven { url = 'https://maven.fabricmc.net/' }
        maven { url = 'https://maven.minecraftforge.net/' }
        gradlePluginPortal()
    }
}
rootProject.name = 'mclogin-%s-%s'
''' % (version, loader.lower()))
    if loader == 'Forge':
        build = '''plugins { id 'net.minecraftforge.gradle' version '6.0.54' }
version = mod_version
group = mod_group_id
base { archivesName = "mclogin-${minecraft_version}-forge" }
java.toolchain.languageVersion = JavaLanguageVersion.of(java_version as int)
minecraft {
    mappings channel: 'official', version: minecraft_version
    REOBF_SETTING
    runs {
        server {
            workingDirectory project.file('run')
            args '--nogui'
            mods { mclogin { source sourceSets.main } }
        }
    }
}
repositories { mavenCentral() }
dependencies { minecraft "net.minecraftforge:forge:${minecraft_version}-${forge_version}" }
JAR_SETTING
'''
        build = build.replace('REOBF_SETTING', '' if version == '1.20.4' else 'reobf = false')
        build += '''
// Forge's development mod locator needs classes and resources in one directory.
sourceSets.main.output.resourcesDir = sourceSets.main.java.destinationDirectory
'''
        build = build.replace('JAR_SETTING', "jar.finalizedBy 'reobfJar'" if version == '1.20.4' else '')
        if version in ['1.21.8', '1.21.11']:
            build = """plugins { id 'java'; id 'net.minecraftforge.gradle' version '7.0.40' }
version = mod_version
group = mod_group_id
base { archivesName = "mclogin-${minecraft_version}-forge" }
java.toolchain.languageVersion = JavaLanguageVersion.of(java_version as int)
minecraft {
    mappings channel: 'official', version: minecraft_version
    runs { register('server') { workingDir = layout.projectDirectory.dir('run'); args '--nogui' } }
}
repositories {
    minecraft.mavenizer(it)
    maven fg.forgeMaven
    maven fg.minecraftLibsMaven
    mavenCentral()
}
dependencies {
    implementation minecraft.dependency("net.minecraftforge:forge:${minecraft_version}-${forge_version}")
    annotationProcessor 'net.minecraftforge:eventbus-validator:7.0.1'
}
// Keep the development mod's classes and metadata under one locator root.
sourceSets.main.output.resourcesDir = sourceSets.main.java.destinationDirectory
"""
        major = int(forge.split('.')[0])
        write(dest / 'src/main/resources/META-INF/mods.toml', f'''modLoader="javafml"
loaderVersion="[{major},{major+1})"
license="MIT"
[[mods]]
modId="mclogin"
version="1.1.0"
displayName="MCLogin"
authors="DSH"
displayTest="IGNORE_SERVER_VERSION"
description=''' + "'''服务端登录验证，包含原版箱子/铁砧界面、PBKDF2、反暴力破解与密码找回。'''" + f'''
[[dependencies.mclogin]]
modId="forge"
mandatory=true
versionRange="[{forge},)"
ordering="NONE"
side="SERVER"
[[dependencies.mclogin]]
modId="minecraft"
mandatory=true
versionRange="[{version}]"
ordering="NONE"
side="SERVER"
''')
    else:
        build = '''plugins { id 'fabric-loom' version '1.10.5' }
version = mod_version
group = mod_group_id
base { archivesName = "mclogin-${minecraft_version}-fabric" }
java.toolchain.languageVersion = JavaLanguageVersion.of(java_version as int)
repositories { mavenCentral() }
dependencies {
    minecraft "com.mojang:minecraft:${minecraft_version}"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:${fabric_loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${fabric_api_version}"
}
loom { serverOnlyMinecraftJar() }
'''
        if version in ['1.21.8', '1.21.11']:
            build = build.replace("'1.10.5'", "'1.14.10'")
        fabric_sources(dest, version)
    build += '''
tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
    options.release = java_version as int
}
tasks.register('securityChecks', JavaExec) {
    dependsOn testClasses
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'com.dsh.mclogin.security.ResetTicketsTest'
}
tasks.named('check') { dependsOn 'securityChecks' }
'''
    if version in ['1.21.8', '1.21.11']:
        # The real regression suite runs through securityChecks (plain Java main).
        build += '\ntasks.named("test") { failOnNoDiscoveredTests = false }\n'
    write(dest / 'build.gradle', build)
    write(dest / 'src/main/resources/pack.mcmeta', json.dumps({'pack': {'pack_format': pack, 'description': f'MCLogin {version} {loader}'}}, ensure_ascii=False, indent=2)+'\n')
    write(dest / 'README.md', port_readme(version, loader))


def fabric_sources(dest, version):
    base = dest / 'src/main/java/com/dsh/mclogin'
    for path in TEMPLATES.joinpath('fabric').rglob('*.java'):
        write(base / path.relative_to(TEMPLATES / 'fabric'), adapt_java(path.read_text(), version, 'Fabric'))
    (dest / 'src/main/resources/META-INF/mods.toml').unlink()
    metadata = {'schemaVersion': 1, 'id': 'mclogin', 'version': '1.1.0', 'name': 'MCLogin', 'authors': ['DSH'], 'license': 'MIT', 'environment': 'server', 'entrypoints': {'server': ['com.dsh.mclogin.MCLoginMod']}, 'mixins': ['mclogin.mixins.json'], 'depends': {'fabricloader': '>=0.19.5', 'minecraft': version, 'java': '>='+str(VERSIONS[version][2]), 'fabric-api': '*'}}
    write(dest / 'src/main/resources/fabric.mod.json', json.dumps(metadata, indent=2)+'\n')
    mixins = {'required': True, 'package': 'com.dsh.mclogin.mixin', 'compatibilityLevel': 'JAVA_'+str(VERSIONS[version][2]), 'mixins': ['AnvilMenuAccess', 'ConnectionGuardMixin', 'PlayerGuardMixin', 'ItemPickupGuardMixin', 'CommandGuardMixin'], 'injectors': {'defaultRequire': 1}}
    write(dest / 'src/main/resources/mclogin.mixins.json', json.dumps(mixins, indent=2)+'\n')
    # Convert lifecycle-independent listeners to direct callbacks.
    for name in ['listener/PlayerConnectionListener.java', 'ui/LoginUiListener.java']:
        path=base/name
        s=path.read_text()
        s=re.sub(r'^import net\.minecraftforge\..*;\n', '', s, flags=re.M).replace('    @SubscribeEvent\n','')
        for event in ['PlayerLoggedInEvent','PlayerLoggedOutEvent','PlayerRespawnEvent']:
            s=s.replace(f'final PlayerEvent.{event} event', 'final ServerPlayer player')
        s=re.sub(r'        if \(!\(event.getEntity\(\) instanceof ServerPlayer player\)\) \{\n            return;\n        \}\n','',s)
        s=s.replace('event.getEntity().getUUID()', 'player.getUUID()')
        s=s.replace('event == null || event.getEntity() == null', 'player == null')
        s=s.replace('final TickEvent.ServerTickEvent event', 'final MinecraftServer server')
        s=s.replace('        if (event.phase != TickEvent.Phase.END) {\n            return;\n        }\n','')
        s=s.replace('        final MinecraftServer server = event.getServer();\n','')
        write(path,s)
    path=base/'security/PasswordResetService.java'
    s=path.read_text().replace('import net.minecraftforge.server.ServerLifecycleHooks;','').replace('ServerLifecycleHooks.getCurrentServer()', 'MCLoginMod.getInstance().getServer()')
    write(path,s)
    path=base/'ui/AnvilInput.java'
    s=path.read_text().replace('this.setMaximumCost(0);','((com.dsh.mclogin.mixin.AnvilMenuAccess) (Object) this).mclogin$getCost().set(0);')
    write(path,s)


if __name__ == '__main__':
    import argparse
    parser=argparse.ArgumentParser()
    parser.add_argument('--loader', choices=['Forge','Fabric'])
    parser.add_argument('--version', choices=VERSIONS)
    args=parser.parse_args()
    for version in ([args.version] if args.version else VERSIONS):
        for loader in ([args.loader] if args.loader else ['Forge','Fabric']):
            generate(version, loader)
            print(f'{version}+{loader}')
