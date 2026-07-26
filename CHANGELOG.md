# MobMind Changelog

## 1.1.1

### Added
- **Creative Mode Taunt Toggle**: New on/off switch in the MobMind settings screen (Ctrl+K). Disables hostile mobs taunting Creative-mode players to switch to Survival.
- **Group Gossip Network**: Mobs now spread rumors within their own kind. Hitting a villager may lower friendship with nearby villagers; attacking a skeleton can make nearby skeletons dislike you.
- **TNT Fear**: Mobs and villagers actively flee placed or primed TNT. Villagers near TNT will plead with nearby players to remove it.
- **Zombie Villager Curing Behavior**: After applying Weakness + Golden Apple, curing zombie villagers roll a loyalty outcome while transforming:
  - 60% loyal — helps you fight nearby hostile mobs.
  - 30% neutral — stops attacking you.
  - 10% hostile — still attacks you.
- **Gift Acceptance**: Passive/neutral mobs (e.g. villagers) can now accept gifts before reaching full friendship.

### Changed
- **Full English Localization**: Mobs now receive English system prompts, context descriptions, and TTS voice mapping when the game language is English.
- **Creative Taunt Conditions**: Mobs only taunt about Creative mode when the player is actually in Creative mode.
- **Settings UI Layout**: Adjusted button spacing so the new Creative Mode Taunt toggle no longer overlaps the Save/Cancel buttons.

### Fixed
- **Bargain Master Achievement**: No longer triggers on regular barter trades (e.g. witches); it only unlocks after successfully haggling a villager's price down.
- **Boss Calm Persistence**: Calmed Ender Dragons and Withers no longer continue attacking; also fixed a server crash caused by an oversized AABB during boss scanning.
- **Witch Barter Potions**: Trading with witches now correctly gives potions with effects instead of empty bottles.
- **Friendly Mob Despawn**: Mobs that have become friends with a player now keep their persistence across world reloads.
- **Tamed Wolf Aggression**: Tamed wolves no longer attack mobs that are friendly/calmed toward their owner.
