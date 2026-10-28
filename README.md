# JustJobs

[![Paper 26.3](https://img.shields.io/badge/Paper-26.3-21b8b6)](https://papermc.io/downloads/paper)
[![Java 25](https://img.shields.io/badge/Java-25-orange)](https://docs.papermc.io/paper/getting-started/)
[![Build](https://img.shields.io/github/actions/workflow/status/RokeyDoess/JustJobs/build.yml?branch=main)](https://github.com/RokeyDoess/JustJobs/actions)
[![GPLv3](https://img.shields.io/badge/License-GPLv3-blue)](LICENSE)

A jobs plugin that just does jobs.

Dig a tunnel, chop some trees, catch a fish. JustJobs rewards everyday survival play with job XP, levels, and money. Every job tracks automatically, so players can get on with playing instead of choosing careers or managing job slots.

`/jobs` shows progress, rewards, leaderboards, and the next paycheck in a simple inventory menu. A boss bar keeps progress visible while playing. Settings lets players choose their boss bar, chat and clock preferences. Every 50 job levels, the server gets a milestone announcement.

## What you get

Seventeen jobs, from Mining and Farming to Cooking, Brewing, Archaeology, and Smithing. Make gear, harvest honey, cook a meal, or find a new biome—there's more than one way to earn.

Paychecks arrive automatically every real-time hour, including for offline players. Hover over “Payday!” in chat to see what each job paid. Whole-number payouts carry small remainders forward, so no earnings go to waste. Immediate payouts are available too.

All jobs live in one `jobs.yml`. Change rewards, adjust leveling, disable jobs, or add your own. Each job has a configurable money cap that resets at payday; XP keeps counting after it.

## Install

Requires **Paper 26.3, Java 25, and Vault or VaultUnlocked**. Payments need a compatible Vault economy provider. EssentialsX is optional. Folia is not supported.

Download the JAR from [Releases](https://github.com/RokeyDoess/JustJobs/releases), put it in `plugins/`, and start the server. When updating, replace the old JustJobs JAR. The plugin creates its settings on first startup.

[Commands](docs/commands.md) · [Configuration](docs/configuration.md) · [Changelog](CHANGELOG.md)

Found a bug or have an idea? Use the [issue forms](https://github.com/RokeyDoess/JustJobs/issues/new/choose). For questions and setup help, head to [Discussions](https://github.com/RokeyDoess/JustJobs/discussions/categories/q-a). [Contributions](.github/CONTRIBUTING.md) are welcome. Please [report security problems privately](.github/SECURITY.md).

Licensed under [GPLv3](LICENSE).
