# Sheska
A bot for a bunch of online weebs. This bot is meant to be used by a manga club so we can store the titles we're interested in and filter them quickly according to our needs, among other tasks.

This project is both incomplete and in development, and it's not intended to have a public instance at any point (we would have to migrate from sqlite and get a proper server!) so you need to host your own.

Environment variables:

| Identifier                      | Description                                                            |
| ------------------------------- | ---------------------------------------------------------------------- |
| `KORD_TOKEN`                    | Discord bot secret token                                               |
| `KORD_WEEB_ROLE`                | Snowflake of the role allowed to add and edit manga entries            |
| `KORD_WEEB_SERVER`              | Snowflake of the Discord server the bot it's intended to work in       |
| `WEBPAGE`                       | URL of the front end website where images are stored                   |
| `MANGA_COVERS_URL_SUBDIRECTORY` | Path of the URL where images can be found (e.g.: `/static/manga`)      |
| `MANGA_IMAGE_DIRECTORY`         | Path where images should be saved to (e.g. `/home/myuser/mangacovers`) |
| `SQLITE_FILE_PATH`              | Path to the sqlite file                                                |
| `DISCORD_HELPER_ROLE`           | Role allowed to use some advanced features                             |
| `DISCORD_WEBHOOK`               | Webhook URL to send Discord announcements                              |
| `DISCORD_LOGGER_CHANNEL`        | Channel id to be used as a log when errors arise or other information  |
