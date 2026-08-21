#!/usr/bin/env bash
# 喵仓 WSL 构建环境（source 本文件后即可构建）：
#   source scripts/dev-env-wsl.sh
# 然后：cd android-app && ./gradlew assembleDebug
#
# 说明：
# - JDK 17 为解包的 Ubuntu 版（~/jdk17），cacerts 已导入系统 CA + 腾讯镜像信任；
#   因 dpkg 解包的 JDK 默认找不到 cacerts，需显式指定 trustStore。
# - Android SDK 已迁到 WSL 本地，路径 ~/Android/Sdk，见 android-app/local.properties。
# - github.com / maven.google.com 被宿主机 SteamTools 中间人劫持，
#   Gradle 发行版走腾讯镜像，依赖仓库走阿里云镜像（settings.gradle.kts 已配置）。

export JAVA_HOME="$HOME/jdk17/usr/lib/jvm/java-17-openjdk-amd64"
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$JAVA_HOME/lib/security/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
echo "[meow-dev] JAVA_HOME=$JAVA_HOME"
echo "[meow-dev] ANDROID_HOME=$ANDROID_HOME"
echo "[meow-dev] 环境就绪喵，cd android-app && ./gradlew assembleDebug"
