# Notifications

This page explains how to receive notifications on the "Notification" tab in "[Diffy - Bitbucket PR Review](https://plugins.jetbrains.com/plugin/35502-diffy--bitbucket-pr-review)" IntelliJ plugin.

Since Atlassian don't actually expose a public notification API at this time, 
this plugin gets notification events from "[Diffy - Bitbucket PR Review (Chrome extension)](https://github.com/fnunezkanut/diffy/tree/main/diffy-chrome-ext)" 
which once you are logged into a Bitbucket Cloud account sends notifications to the IntelliJ plugin: 

* When you click "Diffy sync" button in the bottom left of every bitbucket page
* Every 15 minutes on a timer

## How does it work

Diffy Chrome Extension (on bitbucket.org) collects Bitbucket notifications 
and forwards it to Diffy IntelliJ plugin listening on localhost


## How do I install the plugin?
1. Open `chrome://extensions`.
2. Turn on **Developer mode** (top-right).
3. Click **Load unpacked** and select this `diffy-chrome-ext/` directory.

This plugin will be published on Chrome Extensions page soon

### Firefox

A Firefox extension with the same features lives in `diffy-firefox-ext/` (requires Firefox 140 or newer and the Diffy plugin 1.12.0 or newer):

1. Open `about:debugging#/runtime/this-firefox`.
2. Click **Load Temporary Add-on...** and select `diffy-firefox-ext/manifest.json`.
3. In `about:addons` > the extension > **Permissions**, allow access to `bitbucket.org` and `127.0.0.1`.

See [diffy-firefox-ext/README.md](../diffy-firefox-ext/README.md) for details.

This plugin will be published on Mozilla Addons page soon
