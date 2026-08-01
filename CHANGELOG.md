# MobMind Changelog

## 1.2.5

### Added
- **Village Pet Protection**: Cats, wolves, and parrots are now recognized as village pets. Leashing them or luring them away with food (cod/salmon for cats, bones/meat for wolves, seeds for parrots) triggers nearby villagers to confront the player in a more worried, protective tone. If the pet is tamed and its owner is a nearby villager, the owner will personally run over to stop you.
- **Village Granary Chests**: When villagers have excess crops (wheat, carrots, potatoes, beetroots) and no existing chest nearby, they will automatically place a village chest on stable natural ground. These chests come with a proper loot table (`mobmind:chests/village_granary`) containing bread, wheat, seeds, vegetables, hay blocks, and farm tools. The chest appears unopened to Jade/WTHIT ("里面会是什么？") for the first 3 seconds before villagers start depositing items into it.
- **House Fire Panic**: If a villager detects fire or lava within 10 blocks (excluding campfires/soul campfires) OR catches fire themselves, they will panic and run toward the nearest player screaming for help to put out the fire. Villagers distinguish between regular fire and lava, and burn victims additionally cry "I'm on fire!" Creative-mode players also trigger the alert. Detection range expanded to 32 blocks, added debug logging, and removed the `isPlayerPlaced` filter on fire blocks (fire is fire regardless of origin).
- **Bone Meal Crop Fertilizing Thanks**: When a player uses bone meal on village crops (any crops, regardless of who planted them), nearby farmer villagers will thank the player for helping the crops grow faster (+1 friendship, 60-second cooldown). Works on wheat, carrots, potatoes, beetroots, and any block with an AGE growth property.
- **Crop Flood Protection**: If water flows onto village farmland (water directly above farmland blocks, washing away crops), nearby villagers will detect it and angrily run over to the player yelling at them to block the water immediately before all crops are destroyed. Farmers are especially furious. -2 friendship, 30-second cooldown per player. Excludes player-created farmland. Nether/End excluded.
- **Village Bell Destruction Response**: Breaking the village bell now properly triggers nearby villagers to react with extreme anger. The bell is treated as the most important village structure (raid alarm/meeting point), so villagers are far more upset about it than other vandalism. Uses `anyNearby=true` + fallback villager search (same as container/job block detection) so villagers always respond, with a dedicated `scoldVillageBellDestroyer` response and stronger friendship penalty. Grudge lasts 20 minutes.
- **Expanded Livestock Tempt Items**: Added support for rabbits (carrots/golden carrots/dandelions), mooshrooms (wheat), donkeys, mules (golden apples/golden carrots/sugar/wheat/apples), camels (cactus), cats, wolves, and parrots to the livestock luring detection system.
- **Raw Potato Storage**: When villagers are at full health and store potatoes in chests, they now store raw potatoes (POTATO) instead of baked potatoes (BAKED_POTATO), which is more realistic for a granary.

### Changed
- **Hay Bale Placement Rules**: Hay bales now only stack on whitelisted natural ground: grass blocks, dirt paths, sand, red sand, gravel, stone, cobblestone, and mossy cobblestone. They will never be placed on dirt, farmland, or in the middle of crop fields. The vertical stacking check now scans all the way down through existing hay bales to ensure the very bottom of a hay pillar is not on a container.
- **Container Tops Always Clear**: Hay bales can never be placed on top of any container block (chests, barrels, shulker boxes, etc.). Container tops will always remain air blocks.
- **Crafting Table Job Block**: Crafting tables are now recognized as villager job blocks. Using or breaking a crafting table near a villager will trigger them to come over and ask what you're doing with their tools, the same as other workstations.
- **Villager Crop Storage**: Carrots, potatoes, and beetroots are now also stored in village chests when harvested (not just wheat).

### Fixed
- **Hay Bales on Chests**: Fixed hay bales being placed directly on top of chests and blocking access. New `isBlockAboveContainer` check ensures no hay pillar can be built on a container surface.
- **Hay Bales on Farmland/Dirt**: Fixed hay bales being placed on dirt blocks and farmland, disrupting crop fields. Restricted to natural ground whitelist and added `isInFarmland` exclusion zone.
- **Villager No Response to Workbenches**: Fixed crafting tables not being recognized as job blocks, causing villagers to ignore players using them. Added `CraftingTableBlock` to the `isJobBlock` check.
- **Villager-Killed Animal Drops**: Animals killed by villagers no longer drop any items or experience (matching the behavior where villagers hunt for food and consume it directly).

## 1.2.4

### Added
- **Nitwit Villager Gossip**: When a player talks to a village nitwit, nearby non-nitwit villagers (within 16 blocks) will now interject and advise the player not to bother with him—exasperated, pitying, or dismissive in their own style. Up to 2 villagers chime in per conversation with a 30-second cooldown per nitwit to prevent spam.
- **Villager Whisper Gossip**: When a player wanders inside a village structure, two nearby villagers (within 16 blocks, 4+ blocks away from the player) will occasionally whisper to each other about the player. The player only catches faint fragments ("...that guy again..." / "...he's a good one...") as a grey system message—no dialogue popup, barely audible. Fragments are chosen based on friendship level and active grudges. 15% trigger chance per 30-second check, 90-second cooldown per player.
- **Farmer Crop-Planting Gratitude**: When a player plants crops (wheat, carrots, potatoes, etc.) inside a village structure, a nearby farmer villager will come over and thank them for helping with the farm work (+2 friendship). 120-second cooldown per player.
- **Tempt Item Reaction**: When a player holds an item that attracts a supported mob (e.g. red mushroom for zombie horses, warped fungus for striders), the mob will react—drawn to the item, sniffing the air, wanting to follow. Checked every 2 seconds, 30-second cooldown per mob.
- **Player Skin Gender Recognition**: Mobs now detect the player's skin model (slim/Alex = female, classic/Steve = male) via the GameProfile textures property, and address the player accordingly. Female players may be called "姐姐/小姐/美女/姑娘" (or "sister/lady/miss/pretty lady" in English mode); male players may be called "哥哥/先生/帅哥/小伙子" (or "brother/sir/mister/handsome"). Result is cached per player.
- **Unleash Reaction**: When a mob's lead is removed—by right-clicking the mob, breaking the fence post, or the lead snapping from distance—the mob reacts with relief at being free again. Iron Golems regain their dignity, villagers smooth their clothes and grumble or thank the player, Happy Ghasts hum with joy and rise up. +3 friendship recovery. Checked every 1 second, 8-second cooldown per mob.
- **Livestock Luring/Leashing Confrontation**: When a player holds a tempt item (wheat for cows/sheep, carrots for pigs, seeds for chickens, etc.) to lure passive animals away from a village, or puts a lead on them, nearby villagers will notice and confront the player—asking what they're doing and accusing them of trying to steal village livestock. 30-second cooldown per player.
- **Village Iron Golem Guard**: When a player breaks blocks or opens containers inside a village structure, nearby Iron Golems (within 16 blocks) will now walk over with heavy footsteps to investigate/warn the player. Repeated offenses (5+) or low friendship will provoke the golem into attacking. Iron Golems use context-aware prompts depending on what was broken (container/job block/village property).
- **Spawn Egg Reaction**: Using any spawn egg on a supported mob triggers a reaction. Same-type eggs (e.g. villager egg on a villager) make the mob suspicious about being duplicated ("Are you trying to replace me? Make an army?"); different-type eggs (e.g. cow egg on a villager) make the mob confused about what the player is trying to summon and why they used it on them.
- **Local Ollama Support**: Native `/api/chat` endpoint support for running the mod fully offline with any local Ollama model. No external API key required.
- **Village Property Protection**: Villagers guard hay bales (food reserves), dirt paths, cauldrons, and all village public property. Breaking these triggers scolding with context-aware prompts.
- **Job Block Guard**: Breaking villager workstations (composter, blast furnace, smoker, cartography table, grindstone, stonecutter, loom, brewing stand, lectern, fletching table, smithing table, barrel) triggers villager complaints about losing their job.
- **Job Block Use Reaction**: Right-clicking/using a villager's workstation (composter, cartography table, etc.) triggers the villager to come over and ask what you're doing with their tools.
- **Leash Reaction**: Leashing supported mobs (iron golems, villagers, animals, happy ghasts) triggers angry protests. Only mobs that can actually be leashed in vanilla trigger the reaction. Iron golems and villagers are provoked; iron golems may attack. Happy ghasts feel degraded being tethered to the ground.
- **Fishing Rod Hook**: Hooking supported mobs with a fishing rod triggers reactions. Iron golems/hostile mobs attack; villagers protest; others complain based on friendship.
- **Riding Equipment Reactions**: Saddling skeleton horses, zombie horses, or striders triggers AI responses. Horse armor gives gratitude. Warped fungus attracts striders with excitement. Zombie horses require taming; skeleton horses and striders do not.
- **Riding Reaction**: Mounting skeleton horses, zombie horses, or striders triggers AI responses. Skeleton horses feel a strange sense of purpose carrying the living; zombie horses groan with acceptance; striders shiver and click.
- **Village Livestock Protection**: Killing cows, pigs, sheep, chickens, horses, etc. near a village triggers nearby villagers to scream in horror and anger. Shearing village sheep without permission also triggers complaints.
- **Farmland Trample Reaction**: Jumping on farmland triggers nearby farmers to react. Trampling crops destroys them and causes the farmer to rush over furiously demanding compensation.
- **Grudge Memory System**: Mobs now remember grudges for 10 minutes. Even after attack targets are cleared by vanilla AI, the system prompt informs the mob about recent wrongs (attacks, house breaking, theft, leashing, etc.), keeping them angry and cold toward the offender.
- **Nitwit Villager Persona**: Village nitwits speak slowly with simple words, sometimes get confused or say silly things. Other villagers may tell visitors not to bother talking to them, but they're kind-hearted despite being dim.
- **Baby Mob Persona**: Baby mobs speak in a childlike way—short sentences, lots of emotion, easily excited or scared. They look up to adults and may call the player "big person."
- **Nether Structure Guards**: Ruined portals and nether fortresses are now guarded by zombified piglins and piglins.
- **Dungeon/Temple Guards**: Dungeons, desert pyramids, jungle temples, and trial chambers are guarded by nearby monsters when blocks are broken or containers looted.
- **Village Scold Prompts**: New context-aware prompts for village vandalism — hay bales are called out as "winter food supply," other blocks as "village public property."
- **Mod Menu Integration**: Config button added to the Mods list via Mod Menu entrypoint (optional dependency, `suggests: modmenu:*`).

### Changed
- **All Log Messages Now English**: All 86 LOGGER.info/warn/error calls across 18 source files have been translated from Chinese to English. Log output is now fully English for consistency.
- **Conversation Memory Doubled**: Memory limit increased from 20 to 40 messages (20 rounds of dialogue), so mobs remember more context.
- **Scold Cooldown Reduced**: Reduced from 5 minutes to 30 seconds so repeated block breaking continuously triggers villager complaints.
- **Structure Guard Range Expanded**: Guards (witches, vindicators, evokers, drowned, etc.) now detect block breaking within 16 blocks instead of 6, fixing cases where witch hut/woodland mansion guards never triggered.
- **Village Guard Range Expanded**: Villagers within 16 blocks can sense block breaking in village structures (up from 8).
- **Cauldron Detection**: All cauldron types (water, lava, powder snow) are now recognized as village public property.
- **Farmer-Only Crop Protection**: Only villagers with the Farmer profession react to crop breaking and farmland trampling. Other villagers no longer falsely complain about crops.
- **Livestock Kill Prompts**: Villagers now emphasize that killed animals are ones they raised and let roam free ("we raise them ourselves and let them roam free around the village"), making the scolding feel more personal.

### Fixed
- **Villager Profession Detection on MC 26.2**: Fixed `VillagerData.getProfession()` no longer existing in MC 26.2 — replaced with `profession()` (returns `Holder<VillagerProfession>`) and `ResourceKey.identifier().getPath()` comparison. Farmer-only crop protection and Nitwit/Baby persona detection work again.
- **Trade Pricing (Friendship)**: Fixed friendship-based trade pricing not working. Mixin now injects at HEAD of `Villager.setTradingPlayer()`. Small price adjustments that round to 0 now apply at least 1 item change. Friendship < 20 → +30% price; friendship ≥ 80 → -25% price.
- **Structure ID Detection**: Replaced reflection-based `getResourceKey` with direct `Registry.getKey()` API call, fixing village structure detection that silently failed in MC 26.2.
- **Player-Placed Blocks**: All blocks placed by the player (hay bales, torches, doors, crops, etc.) no longer trigger villager guards. A placement tracking system records player-placed positions and excludes them from village protection.
- **Composter Detection**: Added Block ID fallback (`composter`) for farmer workstation detection in MC 26.2.
- **Natural Loot Structure Filter**: Chests and blocks in ruined portals, dungeons, desert pyramids, jungle temples, igloos, pillager outposts, ancient cities, trial chambers, end cities, bastions, nether fortresses, nether fossils, and mineshafts no longer trigger false villager guards (when not overlapping with a village).
- **Scold Only Triggers Once**: Fixed issue where breaking job blocks or crops only triggered once due to 5-minute cooldown.
- **Bed Guard in Unclaimed Villages**: Beds inside village structures within 16 blocks of a villager are now protected even when villagers don't have HOME memory yet.
- **Leash on Non-Leashable Mobs**: Leash reaction no longer triggers for mobs that can't be leashed in vanilla (e.g., villagers, iron golems). Only `canBeLeashed()` mobs trigger the reaction.
- **False House Block Triggers**: Removed generic "house block" detection that caused villagers to falsely claim player-placed doors, raw stone, and other non-village blocks as their property. Only village-related blocks (containers, crops, job blocks, village property) now trigger protection.
- **Village Property Protection Expanded**: Fixed village bells, fences, torches, campfires, wells, walls, sandstone, cobblestone, and gravel not triggering villager protection when broken. These blocks now have context-specific scold prompts (e.g. bell = "our warning alarm", fence = "keeps animals safe", torch = "lights our streets", well water = "our water source"). Player-placed versions of these blocks (including water from buckets) are correctly excluded.
- **Bed Protection Fixed**: Fixed village beds not triggering protection when broken (BedBlock added to village property). Also fixed player-placed beds being falsely identified as villager beds in the bed guard fallback logic—`isPlayerPlaced` check added to the village-structure fallback in BedGuard.
- **Trader Llama Fishing Rod Reaction**: When a player hooks a wandering trader's llama with a fishing rod, the trader now confronts the player—angry about their llama being hurt, threatening to raise prices or refuse trade. -5 friendship (affects trade pricing). 8-second cooldown.
- **Witch's Cat Fishing Rod Reaction**: When a player hooks a cat near a witch, the witch reacts furiously—screaming curses, threatening, and attacking with potions. -5 friendship, witch is provoked for ~17 seconds.
- **Wandering Trader Bargaining Fixed**: Fixed wandering trader not reading its current trade offers during bargaining. The offers injection in the AI prompt now uses the `Merchant` interface (covers both `Villager` and `WanderingTrader`) instead of `AbstractVillager`. The `applyBargain` and `extractBargainFromText` methods also accept `Merchant`. Additionally, the `AbstractVillagerMixin` now applies friendship-based trade pricing to wandering traders when opening their trade screen (previously only `Villager` was covered by `VillagerTradeMixin`).
- **Job Block Protection Broken by markPlayerPlaced**: Fixed job blocks (cartography table, composter, etc.) not triggering villager protection when broken. The `markPlayerPlaced` call in `UseBlockCallback` was incorrectly marking positions for ALL item types (tools, food, etc.), not just `BlockItem` and buckets. Now only `BlockItem` and bucket placements mark positions as player-placed.
- **Saddle Removal Reaction**: When a player removes a saddle from a supported mob (skeleton horse, zombie horse, strider), the mob now reacts—expressing relief at being unburdened, stretching, or feeling lighter. Detected via periodic saddle state check (1 second interval), 8-second cooldown per mob.
- **Horse Armor Change Reaction**: When a player adds, removes, or swaps horse armor on a supported mob, the mob reacts—standing taller when armored, feeling lighter when unburdened, or commenting on the swap. Detected via `Mob.getBodyArmorItem()` check, 3-second cooldown.
- **Unleash Detection Fixed**: Fixed unleash events not being detected when a mob is untied from a fence and immediately re-leashed by a player within 1 second. The detection now tracks the leash HOLDER (not just leashed state), triggering the unleash reaction when the holder changes from a fence knot to a player.
- **Player Farm Exclusion**: Fixed farmer thanking players for planting crops in player-created farmland. Hoe usage on dirt is now tracked via `markPlayerPlaced`, and `onPlayerPlantCrop` checks `isPlayerPlaced(pos.below())` to exclude player-hoed farmland. Only naturally generated village farmland triggers the farmer's gratitude.
- **Crop Trampling Exclusion**: Fixed villagers scolding players for trampling their own player-hoed farmland and player-planted crops. `onFarmlandTrampled` now checks `isPlayerPlaced` for both the farmland position and the crop position above it.

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
- Path-block reminder now targets the blocking player correctly instead of falling back to the first remembered player.
- Memory file migration: old `.mobmind_memory` JSON files are automatically imported into the new NBT-based persistent storage on first world load.
- Reduced duplicate chat messages when mobs are hit while already retaliating.
- VillagerMixin no longer crashes when negotiating prices with wandering traders.

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
