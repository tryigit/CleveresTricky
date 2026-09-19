# Changelog

## V2.8.4

- **Keybox health at a glance:** Every keybox now shows a clear Valid or Invalid badge with a plain reason (expired, revoked, or failed check), so you always know why a keybox is skipped.
- **Block Invalid Keyboxes:** New setting under Automatic Keybox Check, enabled by default. Turn it off to keep using expired or revoked keyboxes; broken ones stay blocked no matter what.
- **Priority order:** Choose whether RKP or TEE keyboxes are preferred, or keep the default random selection. Saved orders survive app updates and policy edits.
- **Nothing gets deleted:** Invalid keyboxes stay visible in your list with their status instead of disappearing, and the keybox panel is tidier with centered buttons and full text on long-press.
- **Pool control without deleting:** Every stored keybox now has a Disable or Enable action next to Delete. A disabled keybox stays in your list with a clear badge but is never used for attestation, and the choice is remembered in `disabled_keyboxes` across restarts and module updates.
- **Uploads never overwrite:** Pasting or dropping a keybox is always saved as `keybox.xml`, falling back to `keybox2.xml`, `keybox3.xml`, and so on when the name is already taken, so an existing keybox can no longer be replaced silently.
- **More reliable automatic identity:** The Pixel identity refresh now tracks the latest test builds correctly and no longer falls back to outdated profiles.
- **Custom templates that just work:** Saving a custom template works on first use, and custom templates can be applied everywhere built-in ones can.
- **Safer remote servers:** Server connections can no longer be tricked into reaching device-internal or otherwise invalid network addresses, and redirects from a server are rejected instead of followed silently.
- **More trustworthy restores:** Backups are re-checked more strictly during restore, including revocation data and keybox authenticity, so a modified backup cannot grant itself trusted status.
- **Smoother operation:** Background refreshes and the revocation list stay consistent under unlucky timing, and installer safeguards protect existing settings during module updates.
- **Nine languages:** All new screens, settings, and messages are fully translated.
