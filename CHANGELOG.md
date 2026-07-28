# MobMind Changelog

## 1.2.0

### Added
- **Persistent Conversation Memory**: Mob conversations are saved to the world file and persist across restarts.
- **Weapon Gifting**: Give melee weapons, bows, crossbows to mobs by sneaking + right-click or dropping nearby. Mobs equip and use them against threats.
- **Shield Gifting**: Mobs can equip shields in off-hand and block incoming attacks.
- **Arrow Ammunition**: Give arrows (including spectral/tipped) to ranged mobs; special arrows used first, regular arrows as fallback.
- **Iron Ingot Golem Repair**: Right-click iron golems with iron ingots to repair them and get friendly reactions.
- **Path Blocking Reminder**: Mobs politely ask players to move after standing in their path for 2+ seconds.
- **Friend Selector Tool**: Craft with 2 sticks + 1 cobblestone; select/deselect priority friends, which Ctrl+Z recall will prioritize.
- **Death Item Recovery**: Friendly mobs with inventory pick up player death drops and return them on respawn.
- **Copper Golem Persona**: Loyal/Independent mechanical personality variants.

### Changed
- Conversation memory window increased from 4 to 20 messages.
- Weapon pickup range expanded to 8 blocks; non-provoked mobs can also pick up weapons.
- Shield block triggers at 66% HP, instantly on hit, and tracks threats for up to 3–5 seconds.
- Recall (`/dismiss`) only affects mobs the player has actually talked to.
- Friend Selector appears in the Tools & Utilities creative tab.

### Fixed
- Mobs properly retaliate when hit; separate retaliation from chat cooldown.
- Skeletons/strays/bogged/wither skeletons keep vanilla infinite-arrow AI unless given a player weapon.
- Enderman no longer enters permanent anger from gifted weapons; only provokes when attacked or defending allies.
- Enderman teleports away with an echoing taunt when hit by arrows.
- Non-sneak right-click no longer consumes weapons/armor/shields/arrows.
- Bow/crossbow mobs properly shoot from distance and fall back to melee when out of ammo.
- Fixed `NullPointerException: Item id not set` crash on MC 26.2 for custom items/recipes.

## 1.1.2

### Added
- **Persistent Conversation Memory**: Mob conversations are now saved to the world file. Mobs remember what you talked about even after you restart the game or leave the area.
- **Weapon Gifting**: Players can now give melee weapons, bows, and crossbows to mobs by sneaking + right-clicking them or dropping the item nearby. The mob equips it in its main hand and uses it to attack nearby enemies. Bows and crossbows require ammunition to fire.
- **Shield Gifting**: Players can now give shields to mobs by sneaking + right-clicking them or dropping the item nearby. The mob equips it in its off hand and uses it to block incoming attacks.
- **Arrow Ammunition**: Players can give arrows (including spectral arrows and tipped/potion arrows) to mobs by sneaking + right-clicking or dropping them nearby. Mobs with bows or crossbows will consume this ammunition when firing at enemies. Special arrows (spectral and tipped) are used first, with regular arrows used as fallback once special arrows are depleted.
- **Iron Ingot Golem Repair**: Players can now right-click iron golems with iron ingots to repair them (heals 25 HP per ingot). After being repaired, the golem plays a thankful reaction including particle effects and a friendly message.
- **Path Blocking Reminder**: When a player stands directly in a mob's path for more than 2 seconds, the mob will politely ask the player to move out of the way (with 8-second cooldown per mob). Works for all mobs with personality support.

### Changed
- **Conversation History Limit**: Increased the conversation memory window from 2 rounds (4 messages) to 10 rounds (20 messages).
- **Weapon Pickup Range**: Expanded weapon pickup range from 4 blocks to 8 blocks and relaxed friendship requirements so that any non-provoked mob can pick up dropped weapons.
- **Weapon Attack Target Selection**: Fixed a bug where mobs with weapons would automatically attack nearby players and other mobs. Now weapon-wielding mobs only attack monsters that are actively threatening the player or other friendly mobs, and will never attack players, same-species mobs, or other weapon/shield-wielding allies. Different species of hostile mobs can still fight each other (vanilla behavior preserved). Skeletons with bows retain their original ranged attack AI and will not switch to melee attacks.
- **Shield Block Improvement**: Mobs holding shields now instantly raise their shield when hit from the front, completely blocking the incoming attack. They continue to block while enemies are nearby, health is low, or they are being attacked.
- **AI Hand Description Fix**: Fixed a bug where the AI would incorrectly say the player is holding "air" when the player's hand item is consumed during an interaction (e.g., giving a weapon or armor). The AI now correctly recognizes the player's hand as empty in these cases.
- **Death Item Recovery**: When a player dies, nearby friendly mobs (with friendship level >= 40) will pick up the player's death drops and store them in their inventory. When the player respawns, the friendly mobs will return the items to the player by dropping them at the player's location. This feature only applies to mobs that have an inventory (e.g., villagers, piglins).
- **Copper Golem AI Persona**: Rewrote the copper golem persona with a new personality system: 70% Loyal type (follows commands, protects owner, organizes items) and 30% Independent type (uses own judgment, can refuse dangerous orders with suggestions). Both types maintain mechanical life traits — calm, polite, fact-based, not fully human.

### Fixed
- **Mob Retaliation on Hit**: Mobs now properly fight back when attacked. The retaliation logic was previously throttled by a 20-second cooldown shared with chat reactions, causing mobs to stand still while being hit. Now every hit instantly provokes the mob and locks the attacker as the target.
- **Skeleton Infinite Arrows**: Naturally spawned skeletons, strays, bogged, and wither skeletons now retain their original infinite-arrow ranged AI. A new tracking system distinguishes between player-given weapons (which use custom attack goals) and naturally spawned weapons (which keep vanilla AI with infinite ammo).
- **Friendly Mob Retaliation**: Good-aligned mobs no longer just stand there when hit. While they won't start fights on their own, they now properly provoke and fight back when attacked, with a 5-minute retaliation window.
- **Friendly Mob Help Defense**: Weapon/shield-equipped allies now always help defend the player when monsters attack. Other friendly mobs also have improved assist probability based on friendship and temper.
- **Shield Block Retaliation**: Mobs with shields now get provoked even when the shield blocks 100% of the damage. The hurt reaction moved to the HEAD of hurtServer, ensuring the retaliation trigger fires before damage is negated by blocking.
- **Bow/Crossbow Ranged Attack**: Mobs given bows or crossbows (zombies, villagers, etc.) now properly shoot arrows from a distance instead of trying to melee. The vanilla MeleeAttackGoal is suppressed when the mob holds a player-given ranged weapon with ammunition, and resumes when ammo runs out (mob switches back to melee).
- **Non-Sneak Item Consumption**: Fixed a bug where holding weapons, armor, shields, or arrows and right-clicking mobs without sneaking could cause items to disappear. Now equipment items require sneaking to give; non-sneak right-clicks with these items do nothing (items stay in hand), while food can still be fed without sneaking.
- **Enderman Permanent Anger**: Fixed a bug where giving an enderman any weapon caused it to enter permanent anger state and attack everything in sight. Neutral mobs (endermen, zombified piglins, polar bears, etc.) now never actively seek out targets on their own — they only attack when provoked or when protecting the player/friendly mobs, matching vanilla behavior.
- **Enderman Arrow Teleport Taunt**: Endermen now say an eerie, echoing taunt line when hit by an arrow and teleporting away to dodge, mocking the archer's aim or boasting about their teleportation.
- **Arrow Ammo Fallback**: Ranged weapon mobs no longer stop shooting after special arrows run out. After consuming all tipped/potion arrows and spectral arrows, they automatically fall back to infinite regular arrows like vanilla skeletons.
- **Shield Block Logic**: Shield-wielding mobs now raise their shields more reliably: block triggers at 66% HP (instead of 50%), instantly raises shield when hit, blocks when hostile mobs or projectiles are nearby, and faces threats while blocking for up to 3 seconds.
- **Item Pickup Logic**: Fixed mobs not picking up dropped weapons, armor, shields, or other equipment. Unified pickup thresholds and organized priority: arrows → totems of undying → weapons/shields/armor → food → other items.

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
