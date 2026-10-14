# Settlements

A Forge 1.20.1 mod about villages that live on their own: villagers with real professions, needs and
stamina, settlements that grow, trade, defend themselves and remember what the player did.

This project starts as a fork of **Minecraft Comes Alive Reborn** (branch `1.20.1`, commit `4d82455`) by
Luke100000, S0llace, CDAGaming, ffrannny, jahx_senpoopie, WildBamaBoy, SheWolfDeadly and contributors:
https://github.com/Luke100000/minecraft-comes-alive

It is licensed under the **GNU GPL v3** like the original (see `LICENSE`). Changes made here are described in
`CHANGES.md`.

## Status

Stage 0: the MCA code base, Forge only (Fabric and Quilt removed), builds in CI. Nothing new in gameplay yet.

## Plan

1. Settlement storage, professions (lumberjack, miner, farmer, builder, guard), needs (hunger, energy, rest days),
   resource quotas, debug overlay.
2. Blueprints and builders: the village builds new houses by itself.
3. Reputation, donations, help for villagers in need, discounts, theft and its consequences.
4. Defence, diplomacy and trade between settlements, threats (dragons), wars.
5. New dialogues and quests.

## Building

```
./gradlew :forge:build
```

The jar is in `forge/build/libs/`. Needs JDK 21 to run Gradle (the code targets Java 17).
