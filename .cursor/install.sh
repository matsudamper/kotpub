#!/usr/bin/env bash
set -euo pipefail

./gradlew --no-daemon --init-script gradle/resolve-dependencies.init.gradle.kts resolveDependencies
