# Notifications

This page explains how to receive notifications on the **Notification** tab of the [Diffy - Bitbucket PR Review](https://plugins.jetbrains.com/plugin/35502-diffy--bitbucket-pr-review) IntelliJ plugin.

Atlassian does not currently offer a public notification API. Instead, a browser extension reads your Bitbucket Cloud notifications while you are logged in and forwards them to the IntelliJ plugin.

## Install

### Firefox

[Install the extension from Mozilla Add-ons](https://addons.mozilla.org/addon/diffy-bitbucket-pr-review/).

### Chrome

The extension is still in development and is not yet on the Chrome Web Store. Until it is published, load it manually:

1. Open `chrome://extensions`.
2. Turn on **Developer mode** (top right).
3. Click **Load unpacked** and select the `diffy-chrome-ext/` directory of the [Diffy repository](https://github.com/fnunezkanut/diffy/tree/main/diffy-chrome-ext).

## How it works

The Diffy browser extension runs on `bitbucket.org`. It collects your Bitbucket notifications and sends them to the Diffy plugin, which listens on localhost.

The extension syncs notifications in two ways:

* **Manually:** click the **Diffy sync** button in the bottom left corner of any Bitbucket page.
* **Automatically:** every 15 minutes.

