![VersionEye](https://i.imgur.com/ebpnaR1.png)

<p>
<img alt="Supported on Paper" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3.3.1/assets/cozy/supported/paper_vector.svg" height="48"> <img alt="Supported on Purpur" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3.3.1/assets/cozy/supported/purpur_vector.svg" height="48"> <img alt="Built with Java 25" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3.3.1/assets/cozy/built-with/java25_vector.svg" height="48"> <a href="https://modrinth.com/plugin/versioneye"><img alt="Available on Modrinth" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3.3.1/assets/cozy/available/modrinth_vector.svg" height="48"></a>
</p>

A Paper plugin that checks your installed plugins for updates on
[Modrinth](https://modrinth.com). Updates are reported in the console, to
ops when they join, and on demand with `/updatecheck`. All checks run off
the main thread. Works on Paper, Purpur, and Folia.

Plugins are identified by the sha512 hash of their jar, so they are matched
to their exact Modrinth project and release with no name guessing. Jars
Modrinth doesn't know fall back to name matching, and plugins that aren't
on Modrinth at all (like ProtocolLib) are looked up on
[Hangar](https://hangar.papermc.io) as a last resort. Alpha and beta
builds are ignored unless enabled.

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
| `check-hangar` | `true` | Fall back to Hangar for plugins not on Modrinth |
| `overrides` | `{}` | Pin a plugin to a Modrinth slug or `hangar:<slug>` |
| `exclude` | `[VersionEye]` | Plugins to skip |

If a plugin shows as not found or matches the wrong project, pin it to a
Modrinth slug or a Hangar project slug, or exclude it:

```yaml
overrides:
  Essentials: essentialsx
  ProtocolLib: hangar:ProtocolLib
exclude:
  - SomePremiumPlugin
```
