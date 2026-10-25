#!/usr/bin/env bash
# M1 acceptance test on THIS machine, using dummy servers (test-support/DummyServer.java) instead of the real ones.
# Usage: scripts/m1-accept.sh [s0] [s1] [s2] [s3] [s4]      (no argument = all)
#   s0 config validation + --hash-password     s1 routing, ordering, "stop" command
#   s2 SIGTERM to the root                   s3 children that ignore stop -> SIGKILL path
#   s4 crash + restart backoff
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERVER_JAR=${SERVER_JAR:-$ROOT/build/server.jar}
DUMMY_JAR=${DUMMY_JAR:-$ROOT/build/dummy.jar}
WORK=$(mktemp -d /tmp/m1-accept.XXXXXX)
PASS=0; FAIL=0
HASH=$(echo "testpassword1" | java -jar "$SERVER_JAR" --hash-password)

ok()    { echo "    PASS  $1"; PASS=$((PASS+1)); }
bad()   { echo "    FAIL  $1"; FAIL=$((FAIL+1)); }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

cleanup() {   # never leave dummy servers behind: kill only the pids the dummies printed
  for p in $(grep -ho 'pid=[0-9]*' "$WORK"/*/out.log 2>/dev/null | cut -d= -f2); do kill -9 "$p" 2>/dev/null; done
  [ -n "${MPID:-}" ] && kill -9 "$MPID" 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT

# mkenv name nanoMode veloMode nanoTimeout veloTimeout backoff nanoStrategy veloStrategy
mkenv() {
  D=$WORK/$1; LOG=$D/out.log; mkdir -p "$D/nanolimbo" "$D/velocity"
  cp "$DUMMY_JAR" "$D/nanolimbo/NanoLimbo.jar"; cp "$DUMMY_JAR" "$D/velocity/velocity.jar"
  echo "$2" > "$D/nanolimbo/mode.txt"; echo "$3" > "$D/velocity/mode.txt"
  cat > "$D/config.yml" <<YML
server:
  bind: 0.0.0.0
  port: 25568
  password_hash: "$HASH"
children:
  - id: nanolimbo
    type: nanolimbo
    dir: ./nanolimbo
    jar: NanoLimbo.jar
    heap: 256M
    port: 25566
    bind: 127.0.0.1
    stop_strategy: $7
    stop_command: "stop"
    stop_timeout_seconds: $4
    start_order: 1
    stop_order: 2
    restart_backoff_seconds: $6
    console_enabled: false
  - id: velocity
    type: velocity
    dir: ./velocity
    jar: velocity.jar
    heap: 256M
    port: 25565
    bind: 0.0.0.0
    stop_strategy: $8
    stop_command: "shutdown"
    stop_timeout_seconds: $5
    start_order: 2
    stop_order: 1
    restart_backoff_seconds: $6
    console_enabled: true
YML
}

launch() {   # start the root with a FIFO as its stdin (what Pterodactyl does with the console)
  rm -f "$D/in"; mkfifo "$D/in"
  ( cd "$D" && exec java -Xmx128M -jar "$SERVER_JAR" < in > out.log 2>&1 ) &
  MPID=$!
  exec 3> "$D/in"
}
say() { echo "$1" >&3; }
now() { date +%s.%N; }
waitfor()  { local i=0; while ! grep -qE "$1" "$LOG" 2>/dev/null; do sleep 0.3; i=$((i+1)); [ $i -ge $(( ${2:-20} * 3 )) ] && return 1; done; return 0; }
waitcount(){ local i=0; while [ "$(grep -cE "$1" "$LOG" 2>/dev/null)" -lt "$2" ]; do sleep 0.3; i=$((i+1)); [ $i -ge $(( ${3:-20} * 3 )) ] && return 1; done; return 0; }
waitexit() { local i=0; while kill -0 "$MPID" 2>/dev/null; do sleep 0.2; i=$((i+1)); [ $i -ge $(( ${1:-40} * 5 )) ] && return 1; done; wait "$MPID"; RC=$?; MPID=; return 0; }
lineno()   { grep -nE "$1" "$2" | head -1 | cut -d: -f1; }
tstamp()   { grep -m1 "^\[$1\] started" "$LOG" | sed -E 's/.* t=([0-9]+).*/\1/'; }

# From the "shutdown requested" line on, children must go velocity first, then nanolimbo, each fully exited.
check_shutdown_order() {
  local n; n=$(lineno 'shutdown requested' "$LOG"); tail -n +"$n" "$LOG" > "$D/shutdown.log"
  local a b c d
  a=$(lineno '\[root\] stopping velocity' "$D/shutdown.log");   b=$(lineno '\[root\] velocity exited' "$D/shutdown.log")
  c=$(lineno '\[root\] stopping nanolimbo' "$D/shutdown.log");  d=$(lineno '\[root\] nanolimbo exited' "$D/shutdown.log")
  check "velocity is stopped before nanolimbo even starts stopping" "[ -n '$a' ] && [ -n '$b' ] && [ -n '$c' ] && [ -n '$d' ] && [ $a -lt $b ] && [ $b -lt $c ] && [ $c -lt $d ]"
  check "root logged 'all children stopped'" "grep -q 'all children stopped' '$D/shutdown.log'"
}

s0() {
  echo "== s0: config validation and --hash-password"
  check "--hash-password prints a pbkdf2 hash" "echo 'longenough1' | java -jar '$SERVER_JAR' --hash-password | grep -qF 'pbkdf2\$sha256\$210000\$'"
  check "--hash-password rejects a short password" "! echo 'short' | java -jar '$SERVER_JAR' --hash-password >/dev/null 2>&1"
  mkenv s0 obey obey 5 5 "[1]" both stdin
  sed -i 's/^  password_hash:.*/  password_hash: "placeholder"/' "$D/config.yml"
  (cd "$D" && java -jar "$SERVER_JAR" < /dev/null > out.log 2>&1); RC=$?
  check "placeholder password_hash: exit code 1 and clear message" "[ $RC -eq 1 ] && grep -q 'placeholder' '$LOG'"
  mkenv s0b obey obey 5 5 "[1]" both stdin
  sed -i '0,/bind: 127.0.0.1/s//bind: 0.0.0.0/' "$D/config.yml"
  (cd "$D" && java -jar "$SERVER_JAR" < /dev/null > out.log 2>&1); RC=$?
  check "NanoLimbo on 0.0.0.0: exit code 1 and mentions loopback" "[ $RC -eq 1 ] && grep -q 'loopback' '$LOG'"
  mkenv s0c obey obey 5 5 "[1]" both stdin
  sed -i 's/^    port: 25565/    port: 25568/' "$D/config.yml"
  (cd "$D" && java -jar "$SERVER_JAR" < /dev/null > out.log 2>&1); RC=$?
  check "panel port equal to a child port: exit code 1" "[ $RC -eq 1 ] && grep -q 'collides with the web panel' '$LOG'"
  mkenv s0d obey obey 5 5 "[1]" both stdin; rm -rf "$D/velocity"
  (cd "$D" && java -jar "$SERVER_JAR" < /dev/null > out.log 2>&1); RC=$?
  check "missing child directory: exit code 1" "[ $RC -eq 1 ] && grep -q 'does not exist' '$LOG'"
}

s1() {
  echo "== s1: prefixes, routing, start order, lifecycle commands, 'stop' command"
  mkenv s1 "obey stop" "obey shutdown" 15 30 "[5, 10, 30, 60, 300]" both stdin; launch
  waitfor '\[velocity\] started' 30 || bad "velocity never started"
  check "child output is prefixed [nanolimbo] and [velocity]" "grep -q '^\[nanolimbo\] started' '$LOG' && grep -q '^\[velocity\] started' '$LOG'"
  local tn tv; tn=$(tstamp nanolimbo); tv=$(tstamp velocity)
  check "nanolimbo started first, then velocity at least 5 s later" "[ -n '$tn' ] && [ -n '$tv' ] && [ $(( tv - tn )) -ge 4900 ]"
  say "velocity plist";            check "'velocity plist' reaches velocity"            "waitfor '^\[velocity\] plist: ok' 5"
  say "nanolimbo hello";           check "'nanolimbo hello' reaches nanolimbo"           "waitfor '^\[nanolimbo\] echo: hello' 5"
  say "say hi there";              check "unprefixed line goes to the first console-enabled child (velocity)" "waitfor '^\[velocity\] echo: say hi there' 5"
  check "...and not to nanolimbo (console_enabled: false)" "! grep -q '^\[nanolimbo\] echo: say hi there' '$LOG'"
  sleep 5; say status
  check "status shows both running" "waitfor '\[root\] velocity: running' 5 && waitfor '\[root\] nanolimbo: running' 5"
  say "velocity.restart"
  check "velocity.restart starts velocity a second time" "waitcount '^\[velocity\] started' 2 40"
  say "nanolimbo.stop";  check "nanolimbo.stop stops only nanolimbo" "waitfor '\[root\] nanolimbo exited with code 0' 20"
  check "...and velocity keeps running" "grep -c 'velocity exited' '$LOG' | grep -q '^1\$'"
  say "nanolimbo.start"; check "nanolimbo.start brings it back" "waitcount '^\[nanolimbo\] started' 2 20"
  sleep 1
  local t0 t1; t0=$(now); say stop
  waitexit 60; check "'stop' command: root exits" "[ -z '${MPID:-}' ]"
  t1=$(now); echo "    (shutdown took $(echo "$t1 - $t0" | bc) s)"
  check "root exit code is 0" "[ ${RC:-99} -eq 0 ]"
  check_shutdown_order
  check "children received their stop commands on stdin" "grep -q '^\[velocity\] stop command received' '$LOG' && grep -q '^\[nanolimbo\] stop command received' '$LOG'"
  exec 3>&-
}

s2() {
  echo "== s2: SIGTERM to the root (what Wings sends when the panel Stop button uses a signal)"
  mkenv s2 "obey stop" "obey shutdown" 15 30 "[5, 10, 30, 60, 300]" both stdin; launch
  waitfor '\[velocity\] started' 30 || bad "velocity never started"; sleep 1
  local t0 t1; t0=$(now); kill -TERM "$MPID"
  waitexit 60; check "root exits after SIGTERM" "[ -z '${MPID:-}' ]"
  t1=$(now); echo "    (shutdown took $(echo "$t1 - $t0" | bc) s)"
  check "root exit code is 0 (not 143)" "[ ${RC:-99} -eq 0 ]"
  check_shutdown_order
  check "children were stopped via stdin, not killed" "grep -q '^\[velocity\] stop command received' '$LOG' && ! grep -q 'SIGKILL' '$LOG'"
  exec 3>&-
}

s3() {
  echo "== s3: children that ignore stop and SIGTERM -> escalation to SIGKILL within the timeouts"
  mkenv s3 stubborn stubborn 4 4 "[5]" both both; launch
  waitfor '\[velocity\] started' 30 || bad "velocity never started"; sleep 1
  local t0 t1; t0=$(now); say stop
  waitexit 60; check "root still exits" "[ -z '${MPID:-}' ]"
  t1=$(now); local secs; secs=$(echo "$t1 - $t0" | bc); echo "    (shutdown took $secs s; 2 children x 4 s timeout)"
  check "root exit code is 0" "[ ${RC:-99} -eq 0 ]"
  check "shutdown finished in under 20 s" "[ $(echo "$secs < 20" | bc) -eq 1 ]"
  check "SIGTERM was tried, then SIGKILL, for velocity" "grep -q 'velocity still running after 2.0s, sending SIGTERM' '$LOG' && grep -q 'velocity did not exit within 4s, sending SIGKILL' '$LOG'"
  check "SIGTERM was tried, then SIGKILL, for nanolimbo" "grep -q 'nanolimbo still running after 2.0s, sending SIGTERM' '$LOG' && grep -q 'nanolimbo did not exit within 4s, sending SIGKILL' '$LOG'"
  check_shutdown_order
  local pv pn; pv=$(grep -m1 '^\[velocity\] started' "$LOG" | sed -E 's/.*pid=([0-9]+).*/\1/'); pn=$(grep -m1 '^\[nanolimbo\] started' "$LOG" | sed -E 's/.*pid=([0-9]+).*/\1/')
  check "no child process is left running" "! kill -0 $pv 2>/dev/null && ! kill -0 $pn 2>/dev/null"
  exec 3>&-
}

s4() {
  echo "== s4: crash -> restart with escalating backoff (1,2,3,3 s), no restart after a deliberate stop"
  mkenv s4 "obey stop" crash 5 5 "[1, 2, 3]" both stdin; launch
  waitcount 'restarting in' 4 60 || bad "did not see 4 restarts"
  local delays; delays=$(grep -o 'restarting in [0-9]*s' "$LOG" | head -4 | sed -E 's/[^0-9]+([0-9]+)s/\1/' | tr '\n' ' ')
  check "delays escalate 1, 2, 3 and cap at 3 (got: $delays)" "[ '$delays' = '1 2 3 3 ' ]"
  check "nanolimbo is unaffected by velocity crashing" "! grep -q 'nanolimbo exited' '$LOG'"
  say "velocity.stop"; sleep 1; local before; before=$(grep -c '^\[root\] starting velocity' "$LOG")
  sleep 6
  check "velocity.stop cancels the pending restart (no new start after 6 s)" "[ \$(grep -c '^\[root\] starting velocity' '$LOG') -eq $before ]"
  say status; check "status reports velocity stopped" "waitfor '\[root\] velocity: stopped' 5"
  say stop; waitexit 60; check "root exits 0 afterwards" "[ ${RC:-99} -eq 0 ]"
  exec 3>&-
}

SCEN=("$@"); [ ${#SCEN[@]} -eq 0 ] && SCEN=(s0 s1 s2 s3 s4)
for s in "${SCEN[@]}"; do "$s"; done
echo; echo "acceptance: $PASS passed, $FAIL failed"
[ $FAIL -eq 0 ]
