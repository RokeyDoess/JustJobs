# Commands and permissions

All jobs track automatically. Players don't need a command to start earning.

| Command | What it does |
| --- | --- |
| `/jobs` | Open your jobs, levels, progress, and reward rates |
| `/jobs leaderboard` | Open the job leaderboards |
| `/jobs bossbar` | Toggle your progress bar |
| `/jobs notifications` | Toggle your level-up chat messages |
| `/jobs payday` | Toggle your payday chat messages |

All three feedback options start enabled and are saved per player. Click the comparator at the bottom left of the jobs or leaderboards menu to open **Settings**. The Back button returns to the page you came from.

The clock in Settings switches your time displays between 12-hour (AM/PM) and 24-hour time. Your choice is saved; until you pick one, the server's default applies.

“Next paycheck” stays on the jobs page. It shows the next distribution time, a countdown, and unpaid earnings; click it or reopen the menu to refresh. Hover over “Payday!” in a payment message to see the nonzero job amounts included in that deposit.

Detailed leaderboards rank players by total job XP, reading left to right from the top row down.

Jobs and leaderboards hold 27 entries per page, with glass along the top and bottom. Job selection keeps its center-first arrangement; the side slots provide overflow space when a page has more than 21 jobs.

Job milestones are announced in global chat every 50 levels by default. The server owner can change or disable them in `config.yml`; personal chat toggles don't affect these announcements.

## Admin commands

| Command | What it does |
| --- | --- |
| `/jobs reload` | Reload both config files and apply every setting |
| `/jobs earnings [player]` | Show earnings toward each job's cap and the shared unpaid balance |
| `/jobs backup` | Save a checked copy of the job database in `plugins/JustJobs/backups/` |

`/jobs earnings` completes matching player names as you type, including offline players with saved job data. Name suggestions are only available to admins.

Reload applies payday timing, payout retries, saving, travel intervals, and diagnostic logging too. Changing payday timing keeps unpaid money. Invalid settings leave the previous settings running, with an error explaining what needs fixing.

Job backups include progress and unpaid earnings. Keep full server backups as well: worlds hold block tracking, and your economy provider holds money already paid. Restore matching backups together while the server is stopped.

## Permissions

| Permission | Default | Allows |
| --- | --- | --- |
| `justjobs.use` | Everyone | Using `/jobs` and the player commands |
| `justjobs.earn` | Everyone | Earning job XP and money |
| `justjobs.admin` | Operators | Using the admin commands |
