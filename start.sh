#!/bin/bash
set -e

exec java -Duser.timezone=Asia/Manila -XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError -jar "${PREMIER_JAR:-target/premier-0.0.1-SNAPSHOT.jar}"
