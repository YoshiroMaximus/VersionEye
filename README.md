<img alt="Supported on Paper" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3.3.1/assets/cozy/supported/paper_vector.svg" height="56">
<img alt="Built with Java 25" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3.3.1/assets/cozy/built-with/java25_vector.svg" height="56">

A Paper plugin that checks your installed plugins for updates on
[Modrinth](https://modrinth.com). Updates are reported in the console, to
ops when they join, and on demand with `/updatecheck`. All checks run off
the main thread.

Plugins are identified by the sha512 hash of their jar, so they are matched
to their exact Modrinth project and release with no name guessing. Jars
Modrinth doesn't know fall back to name matching. Alpha and beta builds are
ignored unless enabled.

## Commands and permissions

| Command | Permission | Default |
|---|---|---|
| `/updatecheck` | `versioneye.check` | op |
| Join notifications | `versioneye.notify` | op |

## Config

| Option | Default | What it does |
|---|---|---|
| `check-interval-hours` | `6` | How often to re-check (`0` = startup only) |
| `notify-on-join` | `true` | Notify permitted players on join |
| `require-matching-game-version` | `false` | Only count releases tagged for your MC version |
| `include-prereleases` | `false` | Also count alpha/beta uploads as updates |
| `overrides` | `{}` | Pin a plugin to an exact Modrinth slug |
| `exclude` | `[VersionEye]` | Plugins to skip |

If a plugin shows as not found, it either isn't on Modrinth or its jar came
from somewhere else. Pin it to the right project, or exclude it:

```yaml
overrides:
  Essentials: essentialsx
exclude:
  - SomePremiumPlugin
```
