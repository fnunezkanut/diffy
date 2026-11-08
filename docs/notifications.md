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

This plugin will be published on Chrome Marketplace soon