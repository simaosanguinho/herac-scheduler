#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
GREEN='\033[0;32m'
NC='\033[0m' # No Color

cd "$DIR" || {
  echo "Redirection failed!"
  exit 1
}

echo -e "${GREEN}Building fake worker...${NC}"
if command -v mvn >/dev/null 2>&1; then
  mvn clean package
else
  rm -rf target
  mkdir -p target/classes
  mapfile -t sources < <(find src/main/java -name '*.java' -print)
  if [[ ${#sources[@]} -eq 0 ]]; then
    echo "No Java sources found."
    exit 1
  fi
  javac -d target/classes "${sources[@]}"
  jar --create --file target/fakeworker-1.0-SNAPSHOT.jar -C target/classes .
fi
echo -e "${GREEN}Building fake worker...done${NC}"
