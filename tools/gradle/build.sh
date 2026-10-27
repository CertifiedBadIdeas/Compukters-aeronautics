#!/bin/sh
# The Compukters Developers
#
# Copyright 2026 The Compukters Developers
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Adapted from Orbitalis for the independent Orbitalis–Compukters stand and nested source repositories.
set -eu

python3 "$FLIGHT_DEV_BUILD_ROOT/tools/gradle/cache.py" --root "$FLIGHT_DEV_BUILD_ROOT" prepare
if flock --shared "$GRADLE_USER_HOME/.flight-dev-cache.lock" \
    "$FLIGHT_DEV_BUILD_GRADLE" --no-daemon --project-cache-dir "$FLIGHT_DEV_BUILD_ROOT/.gradle-agent" -Dorg.gradle.vfs.watch=false "$@"; then
    python3 "$FLIGHT_DEV_BUILD_ROOT/tools/gradle/cache.py" --root "$FLIGHT_DEV_BUILD_ROOT" finish || \
        printf 'Gradle succeeded; its cache template could not be refreshed.\n' >&2
else
    exit "$?"
fi
