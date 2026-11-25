# SafeWalk v5.5.1 完整源码 + 签名密钥包

打包时间：2026-11-21
对应提交：`909d6de`（GitHub `Xposed-Modules-Repo/io.github.wumisc.safewalk` main）
版本：versionCode **86** / versionName **5.5.1**

## 包内结构

```
SafeWalk-5.5.1-source/
├── app/                          # 应用源码（Java/Kotlin + Compose + Miuix）
│   ├── src/main/java/...         # 主代码：步态、相机、急救卡、磁贴、隐私开关
│   ├── src/main/res/             # 布局、资源、图标
│   └── src/test/java/...         # 879 项单元测试
├── gradle/ + gradlew + gradlew.bat   # Gradle Wrapper（8.x）
├── docs/                         # 方案文档（PRD/架构/诊断/ROM 适配）
├── tools/                        # 诊断脚本
├── build.gradle / settings.gradle / gradle.properties
├── CHANGELOG.md / README.md / LICENSE
├── safewalk-release.jks          # ★ 私有签名密钥库（RSA 4096）
├── keystore.properties           # ★ 签名口令配置
├── 签名密钥说明.md                # ★ 口令、指纹、备份与恢复说明
└── local.properties.example      # SDK 路径示例（复制为 local.properties 后改路径）
```

未包含：`build/`、`app/build/`、`.gradle/`（构建产物，约 258MB，可重新生成）、`local.properties`（本机路径）。

## 构建步骤

```bash
# 1) 配置 SDK 路径
cp local.properties.example local.properties
#    编辑 local.properties，写入你的 sdk.dir

# 2) 编译正式包（会自动读取 keystore.properties 用私有密钥签名）
JAVA_HOME=/opt/jdk-21 PATH=/opt/jdk-21/bin:$PATH bash gradlew assembleRelease --console=plain

# 产物：app/build/outputs/apk/release/app-release.apk
```

> 环境要求：JDK 21、Android SDK Platform 37 + Build-Tools 35、Kotlin 2.x（由 Gradle 自动拉取依赖）。
> 若 `keystore.properties` 缺失，构建会回退 debug 签名——此时不可用于发布。

## 校验签名

```bash
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk | grep SHA256
# 期望：5F:13:31:D0:78:B1:2D:2F:DD:5D:D8:44:E1:A4:AF:CC:CD:03:D1:2B:E2:8B:41:C8:50:CE:1E:8C:65:2E:FD:84
```

## 运行测试

```bash
JAVA_HOME=/opt/jdk-21 PATH=/opt/jdk-21/bin:$PATH bash gradlew cleanTestDebugUnitTest testDebugUnitTest --console=plain
```

## ⚠️ 安全提醒

本包含私钥与口令。**不要上传到公开仓库、公共网盘、聊天群**。建议加密压缩（AES-256）后再存储，并把口令单独记在密码管理器。丢失私钥将导致后续版本无法覆盖升级老用户。
