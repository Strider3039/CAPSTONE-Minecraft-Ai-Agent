# Bridge release documentation (source files)

End-user READMEs that ship inside **`Minecraft_AI_Bridge_Release`** and **`Minecraft_AI_Bridge_Release_Linux`** are maintained as **tracked** files under **`packaging/dist/`** (exceptions in the repo root `.gitignore`):

| Platform | Source file (edit this) | Copied to release as |
|----------|-------------------------|----------------------|
| **Windows** | [`../dist/README_Windows.md`](../dist/README_Windows.md) | `Minecraft_AI_Bridge_Release/README.md` via `packaging/build_bridge_release.ps1` |
| **Linux** | [`../dist/README_Linux.md`](../dist/README_Linux.md) | `Minecraft_AI_Bridge_Release_Linux/README.md` via `packaging/build_bridge_release_linux.sh` |

Do not edit the generated **`README.md`** inside **`packaging/dist/Minecraft_AI_Bridge_*`** directly — it is overwritten on each build.
