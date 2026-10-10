# source me: build env for the backend + test database
export GRADLE_USER_HOME=/home/claude/gradle-home GRADLE_RO_DEP_CACHE=/home/claude/gradle-ro
export TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/matchdayclub_test TEST_DATABASE_USER=claude TEST_DATABASE_PASSWORD=claude
pg_lsclusters 2>/dev/null | grep -q online || pg_ctlcluster 16 main start >/dev/null 2>&1
gw() { (cd /home/claude/work/backend && timeout 1500 ./gradlew --offline --console=plain "$@" 2>&1 | grep -v "^Picked up" | grep -vE "^\s+at (org\.junit|java\.base|org\.gradle|jdk\.|kotlin\.coroutines|kotlinx\.coroutines|io\.ktor|app//org\.junit|app//kotlin|app//io\.ktor|app//kotlinx)"); }
