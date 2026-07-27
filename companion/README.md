#!/usr/bin/env python3
# jetson-companion — stdlib-only HTTP bridge for the animal-counter countingapp.
#
# Runs on the Jetson HOST (systemd, NOT in k3s) and exposes a read-only API the
# Android app talks to: clock sync, live count heartbeat, sessions/history,
# video listing + range-stream, runtime-settings relay, and power-off.
# Talks to the countingapp ONLY via shared files in /data/orin/files
# (hostPath /files in the pod) — see IPC_CONTRACT.md.
#
# Deploy: ansible/playbooks/deploy_companion.yml (installs this script +
# the systemd unit + enables the service).