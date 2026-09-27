# Sim Management

In-game panel opened with `.sim` (Home / Party / Targets / Sims). It only calls the public API of
PhantomPartyManager, PhantomBuddyManager and PhantomManager - the same commands you could type in party chat.

- Enable: `config/module.ini` -> `Enabled = True`, restart.
- Disable: `Enabled = False`, restart. The server is stock.
- Remove: delete this directory while the server is stopped. No database tables.