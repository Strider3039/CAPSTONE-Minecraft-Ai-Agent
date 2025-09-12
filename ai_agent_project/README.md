# AI Agent Project

An end-to-end prototype of a Minecraft player-style AI agent. The MVP uses a headless bot client (logs in as a real player) plus a Python AI service over a lightweight bridge. Optional server-side pieces (plugin + datapack) make training/evals cleaner on servers you control.

## Goals

Control a real player (not an NPC) in SP/MP.

Start simple (navigation/interaction), then expand.

Keep the interfaces stable so we can later swap the client layer (e.g., move to a Fabric/Forge client + Baritone) without retraining.

## Folder Layout

```python
Project_Name/
├─ README.md
├─ CONTRIBUTING.md
├─ .gitignore
├─ LICENSE
│
├─ docs/
│  ├─ MoM/
│  ├─ Reports/
│  ├─ PresentationsVideos/
│  └─ ProjectManagement/
│
├─ resources/
│  ├─ ResearchPapers/
│  ├─ Links.md
│  └─ Tutorials.md
│
├─ code/
│  ├─ backend/
│  │  ├─ shared/                      # message contracts + shared config
│  │  │  ├─ schemas/                  # WS JSON now; proto/ later for gRPC
│  │  │  │  ├─ observation.schema.json
│  │  │  │  ├─ action.schema.json
│  │  │  │  └─ event.schema.json
│  │  │  └─ config/
│  │  │     ├─ default.yaml
│  │  │     └─ dev.yaml
│  │  │
│  │  ├─ ai/                          # Python policy + inference service
│  │  │  ├─ pyproject.toml
│  │  │  └─ src/
│  │  │     ├─ app/                   # WebSocket/HTTP server
│  │  │     ├─ policy/                # PPO/DQN
│  │  │     ├─ features/              # obs transforms
│  │  │     ├─ actions/               # action codecs, rate limits
│  │  │     └─ training/              # optional: train/eval scripts
│  │  │
│  │  ├─ bot/                         # headless real-player client
│  │  │  ├─ mineflayer/               # (Node/TS) MVP path
│  │  │  │  ├─ package.json
│  │  │  │  └─ src/
│  │  │  │     ├─ index.ts            # connects to MC + AI
│  │  │  │     ├─ bridge/             # WS/MsgPack client
│  │  │  │     ├─ obs/                # build Observation
│  │  │  │     └─ actions/            # apply low-level actions
│  │  │  │
│  │  │  ├─ forge/                    # forge player implementation (optional)
│  │  │  │
│  │  │  └─ mcprotocollib/            # (Java) alt bot (optional)
│  │  │     └─ src/main/java/...
│  │  │
│  │  ├─ server/                      # optional when you control the server
│  │  │  └─ paper-plugin/
│  │  │     └─ src/main/kotlin/...    # rewards/resets/metrics (NPC or scaffolding)
│  │  │
│  │  ├─ datapacks/                   # arenas/resets/rewards (support)
│  │  │  └─ ai_base/
│  │  │     └─ data/ai/functions/...
│  │  │
│  │  ├─ infra/                       # local orchestration
│  │  │  ├─ docker-compose.yml        # AI service, (optional) dashboards
│  │  │  └─ Dockerfile.ai
│  │  │
│  │  ├─ tests/                       # backend E2E/unit for AI/bot/plugin
│  │  └─ README.md
│  │
│  ├─ frontend/
│  │  ├─ admin-dashboard/             # optional: control/metrics UI
│  │  │  └─ README.md
│  │  ├─ tests/
│  │  └─ README.md
│  │
│  ├─ scripts/                        # dev helpers (run server/bot/ai)
│  └─ README.md
│
├─ tests/                             # black-box/E2E across services
│  └─ README.md
│
├─ data/                              # sample/mock + (gitignored) rollouts
│  ├─ raw/
│  ├─ processed/
│  └─ README.md
│
└─ .github/
   └─ ISSUE_TEMPLATE/
      ├─ bug_report.md
      └─ feature_request.md
```
