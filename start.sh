#!/bin/bash
set -e

java -Duser.timezone=Asia/Manila -Xmx256m -jar target/premier-0.0.1-SNAPSHOT.jar
