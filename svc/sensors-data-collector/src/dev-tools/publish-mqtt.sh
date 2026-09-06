#!/bin/bash

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <topic> '<json-message>'"
    exit 1
fi

TOPIC="$1"
MESSAGE="$2"

mosquitto_pub \
    -h localhost \
    -p 1883 \
    -t "$TOPIC" \
    -m "$MESSAGE"

