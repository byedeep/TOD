#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
mkdir -p .tools
jdk_url='https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u504-b01/OpenJDK8U-jdk_x64_linux_hotspot_8u504b01.tar.gz'
jdk_sha='9c70e102f527ac674ac2fe9c7d47b9a04e2d19842ba5ab8e9b33f368bbadfaea'
if [[ ! -x .tools/jdk8u504-b01/bin/java ]]; then
  if [[ ! -f .tools/jdk8.tar.gz ]]; then
    curl -fL --retry 3 "$jdk_url" -o .tools/jdk8.tar.gz.part
    mv .tools/jdk8.tar.gz.part .tools/jdk8.tar.gz
  fi
  printf '%s  %s\n' "$jdk_sha" '.tools/jdk8.tar.gz' | sha256sum -c -
  tar -xzf .tools/jdk8.tar.gz -C .tools
fi
if [[ ! -x .tools/gradle-2.14.1/bin/gradle ]]; then
  curl -fL --retry 3 https://services.gradle.org/distributions/gradle-2.14.1-bin.zip -o .tools/gradle.zip.part
  printf '%s  %s\n' 'cfc61eda71f2d12a572822644ce13d2919407595c2aec3e3566d2aab6f97ef39' '.tools/gradle.zip.part' | sha256sum -c -
  unzip -q -o .tools/gradle.zip.part -d .tools
fi
.tools/jdk8u504-b01/bin/java -version
