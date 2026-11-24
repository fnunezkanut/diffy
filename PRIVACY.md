# Privacy Policy

Last updated: November 17, 2026

Diffy (the IntelliJ IDEA plugin and its companion Chrome and Firefox browser extensions) does not collect, transmit, or share any personal information or usage data. There is no analytics, telemetry, or tracking, and the developer operates no server that receives your data.

## IntelliJ plugin

- The plugin talks only to the Bitbucket Cloud API, directly from your IDE, to show your pull requests.
- Your Bitbucket OAuth tokens are stored in the IntelliJ Platform Password Safe (the IDE's own encrypted store). They are never written to plain-text files or logs, and they are never sent anywhere except to Bitbucket.

## Browser extensions

The extensions have two purposes only:

1. Open Bitbucket pull requests in the Diffy plugin in IntelliJ IDEA.
2. Sync Bitbucket notifications to the plugin, because Bitbucket does not offer a notifications API.

The extensions do not read or store your credentials. They do not send data to the developer or to any third party. Notification data goes from the Bitbucket page you have open to the plugin running on your own machine.

## Contact

Questions can be raised as an issue in the project repository.
