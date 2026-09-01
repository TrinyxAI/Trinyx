#!/usr/bin/env python3
from __future__ import annotations

import argparse
import ipaddress
import re
from pathlib import Path
from urllib.parse import urlparse

KEY_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")
HOST_RE = re.compile(r"^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$")
KID_RE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")

REQUIRED = {
    "TRINYX_ENVIRONMENT",
    "AWS_REGION",
    "CLOUD_DB_USERNAME",
    "CLOUD_PUBLIC_URL",
    "KEYCLOAK_PUBLIC_URL",
    "PAID_PUBLIC_URL",
    "PAID_INTERNAL_HOST",
    "CLOUD_INTERNAL_HOST",
    "BILLING_PROVIDER",
    "TRINYX_IDENTITY_SIGNING_KID",
    "TRINYX_ENTITLEMENT_SIGNING_KID",
    "TRINYX_S2S_SIGNING_KID",
    "TRINYX_S2S_SIGNING_ISSUER",
    "TRINYX_S2S_SIGNING_AUDIENCE",
    "TRINYX_S2S_VERIFICATION_ISSUER",
    "TRINYX_S2S_VERIFICATION_AUDIENCE",
}

OPTIONAL = {
    "CLOUD_PRIVATE_IP",
    "PAID_PRIVATE_IP",
    "PAID_CADDYFILE_PATH",
}

FORBIDDEN_NAME_PARTS = (
    "PASSWORD",
    "SECRET",
    "TOKEN",
    "PRIVATE_KEY",
    "SIGNING_KEY",
    "ENCRYPTION_KEY",
    "ACCESS_KEY",
    "API_KEY",
)


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"invalid env line {number}")
        name, value = line.split("=", 1)
        if not KEY_RE.fullmatch(name):
            fail(f"invalid key name on line {number}: {name}")
        if name in values:
            fail(f"duplicate key: {name}")
        if any(part in name for part in FORBIDDEN_NAME_PARTS):
            # Public verification/config KIDs are explicitly allowed, but literal
            # secret/key material must never enter this non-secret environment file.
            if not name.endswith("_KID"):
                fail(f"secret-bearing key name is forbidden in environment inventory: {name}")
        values[name] = value

    missing = sorted(REQUIRED - values.keys())
    extra = sorted(values.keys() - REQUIRED - OPTIONAL)
    if missing:
        fail("missing keys: " + ",".join(missing))
    if extra:
        fail("unknown keys: " + ",".join(extra))
    return values


def validate_https(name: str, value: str) -> None:
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        fail(f"{name} must be an HTTPS URL without credentials")
    if parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        fail(f"{name} must be an origin URL")


def validate_host(name: str, value: str) -> None:
    if len(value) > 253 or not HOST_RE.fullmatch(value) or ".." in value:
        fail(f"invalid host: {name}")


def validate_ip(name: str, value: str) -> None:
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        fail(f"invalid IPv4 address: {name}")
    if address.version != 4 or not address.is_private:
        fail(f"{name} must be a private IPv4 address")


def validate(values: dict[str, str]) -> None:
    env = values["TRINYX_ENVIRONMENT"]
    if env not in {"staging", "production"}:
        fail("TRINYX_ENVIRONMENT must be staging or production")

    if not re.fullmatch(r"[a-z]{2}-[a-z]+-\d", values["AWS_REGION"]):
        fail("invalid AWS_REGION")

    if not re.fullmatch(r"[a-z_][a-z0-9_]{0,62}", values["CLOUD_DB_USERNAME"]):
        fail("invalid CLOUD_DB_USERNAME")

    for key in ("CLOUD_PUBLIC_URL", "KEYCLOAK_PUBLIC_URL", "PAID_PUBLIC_URL"):
        validate_https(key, values[key])

    for key in ("PAID_INTERNAL_HOST", "CLOUD_INTERNAL_HOST"):
        validate_host(key, values[key])

    if values["BILLING_PROVIDER"] not in {"stripe"}:
        fail("unsupported BILLING_PROVIDER")

    for key in (
        "TRINYX_IDENTITY_SIGNING_KID",
        "TRINYX_ENTITLEMENT_SIGNING_KID",
        "TRINYX_S2S_SIGNING_KID",
    ):
        if not KID_RE.fullmatch(values[key]):
            fail(f"invalid {key}")

    for key in ("CLOUD_PRIVATE_IP", "PAID_PRIVATE_IP"):
        if values.get(key):
            validate_ip(key, values[key])

    caddy = values.get("PAID_CADDYFILE_PATH")
    if caddy:
        if not caddy.startswith("/srv/trinyx/") or ".." in Path(caddy).parts:
            fail("PAID_CADDYFILE_PATH must be an absolute /srv/trinyx path")

    # Prevent accidental reuse of staging endpoints in production inventory.
    if env == "production":
        for key in ("CLOUD_PUBLIC_URL", "KEYCLOAK_PUBLIC_URL", "PAID_PUBLIC_URL"):
            if "staging" in values[key].lower():
                fail(f"production inventory contains staging URL in {key}")


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def render(values: dict[str, str], out: Path) -> None:
    env = values["TRINYX_ENVIRONMENT"]
    cloud_base = f"/etc/trinyx/{env}/cloud"
    paid_base = f"/etc/trinyx/{env}/paid"

    write(
        out / "cloud/runtime-static.env",
        "\n".join(
            [
                f"CLOUD_DB_USERNAME={values['CLOUD_DB_USERNAME']}",
                f"CLOUD_PUBLIC_URL={values['CLOUD_PUBLIC_URL']}",
                f"KEYCLOAK_PUBLIC_URL={values['KEYCLOAK_PUBLIC_URL']}",
                f"PAID_PUBLIC_URL={values['PAID_PUBLIC_URL']}",
                "",
            ]
        ),
    )

    if values.get("PAID_PRIVATE_IP"):
        write(
            out / "cloud/cloud-paid.override.yml",
            "services:\n"
            "  auth-service:\n"
            "    extra_hosts:\n"
            f"      - \"{values['PAID_INTERNAL_HOST']}:{values['PAID_PRIVATE_IP']}\"\n",
        )

    paid_lines = [
        "services:",
        "  livecontext:",
        "    environment:",
        "      APP_EDITION: paid-monolith",
        "      DEPLOYMENT_MODE: monolith",
        "      AUTH_MODE: embedded",
        "      MARKETPLACE_MODE: local",
        f"      BILLING_PROVIDER: {values['BILLING_PROVIDER']}",
        "      BILLING_AUTHORITY_MODE: paid-monolith-authority",
        "      CLOUD_LINK_ENABLED: \"true\"",
        "      CREDIT_UNLIMITED: \"false\"",
        "      CREDIT_CONSUMPTION_ENABLED: \"true\"",
        "      PLAN_LIMITS_ENABLED: \"true\"",
        "      WORKFLOW_NODE_BILLING_ENABLED: \"true\"",
        f"      CLOUD_PUBLIC_URL: {values['CLOUD_PUBLIC_URL']}",
        f"      KEYCLOAK_PUBLIC_URL: {values['KEYCLOAK_PUBLIC_URL']}",
        f"      PAID_PUBLIC_URL: {values['PAID_PUBLIC_URL']}",
        f"      APP_PUBLIC_URL: {values['PAID_PUBLIC_URL']}",
        f"      MARKETPLACE_CLOUD_API_URL: {values['CLOUD_PUBLIC_URL']}/api",
        f"      CATALOG_BUNDLE_CLOUD_URL: {values['CLOUD_PUBLIC_URL']}",
        f"      API_CATALOG_BUNDLE_CLOUD_URL: {values['CLOUD_PUBLIC_URL']}",
        f"      SKILL_BUNDLE_CLOUD_URL: {values['CLOUD_PUBLIC_URL']}",
        f"      CLOUD_KEYCLOAK_URL: {values['KEYCLOAK_PUBLIC_URL']}/realms/trinyx",
        f"      CLOUD_LINK_REDIRECT_URI: {values['PAID_PUBLIC_URL']}/api/cloud-link/callback",
        f"      TRINYX_IDENTITY_ISSUER: {values['PAID_PUBLIC_URL']}",
        f"      TRINYX_IDENTITY_SIGNING_KID: {values['TRINYX_IDENTITY_SIGNING_KID']}",
        f"      TRINYX_ENTITLEMENT_ISSUER: {values['PAID_PUBLIC_URL']}",
        f"      TRINYX_ENTITLEMENT_SIGNING_KID: {values['TRINYX_ENTITLEMENT_SIGNING_KID']}",
        f"      TRINYX_S2S_SIGNING_ISSUER: {values['TRINYX_S2S_SIGNING_ISSUER']}",
        f"      TRINYX_S2S_SIGNING_AUDIENCE: {values['TRINYX_S2S_SIGNING_AUDIENCE']}",
        f"      TRINYX_S2S_SIGNING_KID: {values['TRINYX_S2S_SIGNING_KID']}",
        f"      TRINYX_S2S_VERIFICATION_ISSUER: {values['TRINYX_S2S_VERIFICATION_ISSUER']}",
        f"      TRINYX_S2S_VERIFICATION_AUDIENCE: {values['TRINYX_S2S_VERIFICATION_AUDIENCE']}",
        f"      TRINYX_ENTITLEMENT_CLOUD_INGEST_URL: https://{values['CLOUD_INTERNAL_HOST']}:8443/internal/v1/entitlement-projections",
        f"      TRINYX_IDENTITY_CLOUD_REVOCATION_URL: https://{values['CLOUD_INTERNAL_HOST']}:8443/internal/v1/identity-bindings/revocations",
        f"      OAUTH2_FRONTEND_URL: {values['PAID_PUBLIC_URL']}",
        f"      OAUTH2_CALLBACK_URL: {values['PAID_PUBLIC_URL']}/api/credentials/oauth2/callback",
        "  frontend:",
        "    environment:",
        f"      GATEWAY_PUBLIC_URL: {values['PAID_PUBLIC_URL']}",
        "      BACKEND_PORT: \"8080\"",
        "  paid-edge:",
        "    image: caddy:2.11.4-alpine",
        "    container_name: trinyx-paid-private-edge",
        "    restart: unless-stopped",
        "    network_mode: service:livecontext",
        "    depends_on:",
        "      livecontext:",
        "        condition: service_healthy",
        "    environment:",
        f"      PAID_MONOLITH_INTERNAL_HOST: {values['PAID_INTERNAL_HOST']}",
        "      PAID_MONOLITH_INTERNAL_PORT: \"8443\"",
        "      PAID_MONOLITH_INTERNAL_CERT_FILE: /run/tls/billing-internal.crt",
        "      PAID_MONOLITH_INTERNAL_KEY_FILE: /run/tls/billing-internal.key",
        "    volumes:",
    ]

    if values.get("PAID_CADDYFILE_PATH"):
        paid_lines.append(
            f"      - {values['PAID_CADDYFILE_PATH']}:/etc/caddy/Caddyfile:ro"
        )
    paid_lines.extend(
        [
            f"      - /etc/trinyx/tls/billing-internal.crt:/run/tls/billing-internal.crt:ro",
            f"      - /etc/trinyx/tls/billing-internal.key:/run/tls/billing-internal.key:ro",
            "",
        ]
    )
    write(out / "paid/paid.override.yml", "\n".join(paid_lines))

    if values.get("PAID_PRIVATE_IP"):
        write(
            out / "paid/paid-bind.override.yml",
            "services:\n"
            "  livecontext:\n"
            "    ports: !override\n"
            "      - \"127.0.0.1:8080:8080\"\n"
            f"      - \"{values['PAID_PRIVATE_IP']}:8443:8443\"\n"
            "  frontend:\n"
            "    ports: !override\n"
            f"      - \"{values['PAID_PRIVATE_IP']}:3000:3000\"\n",
        )

    write(
        out / "paid/paid-runtime.override.yml",
        "services:\n"
        "  livecontext:\n"
        "    environment:\n"
        "      MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: \"true\"\n"
        "    healthcheck:\n"
        "      test: [\"CMD\", \"wget\", \"-qO-\", \"http://localhost:8080/actuator/health/liveness\"]\n"
        "      interval: 30s\n"
        "      timeout: 5s\n"
        "      retries: 5\n"
        "      start_period: 120s\n"
        "    deploy:\n"
        "      resources:\n"
        "        limits:\n"
        "          memory: 3G\n",
    )

    write(
        out / "metadata.env",
        "\n".join(
            [
                f"TRINYX_ENVIRONMENT={env}",
                f"AWS_REGION={values['AWS_REGION']}",
                f"CLOUD_BASE={cloud_base}",
                f"PAID_BASE={paid_base}",
                f"CLOUD_SSM_PATH=/trinyx/{env}/cloud/",
                f"PAID_SSM_PATH=/trinyx/{env}/paid/",
                "",
            ]
        ),
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    values = load_env(args.inventory)
    validate(values)
    args.out.mkdir(parents=True, exist_ok=True)
    render(values, args.out)
    print(f"TRINYX_ENVIRONMENT_RENDER_OK environment={values['TRINYX_ENVIRONMENT']}")


if __name__ == "__main__":
    main()
