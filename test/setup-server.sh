#!/usr/bin/env bash
# E2E test sunucusu: Paper 1.20.1 + WorldEdit + WorldGuard + DuelSira
set -euo pipefail
JAR="$1"
rm -rf server && mkdir -p server/plugins/DuelSira
cd server

# --- Paper 1.20.1 (once yeni fill API, olmazsa eski v2 API)
URL=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/1.20.1/builds" 2>/dev/null \
  | jq -r '[.[] | select(.channel=="STABLE" or .channel=="stable")][0].downloads["server:default"].url // empty' || true)
if [ -z "${URL}" ]; then
  B=$(curl -fsSL "https://api.papermc.io/v2/projects/paper/versions/1.20.1/builds" | jq '.builds[-1].build')
  URL="https://api.papermc.io/v2/projects/paper/versions/1.20.1/builds/${B}/downloads/paper-1.20.1-${B}.jar"
fi
echo "Paper: $URL"
curl -fsSL -o paper.jar "$URL"

modrinth() {
  curl -fsSL "https://api.modrinth.com/v2/project/$1/version?game_versions=%5B%221.20.1%22%5D&loaders=%5B%22bukkit%22%5D" \
    | jq -r '[.[] | select(.version_type=="release")][0] | (.version_number + " " + ([.files[] | select(.primary==true)][0].url))'
}
read -r WEV WEU <<<"$(modrinth worldedit)"
read -r WGV WGU <<<"$(modrinth worldguard)"
echo "WorldEdit $WEV  $WEU"
echo "WorldGuard $WGV  $WGU"
curl -fsSL -o plugins/WorldEdit.jar "$WEU"
curl -fsSL -o plugins/WorldGuard.jar "$WGU"
cp "$JAR" plugins/DuelSira.jar

echo "eula=true" > eula.txt
cat > server.properties <<'P'
online-mode=false
enforce-secure-profile=false
level-type=minecraft\:flat
spawn-protection=0
difficulty=easy
generate-structures=false
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
view-distance=6
simulation-distance=6
max-players=10
motd=DuelSira E2E
P
# Testte sureler kisa
cat > plugins/DuelSira/config.yml <<'C'
settings:
  loot-time: 3
  end-delay: 2
  countdown: 3
  hub-command: ""
queue:
  response-time: 5
  click-cooldown-ms: 300
C
ls -la plugins
