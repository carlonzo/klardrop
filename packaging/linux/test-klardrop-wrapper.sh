#!/usr/bin/env bash
#
# Unit tests for packaging/linux/klardrop. No Compose: a fake `java` records argv.
# Run from the repo root or via :desktop:jvmTest (Linux only).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WRAPPER="$ROOT/packaging/linux/klardrop"
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

bash -n "$WRAPPER" || fail "bash -n packaging/linux/klardrop"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/app/bin" "$tmp/app/lib/app" "$tmp/bin" "$tmp/core"
cp "$WRAPPER" "$tmp/app/bin/klardrop"
chmod +x "$tmp/app/bin/klardrop"
echo dummy > "$tmp/app/lib/app/klardrop.jar"
touch "$tmp/app/lib/app/libskiko-linux-x64.so"

# Coreutils PATH with no java, so shebang + dirname/awk work.
for c in bash sh dirname basename cat printf awk grep uname readlink pwd ls; do
  if p="$(command -v "$c" 2>/dev/null)"; then
    ln -sf "$p" "$tmp/core/$c"
  fi
done
COREPATH="$tmp/core"

SCRIPT_REAL="$(readlink -f "$tmp/app/bin/klardrop")"

# --- no java -----------------------------------------------------------------
if PATH="$COREPATH" JAVA_HOME="" "$tmp/app/bin/klardrop" >"$tmp/out" 2>"$tmp/err"; then
  fail "expected failure when java is missing"
fi
grep -q "Java 21" "$tmp/err" || fail "missing Java 21 hint, got: $(cat "$tmp/err")"
grep -q "pacman -S jre-openjdk" "$tmp/err" || fail "missing pacman hint"
grep -q "apt install openjdk-21-jre" "$tmp/err" || fail "missing apt hint"
grep -q "dnf install java-21-openjdk" "$tmp/err" || fail "missing dnf hint"

write_java() {
  local version_line="$1"
  cat > "$tmp/bin/java" <<EOF
#!/usr/bin/env bash
if [ "\${1:-}" = "-version" ]; then
  echo '${version_line}' >&2
  exit 0
fi
printf '%s\\n' "\$@" > "$tmp/java.args"
printf '%s\\n' "\$KLARDROP_LAUNCHER" > "$tmp/java.env-launcher"
exit 0
EOF
  chmod +x "$tmp/bin/java"
}

# --- java 17 is rejected -----------------------------------------------------
write_java 'openjdk version "17.0.12" 2024-07-16'
if PATH="$tmp/bin:$COREPATH" JAVA_HOME="" "$tmp/app/bin/klardrop" >"$tmp/out" 2>"$tmp/err"; then
  fail "expected failure on Java 17"
fi
grep -q "too old" "$tmp/err" || fail "expected too-old message, got: $(cat "$tmp/err")"
[ ! -f "$tmp/java.args" ] || fail "java 17 must not exec MainKt"

# --- java 21 execs with launcher property, jvmArgs, classpath, MainKt --------
rm -f "$tmp/java.args" "$tmp/java.env-launcher"
write_java 'openjdk version "21.0.8" 2025-07-15'
PATH="$tmp/bin:$COREPATH" JAVA_HOME="" "$tmp/app/bin/klardrop" --debug --data-dir=/tmp/x \
  >"$tmp/out" 2>"$tmp/err" || fail "java 21 should exec: $(cat "$tmp/err")"

grep -qx -- "-Xms24m" "$tmp/java.args" || fail "missing -Xms24m"
grep -qx -- "-Xmx192m" "$tmp/java.args" || fail "missing -Xmx192m"
grep -qx -- "-XX:+UseG1GC" "$tmp/java.args" || fail "missing G1 flag"
grep -qx -- "-Dklardrop.launcher=$SCRIPT_REAL" "$tmp/java.args" \
  || fail "missing -Dklardrop.launcher=$SCRIPT_REAL in: $(cat "$tmp/java.args")"
grep -q -- "-Dskiko.library.path=$tmp/app/lib/app" "$tmp/java.args" \
  || fail "missing skiko.library.path"
grep -qx -- "MainKt" "$tmp/java.args" || fail "missing MainKt"
grep -qx -- "--debug" "$tmp/java.args" || fail "missing forwarded --debug"
grep -qx -- "--data-dir=/tmp/x" "$tmp/java.args" || fail "missing forwarded args"
grep -q "klardrop.jar" "$tmp/java.args" || fail "classpath missing jar"
grep -qx -- "$SCRIPT_REAL" "$tmp/java.env-launcher" \
  || fail "KLARDROP_LAUNCHER was not the script path"

# Property must name the script, never the fake java binary.
if grep -q "$tmp/bin/java" "$tmp/java.args"; then
  fail "-Dklardrop.launcher must not be the java binary"
fi

# --- JAVA_HOME wins over PATH ------------------------------------------------
mkdir -p "$tmp/home/bin"
cat > "$tmp/home/bin/java" <<EOF
#!/usr/bin/env bash
if [ "\${1:-}" = "-version" ]; then
  echo 'openjdk version "25.0.1" 2025-10-21' >&2
  exit 0
fi
echo "from-java-home" > "$tmp/which-java"
exit 0
EOF
chmod +x "$tmp/home/bin/java"
rm -f "$tmp/which-java"
PATH="$tmp/bin:$COREPATH" JAVA_HOME="$tmp/home" "$tmp/app/bin/klardrop" \
  >"$tmp/out" 2>"$tmp/err" || fail "JAVA_HOME java 25 should exec: $(cat "$tmp/err")"
[ -f "$tmp/which-java" ] || fail "expected JAVA_HOME/bin/java to be used"

printf 'ok\n'
