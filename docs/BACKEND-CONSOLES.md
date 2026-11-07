# Consoles for servers behind Velocity (Ember Link 0.1.1)

Install Ember Link 0.1.1 on each Paper or Fabric backend whose console you want in Ember.
It is compatible with the 0.1.0 Velocity plugin: the proxy plugin-message protocol did not change.

In proxied mode, each backend opens its own Ember gateway connection for status, console logs,
local console command reports, and commands sent from Ember. Player chat, joins, deaths,
advancements, and network events still go through Velocity. The backend advertises chat and
events as unavailable, and it ignores Ember chat so messages are not duplicated.

1. Restart the backend with the new jar and run `bonfire status` in **that backend's console**.
   It should show `Connection: connected`, an instance ID, and a pairing tag.
2. In the desired Ember server, add a Game server channel. Find the backend using its pairing
   tag, start a claim, and run the displayed `bonfire link CODE` in **that backend's console**.
3. Set that Game server channel's purpose to **Console**. People with Manage server can read its
   logs and run backend commands there. The Velocity instance can have its own Console channel.

`Proxy confirmed: not yet` is independent of the backend's direct console connection. Velocity's
`bonfire:hello` travels on a real player's backend connection; it may remain "not yet" while no
player has connected. If it stays that way after a player connects through Velocity, inspect
proxy plugin messaging separately.

The backend's `console.exec-enabled` setting still controls whether Ember may run commands.
