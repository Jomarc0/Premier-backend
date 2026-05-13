#!/bin/bash

mkdir -p /etc/secrets
echo "$DIALOGFLOW_CREDENTIALS_JSON" > /etc/secrets/dialogflow-service-account.json
echo "$FIREBASE_CREDENTIALS_JSON"   > /etc/secrets/firebase-service-account.json

java -Xmx256m -jar target/premier-0.0.1-SNAPSHOT.jar