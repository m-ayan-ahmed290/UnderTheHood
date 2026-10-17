# Account connection fixes in 0.1.1

Account names such as `A.A OneDrive` and `M. Ayan Drive` now work on Android and desktop. Leading/trailing whitespace is trimmed; spaces, dots, @, +, Unicode letters/numbers, underscores and hyphens are supported. Invalid characters produce a clear inline explanation.

Rclone 1.75.2 asks whether to continue with shared OAuth credentials, defaulting to no. The earlier wrapper took the default, so setup could exit without opening a browser. Blank custom OAuth fields now explicitly select the shared credentials, as the connection form describes. Providers may still reject retired shared credentials; the app does not bypass provider restrictions.

Both clients now use Rclone's noninteractive configuration protocol, display additional provider choices, retain progress/errors in the dialog, and provide an explicit browser button. Cancel stops the authorization process. Retrying an unfinished OAuth account reuses its name without overwriting an already authorized account. A completed configuration is checked before it is reported as connected.

Regression coverage includes the reported names, invalid names, Google Drive/OneDrive authorization initiation, cancellation/retry, browser form behavior, provider question/answer continuation, and an Android Compose UI test that intercepts the browser intent. Full sign-in to live provider accounts still requires the user's browser session and is not claimed by these tests.

These previews use development signing. If Android reports a signature conflict, export local metadata before uninstalling the old preview, then install 0.1.1. On Windows, close all running UnderTheHood windows before installing the update so the old companion is not left running.
