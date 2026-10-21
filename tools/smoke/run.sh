#!/usr/bin/env bash
# Smoke test: dev server + real client on a virtual display, screenshots and logs in out/.
set -u
mkdir -p out run run-server/world/datapacks
echo "eula=true" > run-server/eula.txt
cat > run-server/server.properties <<P
online-mode=false
level-type=minecraft\:flat
spawn-protection=0
gamemode=creative
spawn-monsters=false
spawn-animals=false
view-distance=4
enable-rcon=true
rcon.port=25575
rcon.password=smoke
P
cp -r tools/smoke/datapack run-server/world/datapacks/smoke
cat > run/options.txt <<O
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
tutorialStep:none
renderDistance:4
guiScale:2
pauseOnLostFocus:false
narrator:0
soundCategory_master:0.0
O

gradle runServer > out/server-console.log 2>&1 &
for i in $(seq 1 120); do grep -q "Done (" run-server/logs/latest.log 2>/dev/null && break; sleep 2; done
echo "server: $(grep -c 'Done (' run-server/logs/latest.log 2>/dev/null) ready after $i tries"

Xvfb :99 -screen 0 1280x720x24 > /dev/null 2>&1 &
export DISPLAY=:99
sleep 2
REPO=-Pnightly_repo=BestDankEver/vmcomputers-86box
shot() { import -display :99 -window root "out/$1.png" 2>/dev/null || true; }
# 1) Plain start: the mod's setup screen should come up over the title screen.
gradle runClient $REPO > out/client-console-title.log 2>&1 &
sleep 60; shot s1-title-setup
xdotool key Tab; sleep 1; shot s2-setup-tab
pkill -f "fabric.dli.env=client"; sleep 8
cp run/logs/latest.log out/client-title.log 2>/dev/null
# 2) Straight into the server.
gradle runClient $REPO --args="--quickPlayMultiplayer localhost:25565" > out/client-console.log 2>&1 &
# GUI pixel (gui scale 2, 854x480 window at 213,120 on the 1280x720 display)
click() { xdotool mousemove $((213 + $1 * 2)) $((120 + $2 * 2)); sleep 0.3; xdotool click 1; sleep 2; }
key() { xdotool key "$@"; sleep 1.5; }
for t in 40 60 80; do sleep 20; shot "t$t"; done
rcon() { python3 tools/smoke/rcon.py "$@"; sleep 1; }
hold() { xdotool keydown "$1"; sleep 0.4; xdotool keyup "$1"; sleep 1.5; }
key 9; shot a-world-empty-hand
# Place a PC case on the ground ahead (looking down), then open it.
key 3
rcon tp @p 0.5 -60 0.5 0 60
xdotool click 3; sleep 2; shot b-placed
xdotool click 3; sleep 3; shot c-pc-screen
click 210 221; sleep 2; shot c2-open-case
key Escape; shot d-after-escape
rcon tp @p 0.5 -60 0.5 0 15
key e; shot e-inventory
key Escape
# Tablet: connect to the store, then open the parts catalog.
key 1; hold Return; sleep 3; shot f-tablet-enter
hold Return; sleep 4; shot g-tablet-enter2
hold Return; sleep 3; shot h-tablet-enter3
xdotool type --delay 80 voodoo; sleep 2; shot i-catalog-voodoo
click 350 70; sleep 2; shot j-voodoo-setup
key Escape; key Escape
sleep 5
cp run/logs/latest.log out/client.log 2>/dev/null
cp run-server/logs/latest.log out/server.log 2>/dev/null
grep -h -i "mixin\|exception\|error\|mcvmcomputers\|VM Computers" out/client.log out/server.log | head -300 > out/highlights.txt
ls -la out
