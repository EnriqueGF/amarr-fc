# amarr-fc architecture

Sonarr talks to this service twice:

1. The Torznab endpoint translates a TV search into one serialized aMule
   Kad/eD2k search and returns safe video results as Amarr magnet links.
2. The qBittorrent-compatible endpoint accepts that link, converts it back to
   eD2k, records category ownership in SQLite, and controls aMule over EC.

Jackett remains an independent source of torrent indexers. Sonarr combines
Jackett and amarr-fc results itself.

## Data paths

| Purpose | Host | aMule | amarr-fc | Sonarr |
|---|---|---|---|---|
| Complete | `/mnt/media/amuleCompleted` | `/incoming` | `/incoming` | `/data/amule/complete` |
| Incomplete | `/mnt/media/amuleDownloading` | `/temp` | not mounted | not mounted |
| State | `/srv/media/amarr-fc/config` | n/a | `/config` | n/a |

The qBittorrent API reports `/data/amule/complete`, which is the path visible
inside Sonarr. amarr-fc also mounts `/incoming` only so a Sonarr delete request
can remove a completed aMule file safely.

## Safety invariants

- Empty RSS-style searches never trigger a global Kad search.
- Only one network search runs at a time; identical searches use a TTL cache.
- Only video extensions with at least one source are returned.
- Only hashes registered by amarr-fc are visible through the download API.
- `delete all` cannot touch unrelated aMule shared files.
- EC and the aMule web UI are not published on the host network.
