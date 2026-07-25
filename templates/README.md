# Deployment templates

Ready-made entries for self-hosting platforms. Each one points at the repository's root
`docker-compose.yml` instead of restating it, so a change to the stack can never leave a
template describing something that no longer exists.

## Portainer

Add this URL as an app template in Portainer (**Settings > App Templates > URL**):

```
https://raw.githubusercontent.com/livecontext-ai/livecontext-ce/main/templates/portainer/livecontext-ce.json
```

LiveContext CE then appears under **App Templates**, with the ports, database password
and optional LLM keys exposed as form fields. Deploying it pulls this repository and
brings the stack up.

## Any other platform (Coolify, Dokploy, CapRover, Komodo, plain Docker)

They all consume a Compose file directly, so there is no separate template to install:
point them at this repository's root `docker-compose.yml`.

Two things to get right, whatever the platform:

1. **Publish both ports.** The web UI is on `FRONTEND_PORT` (3000) and the browser talks
   to the backend on `BACKEND_PORT` (8080). Both must be reachable from the machine you
   browse from.
2. **Only if the backend is not at `<the address you open the app with>:BACKEND_PORT`** -
   typically a reverse proxy putting everything on one origin - set `GATEWAY_PUBLIC_URL`
   on the `frontend` service to the browser-facing backend URL, for example
   `https://livecontext.example.com`. Otherwise leave it empty: the app derives the
   backend origin from the address you opened it with, so LAN and domain installs work
   with no rebuild and no extra configuration.

Everything else (database, Redis, object storage) is self-contained in the Compose file
and persists in Docker volumes across updates.
