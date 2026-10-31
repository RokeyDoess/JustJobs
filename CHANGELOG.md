# Changelog

## 1.1.2 (2026-10-30)

- Hunting now rewards boss fights: 200 XP and 50 coins for the Ender Dragon, 50 XP and 12.50 coins for the Wither. Every eligible player who damaged the boss gets the full reward, including offline contributors. Participation survives restarts.
- Added reusable `bonus: true` reward rules. Bonuses pay immediately and bypass the job money cap without using its allowance. Both default boss rules are bonuses; their XP, money, and bonus status are configurable. Whole-number remainders stay saved and failed deposits retry automatically.
- Confirmed bonus deposits send `Bonus! x has been deposited into your account.` Hover over “Bonus!” for the paid sources. `feedback.bonus-chat` controls these messages independently of payday chat.
- The next-paycheck entry shows the payout earned so far, rounded for the server's payout mode and economy provider. Fractional leftovers stay saved without inflating the displayed paycheck.
- Workstations are now claimed silently.
- Added short explanations throughout `config.yml`. Missing descriptions are added to existing configs on load; custom values and comments stay.

## 1.1.1 (2026-10-29)

- Manually loaded ingredients still pay the player who added them. Hopper-fed and other unassigned ingredients now pay the workstation owner, including fully automatic setups.
- Placing a workstation records its owner. The first eligible player to open an unowned workstation claims it; later visitors don't change ownership. Existing campfires are claimed on their first successful food insertion.
- Ownership and ingredient credit survive restarts. Breaking and replacing a workstation clears the old ownership. All of an owner's machines share the normal job money cap; XP remains uncapped.
- Inputs loaded by players who can't earn rewards don't pay the owner instead.

## 1.1.0 (2026-10-27)

- Added Archaeology, Smithing, Cooking, and Brewing, bringing the default job count to seventeen. Archaeology covers brushing and natural decorated pots. Smithing covers gear crafting, upgrades, trims, anvil repairs, and ingot smelting.
- Farming now includes shearing, hive harvesting, and taming. Building includes selected stonecutter outputs and banner weaving. Exploring includes first-time biome discoveries and map expansion or locking.
- Added fourteen activity types for custom jobs, including villager curing. Taming, curing, and biome rewards keep their history across restarts; curing the same villager repeatedly doesn't pay.
- Cooking and smelting credit whoever manually loaded each input quantity. Hopper-fed inputs don't pay; fuel and output collection don't affect credit. Brewing pays per manually loaded ingredient consumed, including potion improvements. Bottles may be automated. Unfinished batches keep their attribution through restarts and can finish while the player is offline.
- Rebalanced XP requirements by job, with quicker starts for slower activities and a flat cost from level 50 onward. Cooking and ingot smelting earn 1 XP per item; walking earns 0.02 XP per block. Existing XP is kept, so displayed levels may change when the new curves apply. Old milestones aren't replayed.
- Personal level-up messages are spaced ten seconds apart by default, combining rapid gains into the latest level reached per job. Cooking and Smithing have a 30-second boss-bar cooldown. Both intervals are configurable and don't delay earnings.
- Added global milestone announcements every 50 job levels by default. The interval is configurable; bulk gains announce only the highest milestone crossed.
- Reduced default money rewards: Hunting pays 0.05 per kill, Dueling 2.5, Enchanting 0.35 per level spent, Exploring 0.00075 per block, and Mining 0.006/0.03/0.08/0.35 by block group. The shared default cap is now 25 per job per payday.
- Removed side glass borders and expanded menu pages to 27 entries. Existing job placement stays familiar; detailed leaderboards read left to right. Navigation and settings buttons stay in place.
- Added player-name completion for `/jobs earnings`, including players with saved data who are offline.
- Added a saved 12/24-hour clock choice in Settings. The server default is 12-hour time with AM/PM.
- Recognized defaults upgrade automatically; custom curve points, XP rates, selectors, and unpaid money are kept. Back up plugin data and worlds before updating. The new database format requires a matching pre-update backup to roll back to 1.0.2.

## 1.0.2 (2026-10-26)

- Job money caps now reset at each scheduled payday when paychecks are enabled. Immediate payouts keep rolling-hour caps. Reloads and restarts retain the current allowance; XP remains uncapped.
- Hunting now pays 0.1667 per hostile mob, down from 0.40, with the same XP. Existing default rewards upgrade automatically; custom rates are kept.
- Moved the boss bar, level-up chat, and payday chat toggles into a separate Settings menu. Open it from the comparator at the bottom left of the jobs or leaderboards page; Back returns to the same page.
- Updated the SQLite storage library, Vault API, logging API, and build tools.
- Updated the test framework.

## 1.0.1 (2026-10-25)

- Added automatic hourly paychecks, with an optional immediate-payout mode. Offline players are paid, fractions carry forward, and missed or rejected payments recover automatically.
- Added “Next paycheck” to the jobs menu and a saved payday chat toggle, enabled by default. Hovering over “Payday!” shows the deposit's per-job breakdown.
- Added optional verbose logging with periodic job totals, per-player earning rates, cap deductions, and payment outcomes.
- Fishing pays 0.25 per catch, down from 0.75. XP is unchanged.
- Exploring and Sailing update every 500 blocks. Per-block XP and money rates are unchanged.
- Spawner mobs and other artificial spawns no longer earn Hunting XP or money by default. Set `rules.reward-spawner-mobs: true` to allow them.
- Dueling pays 5 per player kill, down from 10. XP and the pair cooldown are unchanged.
- Existing default settings upgrade automatically; custom rates and intervals are kept.
- Back up job data before updating. Rolling back from the paycheck database upgrade requires a pre-update database.

## 1.0.0 (2026-10-22)

- First stable release, with 13 automatic jobs, Vault rewards, progress menus, and leaderboards.
- Digging now pays 0.0085 per block. Mining's stone rate stays at 0.0075, with higher rates for ores.
- Dueling now pays 10 per player kill.
- Exploring and Sailing both update every 200 blocks. Per-block XP and money rates are unchanged.
- Slow sailing now keeps small movement samples instead of losing their distance.
- Vault accounts are created when a provider needs them. Failed account creation keeps the reward for an automatic retry.
- Missing economy support now produces a clear server log message without spamming every retry.
- Updated and shortened the docs, with Paper support, Java, build, and license badges in the README.
- Updated the Paper API and compatibility checks to the latest stable 26.3 build.

## 0.0.4

- Detailed leaderboards now display player entries left to right, from the top row to the bottom row, on every page.
- Engineering and Building now reward crafting instead of placement. Repeatedly placing, breaking, or moving the same blocks no longer earns rewards from either default job.
- Building now allows thirteen masonry and decorative outputs. Removed broad wood, slab, stair, glass, and recoloring selections. Engineering excludes redstone dust, storage blocks, levers, and buttons.
- Crafting rewards count actual output items, including bulk crafts. Failed and cancelled attempts earn nothing; automatic crafters and stonecutters are excluded.
- Existing default job configurations upgrade without resetting progress or tuned reward amounts.
- Check the money actually received before confirming a Vault payout. Canceled and partial credits keep the unpaid amount, even when the provider reports success.
- Wait for an unreadable balance to recover before attempting another deposit. Reloading keeps that check in progress.
- A player with an unreadable economy balance no longer holds up other players' payouts or saved confirmations.
- Send optional payout messages only after the payment confirmation is saved.
- Write configuration updates safely and finish interrupted updates on the next load. Normal reloads keep the formatting of an unchanged jobs file.
- Handle database locks and failed opens more cleanly. Added tests for full disks, read-only storage, damaged data, and abrupt process exits.
- Added `/jobs backup` for a checked copy of job data without stopping the server.
- Vault is now a required dependency. EssentialsX remains optional.

## 0.0.3

- Added Farming, Fishing, Engineering, and Building, bringing the default set to thirteen jobs. All four track automatically and can be tuned in `jobs.yml`.
- Reward lines now describe what earns each rate, including Mining's ore groups and Engineering's component groups. Labels can be edited per rule.
- Added `economy.currency-symbol`, defaulting to `$`, for menus and money messages. Small reward rates retain all their decimal places.
- Farming rewards mature crops, berry harvests, and animal breeding. Fishing rewards successful rod catches.
- Engineering rewards block placement only. It shares a saved, once-per-location placement allowance with Building, preventing repeated rewards from replacing blocks or swapping players. Doors count once and reserve both spaces.
- Renamed the default jobs to Digging, Mining, Treefelling, Hunting, Dueling, Trading, Exploring, Sailing, and Enchanting. Default job IDs now match these names in lowercase.
- Exploring grants rewards every 150 blocks and Sailing every 200. Each job's interval is configurable, and unfinished distance is saved.
- Walking follows the actual horizontal path, so jumping and turning don't reduce the distance counted.
- Level-up messages now read "Reached level 2 Digging". Added a saved chat toggle to the menu and a configurable starting preference.
- Replaced the old XP curve with generous early levels, a linear rise to level 50, and flat requirements afterward. Added a per-job XP requirement multiplier.
- Jobs and leaderboards share a five-row layout, with jobs filling the middle row first, centered controls, and a close button at bottom right. Jobs have gray borders and leaderboards have purple borders. Menu tools and weapons no longer show their native stats.
- Leaderboard job icons show the top five players by default; the preview size can be set from one to ten. Detailed rankings use each player's head.
- Retuned payouts for slower earnings: soil and stone 0.0075, logs 0.075, hostile kills 0.40, player kills 12.50, emeralds spent 0.10, and enchanting 0.50 per level spent. Ores pay 0.03, 0.10, or 0.50 depending on type.
- Exploring pays 0.001 per block and Sailing 0.0005. The 100-per-hour cap per player and job stays in place.
- Removed payment-retry details from player-facing menus and command output.
- Existing configurations upgrade automatically while retaining custom names and reward values.

## 0.0.2

- Gladiator kills give 10 XP and pay 20, with a 60-second cooldown between the same two players.
- Rebalanced payouts around ordinary play, including time spent gathering supplies and moving between tasks.
- Digger pays 0.04 per block. Miner pays 0.05 for stone and 0.50 for ores. Lumberjack pays 0.25 per log.
- Hunter pays 0.75 per kill, and Trader pays 0.50 per emerald spent.
- Enchanter rewards the levels actually spent: 10 XP and 1 currency per level, so a level-30 offer pays three times a one-level enchant.
- Explorer pays 0.002 per block walked, and Sailor pays 0.001 per block sailed.
- Spawner mobs earn rewards by default. Lumberjack accepts player-grown trees while still excluding new hand-placed logs.
- Added a per-job `natural-only` setting. Digger and Miner still require natural blocks.
- Added a rolling 100-per-hour money cap for each player and job. XP keeps counting, and limits can be changed or disabled.
- Cancelled deaths no longer give kill rewards. Cancelled breaks, trades, and enchants are also covered by server checks.
- Reload now applies every setting, including intervals. Invalid settings leave the previous configuration running.
- Failed and interrupted payments recover automatically. New earnings pay as soon as possible, with fractions retained when needed.
- Players can toggle their boss bar with `/jobs bossbar` or the jobs menu. The setting is saved, and the bar switches jobs less often.
- Menus show reward amounts, hourly earnings, and personal leaderboard rank. Admins can check earnings with `/jobs earnings`.
- Ordinary jumps count toward Explorer progress.
- Batched block tracking and payouts, saved only changed players, and moved leaderboard sorting off the server thread.
- Added repeatable Paper checks for events, payments, reloads, restarts, menus, and simulated load.

## 0.0.1

Initial prerelease for Paper 26.3 and Java 25.

- Nine jobs that track automatically, all configured in one file.
- Separate levels for each job, with quick early levels and adjustable XP requirements.
- A progress boss bar, optional level-up messages, job menus, and leaderboards.
- Vault payouts, with the option to save fractions until a whole coin can be paid.
- Saved progress and pending earnings, plus commands for checking interrupted payments.
- Placed-block tracking, PvP cooldowns, and checks on travel rewards.
