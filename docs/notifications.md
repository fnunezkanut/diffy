# Notifications

This page explains how to receive notifications on the **Notification** tab of the [Diffy - Bitbucket PR Review](https://plugins.jetbrains.com/plugin/35502-diffy--bitbucket-pr-review) IntelliJ plugin.

Atlassian does not currently offer a public notification API. Instead, a browser extension reads your Bitbucket Cloud notifications while you are logged in and forwards them to the IntelliJ plugin.

## Install

### Firefox

[Install the extension from Mozilla Add-ons](https://addons.mozilla.org/addon/diffy-bitbucket-pr-review/).

### Chrome

[Install the extension from Chrome Web Store](https://chromewebstore.google.com/detail/diffy-bitbucket-pr-review/pkeoedpgepmldoikeedkchmhkgjpelln).

## How it works

The Diffy browser extension runs on `bitbucket.org`. It collects your Bitbucket notifications and sends them to the Diffy plugin, which listens on localhost.

The extension syncs notifications in two ways:

* **Manually:** click the **Diffy sync** button in the bottom left corner of any Bitbucket page.
* **Automatically:** every 15 minutes.

### Why not use a notifications API?
Atlassian do not provide a public Notifications for Bitbucket at this time :(


## Filters

The toolbar of the Notifications tab has two remembered checkboxes: **Filter out bots** hides notifications from bots, and **Show only Unread** lists only unread notifications. The unread counter in the tab title ignores both filters.

