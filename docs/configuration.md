# Configuration

Settings live in `plugins/JustJobs/`. `jobs.yml` contains every job; `config.yml` controls shared behavior. Edit them and run `/jobs reload`. Invalid settings leave the previous configuration running.

## Jobs and rewards

Change a rule's `xp` and `money` to tune its reward. Set a job's `enabled` to `false` to disable it while keeping saved progress.

| Job | Default money reward |
| --- | --- |
| Digging | 0.0085 per natural soil or sand block |
| Mining | 0.006 per natural stone block; ores pay 0.03, 0.08, or 0.35 |
| Treefelling | 0.075 per eligible log |
| Hunting | 0.05 per hostile mob killed |
| Dueling | 2.5 per player kill, with a 60-second cooldown between the same two players |
| Trading | 0.10 per emerald spent with a villager |
| Exploring | 0.00075 per block walked, awarded every 500 blocks; 0.25 per new biome; 0.05 per map expanded or locked |
| Sailing | 0.0005 per block sailed, awarded every 500 blocks |
| Enchanting | 0.35 per level spent at an enchanting table |
| Farming | 0.02 per crop harvest; 0.50 per animal bred; 0.025 per sheep sheared; 0.05 per hive harvest; 0.25 per animal tamed |
| Fishing | 0.25 per successful rod catch |
| Engineering | 0.02, 0.05, or 0.15 per crafted component or rail |
| Building | 0.015 per allowed block crafted; 0.005 per allowed stonecutter output; 0.025 per banner pattern applied |
| Archaeology | 0.15 per completed natural excavation; 0.05 per natural decorated pot broken |
| Smithing | 0.025–0.10 per armor or weapon crafted; 0.25 per upgrade; 0.05 per trim; 0.25 per full durability repair; 0.01 per iron, copper or gold ingot smelted |
| Cooking | 0.005 per food cooked; 0.0025 per cookie, 0.01 per bread or simple soup, 0.05 per larger meal or golden food crafted |
| Brewing | 0.025–0.10 per potion ingredient consumed, including upgrades; the menu names each ingredient group |

Mining's common ore group is coal, copper, Nether gold, and quartz. Iron, gold, redstone, and lapis use the middle rate. Diamond, emerald, and ancient debris use the highest rate. The jobs menu names each reward group; edit a rule's `description` when changing what it pays for.

Engineering pays 0.02 for redstone torches, repeaters, tripwire hooks, and ordinary rails; 0.05 for powered, detector, and activator rails; and 0.15 for components such as pistons, observers, and hoppers. Rates are per output item, so crafting 16 ordinary rails earns 0.32.

Building's crafting list stays limited to brickwork, bookshelves, flower pots, and jack o'lanterns. Stonecutting has its own list of finished brick slabs, stairs, and walls. Planks, logs, ordinary stone slabs, panes, recoloring recipes, and reversible storage blocks don't pay by default. Weaving pays for applying a banner pattern with dye.

Set `boss-bar-cooldown-seconds` on any job to space out its progress-bar popups. Cooking and Smithing default to `30`; other jobs default to `0` (no cooldown). The bar stays for the usual `feedback.boss-bar-seconds`, with live progress while visible, then waits until the cooldown ends before opening again. Frequent rewards don't keep it pinned on screen. This only affects the bar: XP, money, and chat messages continue normally. Each player has a separate cooldown for each job, and `/jobs reload` applies changes.

## Adding a job

Add another entry under `jobs:`. For example:

```yaml
  snow_digging:
    name: Snow Digging
    description: Dig natural snow blocks.
    enabled: true
    icon: SNOW_BLOCK
    color: WHITE
    rules:
      - activity: block-break
        description: natural snow block
        materials: [SNOW_BLOCK]
        xp: 1
        money: '0.04'
```

Keep the ID (`snow_digging` here) stable to preserve progress. IDs use lowercase letters, numbers, underscores, and hyphens. Change `name` to rename a job in-game. Icons use Minecraft item names; bar colors are `PINK`, `BLUE`, `RED`, `GREEN`, `YELLOW`, `PURPLE`, and `WHITE`.

| Activity | What counts |
| --- | --- |
| `block-break` | Each eligible block broken |
| `craft` | Each output item actually crafted in a player's crafting grid |
| `crop-harvest` | A mature crop broken or a successful berry harvest |
| `breed` | An animal bred, credited to the player who initiated it |
| `fish` | A successful rod catch, including junk and treasure |
| `mob-kill` | A mob kill credited to the player |
| `player-kill` | A player kill allowed by the pair cooldown |
| `trade` | Each emerald spent with a villager, using the actual trade price |
| `travel` | Each block walked |
| `sail` | Each block traveled while driving a boat on water |
| `enchant` | Each level spent at an enchanting table: 1, 2, or 3 |
| `block-place` | A new placement, once per world location; available for custom jobs only |
| `shear` | An animal successfully sheared with nonempty drops; select `entities` |
| `beehive-harvest` | A full hive manually harvested; select `HONEY_BOTTLE` or `HONEYCOMB` in `materials` |
| `tame` | The first successful taming of an animal; select `entities` |
| `stonecut` | Each output item taken from a stonecutter; select output `materials` |
| `loom` | A banner pattern applied while consuming dye; select banner `materials` |
| `brush` | A completed natural suspicious-sand or suspicious-gravel excavation with loot; select block `materials` |
| `smith` | A completed smithing-table operation; select output `materials` and optionally `operations: [upgrade, trim]` |
| `anvil-repair` | The fraction of an item's full durability restored at an anvil; select output `materials` |
| `cure-villager` | The first cure of a villager, credited to its online curing player; select `entities: [VILLAGER]`. Available for custom jobs |
| `cartography` | A completed map operation; select `materials: [FILLED_MAP]` and optionally `operations: [expand, lock, copy]` |
| `biome-discover` | First visit to a biome per player; select `biomes: ['*']` or names such as `minecraft:forest` |
| `smelt` | Nonfood output from a furnace or blast furnace; manual inputs pay their contributor, unassigned inputs pay the block owner; select output `materials` |
| `cook` | Food cooked in a furnace, smoker or campfire; select output `materials` |
| `brew` | One potion ingredient consumed, regardless of bottle count; manual inputs pay their contributor, unassigned inputs pay the block owner; select ingredient `materials` |

Material rules use names such as `[DIRT, SAND]`; block tags such as `'#minecraft:logs'` are also supported. Fishing uses caught item names, such as `[COD, SALMON]`. Animal and mob rules use `entities`, such as `[COW, SHEEP]`. `'#hostile'` selects hostile mobs; `'*'` matches everything for that selector. Matching rules add together, including across jobs.

Harvests must be mature and credited to a player. Farming includes the crops and berries listed in `jobs.yml`; melons, pumpkins, sugar cane, and cactus aren't included. Automatic crafters, dispenser shearing, and automated hive harvesting don't pay. Stonecutting uses its own activity, separate from crafting-grid rewards. Failed or cancelled actions earn nothing.

Smithing upgrades and trims can have separate rates using `operations`. Cartography defaults to expanding and locking maps; copying doesn't pay unless explicitly enabled. Anvil rewards scale with restored durability: repairing half an item earns half the configured XP and money. Renaming, enchanting, and Mending alone don't count as anvil repairs.

Biome discoveries start being tracked after this update. Each biome pays once per player across all worlds and jobs, and the history survives restarts. Walking or riding into a biome counts; teleport events don't. Taming history stays with the animal. Curing history follows villagers through reinfection, so curing the same villager repeatedly doesn't pay.

For smelting and cooking, each manually loaded quantity keeps its contributor. Unassigned inputs, including hopper-fed ingredients, pay the workstation owner. Inputs process in insertion order; withdrawals remove the newest quantities first. Campfires track each food slot separately. Fuel and whoever collects the output don't affect credit. Cooking and nonfood smelting are separate activities, so one result doesn't pay both.

The player who places a furnace, smoker, blast furnace, brewing stand, or campfire owns it. For existing blocks with no recorded owner, the first eligible player to open one claims it and gets a short confirmation. Existing campfires use the first successful food insertion instead. Opening an owned workstation never transfers it. Breaking the block clears its ownership; a replacement belongs to its new placer.

Brewing tracks only the consumed recipe ingredient. One ingredient earns one reward whether it changes one bottle or three. Bottle loading can be automated, and potion enhancements count. Blaze powder used as fuel doesn't pay; blaze powder used as a potion ingredient does.

Loading a feeder chest doesn't attach a contributor to its ingredients: hopper-fed inputs use the processing block's owner. Fully automatic farms can therefore earn for that owner too. Inputs already inside a workstation can use owner fallback after it is claimed. With neither a contributor nor an owner, processing earns nothing.

Direct inputs must be loaded while the player is eligible to earn. Known creative-mode or permission-denied inputs don't pay anyone, including through owner fallback. Owners who are online must also be eligible; offline owner fallback uses their last recorded eligibility at placement, opening, or processing. Ownership and contributor batches survive restarts, and processing can earn while their players are offline. Rewards enter the usual paycheck and per-player job cap on completion. Multiple machines don't create separate allowances, and XP stays uncapped.

## Money and limits

Players can earn 25 currency per job between paydays by default. Every job's allowance resets at the scheduled payday, including for offline players and when a deposit needs to retry. XP continues after the cap; excess money is not banked for later. The allowance survives reloads and restarts.

Defaults aim for roughly 10–20 coins per player-hour of mixed survival play. That's a tuning target, not a global cap or a guaranteed income; farm output and play styles vary.

With paydays disabled, caps use a rolling hour instead. That allowance returns in minute buckets, which can add up to a minute before older earnings expire.

Set `economy.hourly-income-limit-per-job` in `config.yml`, or add `hourly-income-limit` inside a job to override it. These existing key names still apply in payday mode. Use `0` for no limit. A custom payday interval changes the cap period too: a two-hour payday allows 25 per job over those two hours, not 25 each hour.

Paydays run every real-time hour by default, on the hour, for all players together. Offline players are included where the economy provider supports their accounts. Earnings made after the cutoff use the new allowance and wait for the following payday.

`economy.payday-interval-minutes` sets the period (1–1440 minutes). The default pays on the hour. Custom periods follow a shared UTC schedule; the menu displays server-local time. Set `economy.payday-enabled: false` for immediate payouts. Reload applies a changed period to the next future boundary and keeps everything owed. Paydays missed during downtime become payable after startup; settled paychecks aren't repeated.

Whole-number payouts pay 1 from a paycheck of 1.37 and keep 0.37 for later. Remainders combine with future released earnings. All jobs share one unpaid balance. Set `economy.whole-number-payouts` to `false` to allow the smaller payments your economy supports. Smaller remainders stay saved. `economy.currency-symbol` sets the prefix used in menus and messages; use `''` for none.

Rejected payments retry automatically without waiting for another payday. Missing economy support leaves earnings saved and produces a server log warning. Providers that require accounts have them created before payment. Partial credits keep the unpaid amount, and an unreadable account does not hold up other players. Payment messages describe only the money actually credited, after its confirmation is saved.

Payday chat starts enabled and can be toggled in the Settings menu or with `/jobs payday`. Hover over “Payday!” to see the job breakdown for that deposit. Jobs that contributed nothing are omitted. Existing unpaid balances from older versions remain payable; their source is shown as “Previous earnings” because those versions did not save a per-job unpaid breakdown.

Your economy must make deposits visible in its Vault balance immediately. Vault cannot share a transaction with JustJobs, so a crash between a deposit and its saved confirmation can cause a duplicate retry. Other wallet activity during an unreadable balance can also make that payment uncertain.

## Travel and leveling

Exploring and Sailing update every 500 blocks by default. Each job's `distance-per-reward` changes the update interval. Rule rates stay per block, so changing the interval doesn't change the total reward. Unfinished distance is saved. Slow sailing samples accumulate rather than being discarded.

Walking earns 0.02 XP per block (10 XP per update); sailing earns 0.01 (5 XP per update). Their money rates are separate.

Players start at level 1. Shared progression settings are in `config.yml`:

| Setting | Default | Meaning |
| --- | --- | --- |
| `first-level-xp` | 10 | XP needed to reach level 2 |
| `early-level-xp` | 40 | Requirement at the last early level |
| `early-level` | 10 | Last early level |
| `plateau-xp` | 200 | Flat next-level requirement at the plateau |
| `plateau-level` | 50 | First plateau level |

Requirements rise linearly between those points, then stay flat from level 50 onward. There is no level cap. Default jobs use their own quotas where activity volumes differ:

| Job | XP for level 1 → 2 | XP for level 10 → 11 | XP per level from 50 onward |
| --- | --- | --- | --- |
| Digging | 40 | 150 | 1,000 |
| Mining | 40 | 150 | 1,500 |
| Treefelling | 12 | 60 | 400 |
| Hunting | 20 | 80 | 500 |
| Dueling | 10 | 30 | 100 |
| Trading, Fishing, Brewing | 10 | 30 | 150 |
| Exploring, Building, Smithing, Cooking | 10 | 40 | 200 |
| Sailing | 5 | 25 | 100 |
| Enchanting | 25 | 75 | 250 |
| Farming | 20 | 80 | 600 |
| Engineering | 15 | 60 | 300 |
| Archaeology | 5 | 20 | 50 |

Cooked food and smelted ingots earn 1 XP each. Starting from zero, 10 items reach level 2, 24 reach level 3, and 128 reach level 7. Level 50 takes 4,933 XP in those jobs, and each level after that costs 200. Mining takes 33,144 XP to reach level 50 and 1,500 per level afterward. These are action counts and quotas, not estimates of play time.

Put `progression` inside a job to override shared values. Its `xp-multiplier` defaults to `1`: `2` requires twice the XP at every level, while `0.5` halves it. It does not compound. Changing progression keeps earned XP but may change displayed levels.

Personal level-up messages have ten seconds between them by default. If several levels arrive together, the next message shows the latest reached level for that job. XP applies immediately. Turning messages off or leaving clears any delayed notices; global milestones have their own setting and announce immediately.

## Shared settings

These are in `config.yml`. Times are in seconds unless marked otherwise.

| Setting | Default | Meaning |
| --- | --- | --- |
| `economy.payout-interval-seconds` | 10 | Retry interval for unpaid earnings |
| `economy.payday-enabled` | true | Hold new earnings for scheduled paydays |
| `economy.payday-interval-minutes` | 60 | Minutes between shared paydays |
| `economy.payout-chat` | false | Allow payment messages in immediate-payout mode |
| `display.time-format` | 12 | Default clock format: `12` uses AM/PM; `24` uses a 24-hour clock. Players can override it in Settings |
| `feedback.payday-chat` | true | Allow payday messages |
| `feedback.payday-chat-default` | true | Starting payday chat preference for new players |
| `feedback.level-up-chat` | true | Allow level-up messages |
| `feedback.level-up-chat-default` | true | Starting chat preference for new players |
| `feedback.level-up-chat-interval-seconds` | 10 | Minimum spacing between personal level messages across jobs; `0` disables spacing |
| `feedback.milestone-level-interval` | 50 | Global job announcements every N levels; `0` disables them |
| `feedback.boss-bar` | true | Allow progress bars |
| `feedback.boss-bar-seconds` | 6 | How long a progress bar stays visible |
| `feedback.leaderboard-preview-size` | 5 | Top players shown per job in the leaderboard selector; 1–10 |
| `storage.autosave-seconds` | 30 | Save interval for changed progress |
| `rules.excluded-worlds` | [] | World names where rewards are disabled |
| `rules.pvp-pair-cooldown-seconds` | 60 | Cooldown between the same two players, in either direction |
| `rules.reward-spawner-mobs` | false | Allow spawner mobs and other artificial spawns |
| `rules.travel-sample-ticks` | 20 | Sailing check interval, in server ticks |
| `rules.travel-minimum-displacement` | 0.5 | Distance collected before applying a sailing sample |
| `rules.maximum-walk-blocks-per-sample` | 12 | Maximum walking distance counted per second |
| `rules.maximum-sail-blocks-per-sample` | 35 | Maximum sailing distance counted per sample |

Milestone announcements say `PlayerName has reached level 50 Mining`. They go to global chat even if the earning player has personal level-up messages turned off. Changing the interval with `/jobs reload` applies to future level gains and doesn't announce past milestones. A reward that skips several milestones announces only the highest one crossed.

Clock times use the server's timezone. `display.time-format: 12` shows times such as `1:00 PM`; `24` shows `13:00`. This default applies to the next-paycheck entry and other clock displays until a player chooses their own format in Settings. Personal choices survive restarts and aren't changed by config reloads. Use `/jobs reload` after changing the default.

## Diagnostic logging

Set `development.verbose-logging: true` to write periodic summaries to the server log. `development.summary-interval-seconds` defaults to 300 and accepts 10–3600. Logging is off by default and reloadable. It adds no per-action log messages.

Each summary includes per-job rewarded event counts, XP, credited earnings, money withheld by job caps, money paid, and payment rejection/partial-credit counts. It also lists the 20 highest-earning player/job combinations, including player names and UUIDs. Rates use the elapsed logging window, not measured active playtime; these are diagnostic estimates rather than promised hourly incomes. Summaries reset each window and on restart, with a final summary on normal shutdown. Job progress and paychecks are saved separately and aren't affected by this setting.

## Tracking and backups

Only survival and adventure players earn rewards. Digging and Mining require natural blocks. Archaeology pays for natural decorated pots, including pots broken with Silk Touch; placing and breaking them again doesn't pay. Treefelling also accepts player-grown trees, including tree farms. New hand-placed logs do not pay. Set `natural-only: false` on a block-breaking job to allow grown or generated blocks; hand-placed blocks remain excluded.

Hunting excludes spawners, trial spawners, spawn eggs, and other artificial spawns by default. These mobs give neither job XP nor money, even after a transformation such as a zombie becoming a drowned. Set `rules.reward-spawner-mobs: true` to allow them; the setting applies to all mob-kill jobs.

Tracking starts when JustJobs is installed. Older placed blocks and changes made by world-editing tools may not be recognized. Mining excludes generated stone and piston-moved blocks. Travel excludes flight, sustained falls, swimming, teleports, passengers, and boats on land or ice. Ordinary jumps count normally.

If you add crafting rules, avoid recipes that can be reversed or recolored repeatedly. The legacy `block-place` activity can still be farmed by moving materials to fresh locations; no default job uses it.

Progress saves every 30 seconds and on normal shutdown. A crash can lose unsaved progress. Back up plugin data, worlds, and economy data together while stopped: block tracking, workstation ownership, and unfinished processing attribution live in world chunks, and paid money lives with your economy provider. The database backup alone doesn't include workstation inputs or owners.

Older configs upgrade once. Recognized default rates and the former shared cap of 100 move to the new defaults; other custom values and saved progress stay. New default jobs are added if their IDs aren't already used. Existing rules for a new activity take precedence over adding its defaults. The processing update adds Cooking, Brewing, and Smithing's ingot rule. The popup update adds the 30-second Cooking and Smithing cooldowns where unset, plus Archaeology's pot rule if it has no block-breaking rules already. Review custom Engineering or Building selectors when upgrading from placement rewards.

Back up job data before upgrading. Saved clock preferences and biome discoveries update the database format; older builds cannot open the newer database. Restore a matching pre-upgrade backup if rolling back to 1.0.2. Existing XP and money already earned aren't reduced by the new rates or cap.

The XP update keeps cumulative XP and applies the new requirements to it, so displayed levels can change. It doesn't replay old level-up messages or milestone announcements. Recognized default curves and XP rates upgrade once; custom curve points, reward amounts, selectors, and multipliers stay. A customized shared curve also stays in place.
