#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/imaginemetal/metalods" "$TMP/org/jspecify/annotations"
# Fixtures only: IDs 0..3 have the production kind numbering. No game registries/GPU allocations.
cat > "$TMP/imaginemetal/metalods/MetaPalette.java" <<'JAVA'
package imaginemetal.metalods;
final class MetaPalette { static int kind(int id) { return id; } }
JAVA
cat > "$TMP/org/jspecify/annotations/Nullable.java" <<'JAVA'
package org.jspecify.annotations;
@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE})
public @interface Nullable {}
JAVA
"${JAVA_HOME:?Set JAVA_HOME to JDK 25}/bin/javac" --release 25 -d "$TMP/classes" \
  "$TMP/imaginemetal/metalods/MetaPalette.java" "$TMP/org/jspecify/annotations/Nullable.java" \
  "$ROOT/mod/src/main/java/imaginemetal/metalods/Voxel.java" \
  "$ROOT/mod/src/main/java/imaginemetal/metalods/Rle.java" \
  "$ROOT/mod/src/main/java/imaginemetal/metalods/MetaMesher.java" \
  "$ROOT/mod/src/main/java/imaginemetal/metalods/LatestIngest.java" \
  "$ROOT/mod/src/main/java/imaginemetal/metalods/TileGate.java" \
  "$ROOT/mod/src/main/java/imaginemetal/metalods/TileJobs.java" \
  "$ROOT/mod/src/main/java/imaginemetal/metalods/DetailJobs.java" \
  "$ROOT/ci/metalods/DetailCheck.java" \
  "$ROOT/ci/metalods/SchedulingCheck.java" \
  "$ROOT/ci/metalods/IngestCheck.java" \
  "$ROOT/ci/metalods/GeometryCheck.java" \
  "$ROOT/ci/metalods/CaveCheck.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" imaginemetal.metalods.GeometryCheck
"$JAVA_HOME/bin/java" -cp "$TMP/classes" imaginemetal.metalods.CaveCheck

"$JAVA_HOME/bin/java" -cp "$TMP/classes" imaginemetal.metalods.IngestCheck
"$JAVA_HOME/bin/java" -cp "$TMP/classes" imaginemetal.metalods.SchedulingCheck
"$JAVA_HOME/bin/java" -cp "$TMP/classes" imaginemetal.metalods.DetailCheck
python3 "$ROOT/ci/metalods/coverage_check.py"

"$JAVA_HOME/bin/java" "$ROOT/ci/metalods/ZipInterruptCheck.java"
