# amarr-fc

Production-oriented aMule connector for Sonarr, based on
[vexdev/amarr](https://github.com/vexdev/amarr).

It exposes:

- a Torznab TV/movie indexer backed by Kad/eD2k searches;
- the subset of the qBittorrent Web API used by Sonarr and Radarr;
- virtual season packs assembled from individual eD2k episodes, with aggregate
  progress and restart-safe state;
- liveness and readiness health checks;
- persistent download/category ownership in SQLite.

The original project and this derivative are distributed under the MIT
license. See [LICENSE.md](LICENSE.md).

## Sonarr

Add a Torznab indexer:

```text
URL: http://amarr-fc:8080/indexer/amule/api
API key: AMARR_API_KEY
RSS: disabled
Automatic Search: initially disabled
Interactive Search: enabled
```

Add a qBittorrent download client:

```text
Host: amarr-fc
Port: 8080
Username: AMARR_QBIT_USERNAME
Password: AMARR_QBIT_PASSWORD
Category: sonarr
```

Sonarr must see the completed directory at `/data/amuleCompleted`.

Season searches can return an `Sxx PACK` result when at least two distinct
episodes are available. Grabbing it queues every available real episode file
in aMule and exposes them to Sonarr as one season directory; any gaps remain
monitored in Sonarr for later searches.

## Radarr

Use the same Torznab URL and API key, select the Movies categories, and add a
second qBittorrent download client pointing to `amarr-fc:8080` with category
`radarr`. Radarr must see the completed directory at `/data/amuleCompleted`.

## Development

```bash
./gradlew test
docker build -t amarr-fc:local .
```

Deployment files and configuration examples live in [`deploy/`](deploy/).
The complete runtime design is documented in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

For hosts behind a UPnP router, `deploy/amule-network-watchdog` keeps the
aMule TCP, server UDP and Kad UDP mappings alive and reconnects discovery
networks after transient failures. Install the accompanying systemd service
and timer on the Docker host.

Create a mode-0600 runtime environment without committing secrets:

```bash
cd deploy
AMULE_PASSWORD='existing EC password' ./install-env.sh .env
```
