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

## Optional add-ons need the repository, not npx

Two heavy features are opt-in: interface screenshots/PDFs (`renderer`) and the
browser agent with web search (`browser-agent`). Each is enabled by an env file
that turns on a Docker profile and the matching app setting together. Those env
files ship with the git repository and this CLI passes no `--env-file`, so
**neither add-on can be enabled through `npx`**. To use them, clone
[the repository](https://github.com/livecontext-ai/livecontext-ce) and run
`docker compose --env-file docker/.env.ce.renderer up -d` directly. Everything
else works identically either way.

## Requirements

- Docker Engine 24+ with Compose v2 (or Docker Desktop 4.x and later)
- 4 GB RAM minimum, 8 GB recommended

Data lives in `./livecontext` next to where you run the command (remove it to
reset). Optional configuration (LLM keys, SMTP, ports) is documented in the main
project README; copy `./livecontext/.env.example` to `.env` and re-run.

Licensed under AGPL-3.0. Part of https://github.com/livecontext-ai/livecontext-ce
