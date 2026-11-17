#!/usr/bin/env bash
echo "=== docker context ls ==="
docker context ls 2>&1 | head -5
echo "=== DOCKER_HOST ==="
echo "DOCKER_HOST=[${DOCKER_HOST}]"
echo "=== docker endpoint ==="
docker context inspect --format "{{.Endpoints.docker.Host}}" 2>&1 | head -2
echo "=== docker info ==="
docker info --format "{{.ServerVersion}} | {{.OSType}}" 2>&1 | head -3
echo "=== npipe test ==="
ls -la "//./pipe/docker_engine" 2>&1 | head -2
echo "=== DOCKER_CERT_PATH ==="
echo "[${DOCKER_CERT_PATH}]"