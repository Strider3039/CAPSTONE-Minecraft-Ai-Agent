# AI Agent Project

An end-to-end prototype of a Minecraft player-style AI agent. The MVP uses a headless bot client (logs in as a real player) plus a Python AI service over a lightweight bridge. Optional server-side pieces (plugin + datapack) make training/evals cleaner on servers you control.

## Goals

Control a real player (not an NPC) in SP/MP.

Start simple (navigation/interaction), then expand.

Keep the interfaces stable so we can later swap the client layer (e.g., move to a Fabric/Forge client + Baritone) without retraining.
