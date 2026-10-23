# Configuration

Settings live in `plugins/JustJobs/`. `jobs.yml` contains every job; `config.yml` controls shared behavior. Edit them and run `/jobs reload`. Invalid settings leave the previous configuration running.

## Jobs and rewards

Change a rule's `xp` and `money` to tune its reward. Set a job's `enabled` to `false` to disable it while keeping saved progress.

| Job | Default money reward |
| --- | --- |
| Digging | 0.0085 per natural soil or sand block |
| Mining | 0.0075 per natural stone block; ores pay 0.03, 0.10, or 0.50 |
| Treefelling | 0.075 per eligible log |
| Hunting | 0.40 per hostile mob killed |
| Dueling | 10 per player kill, with a 60-second cooldown between the same two players |
| Trading | 0.10 per emerald spent with a villager |
| Exploring | 0.001 per block walked, awarded every 200 blocks |
| Sailing | 0.0005 per block sailed, awarded every 200 blocks |
| Enchanting | 0.50 per level spent at an enchanting table |
| Farming | 0.02 per mature crop or berry harvest; 0.50 per animal bred |
| Fishing | 0.75 per successful rod catch |
| Engineering | 0.02, 0.05, or 0.15 per crafted component or rail |
| Building | 0.015 per allowed block crafted |

Mining's common ore group is coal, copper, Nether gold, and quartz. Iron, gold, redstone, and lapis use the middle rate. Diamond, emerald, and ancient debris use the highest rate. The jobs menu names each reward group; edit a rule's `description` when changing what it pays for.

Engineering pays 0.02 for redstone torches, repeaters, tripwire hooks, and ordinary rails; 0.05 for powered, detector, and activator rails; and 0.15 for components such as pistons, observers, and hoppers. Rates are per output item, so crafting 16 ordinary rails earns 0.32.

Building allows thirteen outputs: bricks, stone bricks, mud bricks, Nether bricks, red Nether bricks, End stone bricks, purpur blocks, deepslate tiles, tuff bricks, polished blackstone bricks, bookshelves, flower pots, and jack o'lanterns. Planks, logs, slabs, stairs, panes, recoloring, and reversible storage blocks are excluded.

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

Block and crafting rules use `materials`, such as `[DIRT, SAND]`. Block tags such as `'#minecraft:logs'` are supported. Fishing uses caught item names, such as `[COD, SALMON]`. Mob and breeding rules use `entities`, such as `[ZOMBIE, SKELETON]` or `[COW, SHEEP]`. `'#hostile'` selects hostile mobs; `'*'` matches everything for that activity. Matching rules add together, including across jobs.

Harvests must be mature and credited to a player. Farming includes the crops and berries listed in `jobs.yml`; melons, pumpkins, sugar cane, and cactus aren't included. Enchanting excludes anvils and grindstones. Crafting includes bulk and hotbar crafting but excludes automatic crafters and stonecutters. Failed or cancelled actions earn nothing.

## Money and limits

Players can earn 100 currency per job in a rolling hour by default. XP continues after the cap; excess money is not banked for later. The allowance survives reloads and restarts. It returns in minute buckets, which can add up to a minute before older earnings expire.

Set `economy.hourly-income-limit-per-job` in `config.yml`, or add `hourly-income-limit` inside a job to override it. Use `0` for no limit.

Whole-number payouts pay 1 from earnings of 1.37 and keep 0.37 for later. All jobs share one unpaid balance. Set `economy.whole-number-payouts` to `false` to allow the smaller payments your economy supports. Smaller remainders stay saved. `economy.currency-symbol` sets the prefix used in menus and messages; use `''` for none.

Rejected payments retry automatically. Missing economy support leaves earnings saved and produces a server log warning. Providers that require accounts have them created before payment. Partial credits keep the unpaid amount, and an unreadable account does not hold up other players.

Your economy must make deposits visible in its Vault balance immediately. Vault cannot share a transaction with JustJobs, so a crash between a deposit and its saved confirmation can cause a duplicate retry. Other wallet activity during an unreadable balance can also make that payment uncertain.

## Travel and leveling

Exploring and Sailing update every 200 blocks by default. Each job's `distance-per-reward` changes the update interval. Rule rates stay per block, so changing the interval doesn't change the total reward. Unfinished distance is saved. Slow sailing samples accumulate rather than being discarded.

Players start at level 1. Shared progression settings are in `config.yml`:

| Setting | Default | Meaning |
| --- | --- | --- |
| `first-level-xp` | 25 | XP needed to reach level 2 |
| `early-level-xp` | 150 | Requirement at the last early level |
| `early-level` | 10 | Last early level |
| `plateau-xp` | 2500 | Flat next-level requirement at the plateau |
| `plateau-level` | 50 | First plateau level |

Requirements rise linearly between those points: 25 XP at level 1, 81 at level 5, 150 at level 10, 1,325 at level 30, and 2,500 from level 50 onward. Dueling starts at 10 XP, so the first kill earns a level.

Put `progression` inside a job to override shared values. Its `xp-multiplier` defaults to `1`: `2` requires twice the XP at every level, while `0.5` halves it. It does not compound. Changing progression keeps earned XP but may change displayed levels.

## Shared settings

These are in `config.yml`. Times are in seconds unless marked otherwise.

| Setting | Default | Meaning |
| --- | --- | --- |
| `economy.payout-interval-seconds` | 10 | Retry interval for unpaid earnings |
| `economy.payout-chat` | false | Send a message when money is paid |
| `feedback.level-up-chat` | true | Allow level-up messages |
| `feedback.level-up-chat-default` | true | Starting chat preference for new players |
| `feedback.boss-bar` | true | Allow progress bars |
| `feedback.boss-bar-seconds` | 6 | How long a progress bar stays visible |
| `feedback.leaderboard-preview-size` | 5 | Top players shown per job in the leaderboard selector; 1–10 |
| `storage.autosave-seconds` | 30 | Save interval for changed progress |
| `rules.excluded-worlds` | [] | World names where rewards are disabled |
| `rules.pvp-pair-cooldown-seconds` | 60 | Cooldown between the same two players, in either direction |
| `rules.reward-spawner-mobs` | true | Allow spawner mobs and other artificial spawns |
| `rules.travel-sample-ticks` | 20 | Sailing check interval, in server ticks |
| `rules.travel-minimum-displacement` | 0.5 | Distance collected before applying a sailing sample |
| `rules.maximum-walk-blocks-per-sample` | 12 | Maximum walking distance counted per second |
| `rules.maximum-sail-blocks-per-sample` | 35 | Maximum sailing distance counted per sample |

## Tracking and backups

Only survival and adventure players earn rewards. Digging and Mining require natural blocks. Treefelling also accepts player-grown trees, including tree farms. New hand-placed logs do not pay. Set `natural-only: false` on a block-breaking job to allow grown or generated blocks; hand-placed blocks remain excluded.

Tracking starts when JustJobs is installed. Older placed blocks and changes made by world-editing tools may not be recognized. Mining excludes generated stone and piston-moved blocks. Travel excludes flight, sustained falls, swimming, teleports, passengers, and boats on land or ice. Ordinary jumps count normally.

If you add crafting rules, avoid recipes that can be reversed or recolored repeatedly. The legacy `block-place` activity can still be farmed by moving materials to fresh locations; no default job uses it.

Progress saves every 30 seconds and on normal shutdown. A crash can lose unsaved progress. Back up plugin data, worlds, and economy data together while stopped: block tracking lives in world chunks, and paid money lives with your economy provider.

Older configs upgrade once. Recognized default rates and travel intervals move to the new defaults; custom values and saved progress stay. Review custom Engineering or Building selectors when upgrading from placement rewards.
