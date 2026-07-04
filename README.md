# VersionEye

<img alt="Supported on Paper" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3.3.1/assets/cozy/supported/paper_vector.svg" height="56">
<img alt="Built with Java 25" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3.3.1/assets/cozy/built-with/java25_vector.svg" height="56">

Keeps an eye on your plugins so you don't have to. VersionEye scans every
installed plugin against [Modrinth](https://modrinth.com) and tells you when
something is outdated — in the console, in chat when ops join, and on demand
with a command. All checks run off the main thread, so there's zero impact
on server performance.

## ✨ Features

- 🔄 Automatic check on startup and every 6 hours (configurable)
- 🔔 Ops get a clickable update summary when they join
- ⌨️ `/updatecheck` — run a check anytime
- 🎯 Exact matching by jar file hash — knows precisely which release you're running
- ⚡ Plugins are checked in parallel, so even big servers finish in seconds
- 🧪 Ignores alpha/beta builds by default — only stable releases count

## 📦 Commands & Permissions

| Command | Permission | Default |
|---|---|---|
| `/updatecheck` | `versioneye.check` | op |
| Join notifications | `versioneye.notify` | op |

## ⚙️ Config

| Option | Default | What it does |
|---|---|---|
| `check-interval-hours` | `6` | How often to re-check (`0` = startup only) |
| `notify-on-join` | `true` | Notify permitted players on join |
| `require-matching-game-version` | `false` | Only count releases tagged for your MC version |
| `include-prereleases` | `false` | Also count alpha/beta uploads as updates |
| `overrides` | `{}` | Pin a plugin to an exact Modrinth slug |
| `exclude` | `[VersionEye]` | Plugins to skip |

Plugins are identified by the sha512 hash of their jar, so downloads from
Modrinth are matched to their exact project and release. Jars Modrinth
doesn't know (e.g. downloaded from GitHub) fall back to name matching —
if one shows as **not found**, pin it in the config:

```yaml
overrides:
  Essentials: essentialsx
```

Plugins that aren't on Modrinth at all? Add them to `exclude` and they'll
stay quiet.
