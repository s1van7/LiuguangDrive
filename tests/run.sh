#!/usr/bin/env bash
# 纯 JVM 自检，不需要 Android SDK 或设备
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/tests
rm -rf "$OUT" && mkdir -p "$OUT"
javac -encoding UTF-8 -d "$OUT" \
    src/cn/liuguang/drive/LrcParser.java \
    src/cn/liuguang/drive/Keys.java \
    src/cn/liuguang/drive/LyricStream.java \
    src/cn/liuguang/drive/LyricProbe.java \
    tests/SelfTest.java
java -cp "$OUT" cn.liuguang.drive.SelfTest
