#!/usr/bin/env bash
set -euo pipefail
umask 077
install -d -o root -g root -m 0700 /run/trinyx
install -d -o root -g root -m 0700 /run/trinyx/auth-runtime
test -s /opt/trinyx/private-ca/paid-monolith-truststore.p12
test -s /etc/trinyx/secrets/paid-monolith-truststore-password
install -o root -g 1001 -m 0640 /opt/trinyx/private-ca/paid-monolith-truststore.p12 /run/trinyx/auth-runtime/paid-monolith-truststore.p12
install -o root -g 1001 -m 0640 /etc/trinyx/secrets/paid-monolith-truststore-password /run/trinyx/auth-runtime/paid-monolith-truststore-password
cmp -s /opt/trinyx/private-ca/paid-monolith-truststore.p12 /run/trinyx/auth-runtime/paid-monolith-truststore.p12
cmp -s /etc/trinyx/secrets/paid-monolith-truststore-password /run/trinyx/auth-runtime/paid-monolith-truststore-password
test "$(stat -c %u /run/trinyx/auth-runtime/paid-monolith-truststore.p12)" = 0
test "$(stat -c %g /run/trinyx/auth-runtime/paid-monolith-truststore.p12)" = 1001
test "$(stat -c %a /run/trinyx/auth-runtime/paid-monolith-truststore.p12)" = 640
test "$(stat -c %u /run/trinyx/auth-runtime/paid-monolith-truststore-password)" = 0
test "$(stat -c %g /run/trinyx/auth-runtime/paid-monolith-truststore-password)" = 1001
test "$(stat -c %a /run/trinyx/auth-runtime/paid-monolith-truststore-password)" = 640
