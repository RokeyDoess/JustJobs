# Commands and permissions

All jobs track automatically. Players don't need a command to start earning.

| Command | What it does |
| --- | --- |
| `/jobs` | Open your jobs, levels, and progress; click a job for activities and rewards |
| `/jobs leaderboard` | Open the job leaderboards |
| `/jobs history` | View your latest 30 confirmed deposits and their job breakdowns |
| `/jobs bossbar` | Toggle your progress bar |
| `/jobs notifications` | Toggle your level-up chat messages |
| `/jobs payday` | Toggle your payday chat messages |

All three feedback options start enabled and are saved per player. Click the comparator at the bottom left of the jobs or leaderboards menu to open **Settings**. The Back button returns to the page you came from.

The clock in Settings switches your time displays between 12-hour (AM/PM) and 24-hour time. Your choice is saved; until you pick one, the server's default applies.

“Next paycheck” stays on the jobs page. It shows the next distribution time, a countdown, and the payout earned so far, with the server's payout rounding applied. Fractional leftovers stay saved. Click it for paycheck history, or reopen the jobs menu to refresh the preview. Hover over “Payday!” in a payment message to see the nonzero job amounts included in that deposit.

History shows the actual amount deposited, its date, and each job's share. Bonus deposits have their own entry. Failed attempts stay out of history, and a partial payment shows only the amount received. Entries start when this update is installed and survive restarts. Dates use the server's timezone and your chosen clock format.

Bonuses pay immediately. Hover over “Bonus!” in their deposit message to see the source. The server can disable these messages separately from payday chat.

Detailed leaderboards rank players by total job XP, reading left to right from the top row down.

Jobs are grouped into Gathering, Crafting & Trade, and Adventure rows. Hover over a job for its level, progress, and earnings. Click it for detailed progress and individual reward entries, with a button for that job's leaderboard. Back returns to the jobs page you came from.

The leaderboard selector uses the same groups. Each row holds nine jobs; larger groups get more pages. Detailed leaderboards and job reward lists hold 27 entries per page. The top and bottom bars keep their usual navigation, Settings, and Close buttons.

Job milestones are announced in global chat every 50 levels by default. The server owner can change or disable them in `config.yml`; personal chat toggles don't affect these announcements.

## Admin commands

| Command | What it does |
| --- | --- |
| `/jobs reload` | Reload both config files and apply every setting |
| `/jobs earnings [player]` | Show earnings toward each job's cap and the shared unpaid balance |
| `/jobs economy` | Open server earnings, deposits, and money cap totals by job. Console receives a text report |
| `/jobs backup` | Save a checked copy of the job database in `plugins/JustJobs/backups/` |

`/jobs earnings` completes matching player names as you type, including offline players with saved job data. Name suggestions are only available to admins.

The economy overview records from its displayed start date. Earned money includes bonuses and money waiting for payday. Deposited money includes confirmed payments, which may cover earnings from before tracking began. The unpaid balance also includes earlier earnings carried forward. “Money above cap” is the amount withheld by job caps. “Cap reached” counts a player reaching a job's limit, once until their allowance returns. Repeated actions at the cap don't add more cap reaches. Reopen the overview to refresh it.

Reload applies payday timing, payout retries, saving, travel intervals, and diagnostic logging too. Changing payday timing keeps unpaid money. Invalid settings leave the previous settings running, with an error explaining what needs fixing.

Job backups include progress and unpaid earnings. Keep full server backups as well: worlds hold block tracking, workstation ownership, and unfinished ingredient attribution. Your economy provider holds money already paid. Restore matching backups together while the server is stopped.

## Permissions

| Permission | Default | Allows |
| --- | --- | --- |
| `justjobs.use` | Everyone | Using `/jobs` and the player commands |
| `justjobs.earn` | Everyone | Earning job XP and money |
| `justjobs.admin` | Operators | Using the admin commands |
