# Contributing

Bug reports, ideas, documentation fixes, and code contributions are welcome.

Use the [issue forms](https://github.com/RokeyDoess/JustJobs/issues/new/choose) for bugs and feature requests. Include enough detail to reproduce a bug, especially the Paper version, economy provider, and any changed settings. For setup questions, use [Q&A](https://github.com/RokeyDoess/JustJobs/discussions/categories/q-a). Report exploits through the [security policy](SECURITY.md).

Keep pull requests focused. For a larger change, open an issue to discuss it before spending a lot of time on it. JustJobs should stay small: automatic jobs, clear progress, and reliable rewards.

## Building and testing

Install Java 25, then run `./mvnw verify` from the project root (`.\mvnw.cmd verify` on Windows). This runs the unit tests and builds the plugin in `target/`.

Test gameplay changes on a separate Paper server. Install Vault or VaultUnlocked and a compatible economy provider when testing payments; EssentialsX isn't required. Cover the normal action and likely reward loops. Changes to money or storage also need checks for failed payments, reloads, and restarts. Keep existing player data and custom settings compatible, or explain the migration clearly.

Update the relevant docs when behavior or settings change. Leave server folders, logs, player data, and generated JARs out of commits. Contributions are covered by the project's [GPLv3 license](../LICENSE).
