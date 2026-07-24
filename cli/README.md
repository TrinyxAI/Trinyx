# livecontext (CLI)

One-command launcher for **LiveContext Community Edition**. It wraps the Docker
stack behind a single npm command. Docker is the runtime; this CLI only
orchestrates it (it does not replace Docker).

## Usage

```bash
npx livecontext          # start (default): pulls images and boots the stack
npx livecontext down     # stop and remove the containers
npx livecontext logs     # follow the logs
npx livecontext status   # container status
npx livecontext update   # pull the latest images and restart
```

Then open **http://localhost:3000** and create the first account (it becomes the
admin). On the first run the CLI fetches the current model catalog so a fresh,
never-cloud-linked install ships with up-to-date models.

## Requirements

- Docker Engine 24+ with Compose v2 (or Docker Desktop 4.x and later)
- 4 GB RAM minimum, 8 GB recommended

Data lives in `./livecontext` next to where you run the command (remove it to
reset). Optional configuration (LLM keys, SMTP, ports) is documented in the main
project README; copy `./livecontext/.env.example` to `.env` and re-run.

Licensed under AGPL-3.0. Part of https://github.com/livecontext-ai/livecontext-ce
