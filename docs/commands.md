# Commands and permissions

All jobs track automatically. Players don't need a command to start earning.

| Command | What it does |
| --- | --- |
| `/jobs` | Open your jobs, levels, progress, and reward rates |
| `/jobs leaderboard` | Open the job leaderboards |
| `/jobs bossbar` | Toggle your progress bar |
| `/jobs notifications` | Toggle your level-up chat messages |
| `/jobs payday` | Toggle your payday chat messages |

All three feedback options start enabled and are saved per player. They also have buttons in the jobs menu. “Next paycheck” shows the next distribution time, a countdown, and unpaid earnings; click it or reopen the menu to refresh. Hover over “Payday!” in a payment message to see the nonzero job amounts included in that deposit.

Detailed leaderboards rank players by total job XP, reading left to right from the top row down.

## Admin commands

| Command | What it does |
| --- | --- |
| `/jobs reload` | Reload both config files and apply every setting |
| `/jobs earnings [player]` | Show hourly earnings by job and the shared unpaid balance |
| `/jobs backup` | Save a checked copy of the job database in `plugins/JustJobs/backups/` |

Reload applies payday timing, payout retries, saving, travel intervals, and diagnostic logging too. Changing payday timing keeps unpaid money. Invalid settings leave the previous settings running, with an error explaining what needs fixing.

Job backups include progress and unpaid earnings. Keep full server backups as well: worlds hold block tracking, and your economy provider holds money already paid. Restore matching backups together while the server is stopped.

## Permissions

| Permission | Default | Allows |
| --- | --- | --- |
| `justjobs.use` | Everyone | Using `/jobs` and the player commands |
| `justjobs.earn` | Everyone | Earning job XP and money |
| `justjobs.admin` | Operators | Using the admin commands |
