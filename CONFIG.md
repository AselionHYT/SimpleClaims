# Simple Claims Configuration

This document explains all the configuration options available in the `SimpleClaims` mod.

## Default Party Settings

These settings define the initial permissions and limits for newly created parties.

- **DefaultPartyClaimsAmount** (Integer, Default: `25`): The maximum number of chunks a party can claim by default.
- **ScaleClaimLimitByMembers** (Boolean, Default: `true`): If `true`, the maximum number of chunks a party can claim
  will be scaled by the number of members in the party.
- **MaxAddChunkAmount** (Integer, Default: `100`): The maximum amount of chunks a party can have when using the
  `add-chunk-amount` command or gain by playtime.
- **ClaimChunkGainInMinutes** (Integer, Default: `-1`): How often (in minutes) a player will gain another claim chunk by
  play time. Use `-1` to disable this feature.
- **MaxPartyMembers** (Integer, Default: `-1`): The maximum number of members a party can have. Use `-1` for no limit.
- **MaxPartyAllies** (Integer, Default: `-1`): The maximum number of allies (both players and other parties) a party can
  have. Use `-1` for no limit.
- **DefaultPartyBlockPlaceEnabled** (Boolean, Default: `false`): Whether block placement is allowed for non-members in
  claimed chunks by default.
- **DefaultPartyBlockBreakEnabled** (Boolean, Default: `false`): Whether block breaking is allowed for non-members in
  claimed chunks by default.
- **DefaultPartyBlockInteractEnabled** (Boolean, Default: `false`): Whether general block interaction is allowed for
  non-members in claimed chunks by default.
- **DefaultPartyPVPEnabled** (Boolean, Default: `false`): Whether PVP is enabled within claimed chunks by default.
- **DefaultPartyFriendlyFireEnabled** (Boolean, Default: `false`): Whether PVP between members of the same party is
  enabled by default.
- **DefaultPartyAllowEntry** (Boolean, Default: `true`): Whether non-members are allowed to enter claimed chunks by
  default.
- **DefaultPartyInteractChest** (Boolean, Default: `false`): Whether non-members can interact with chests in claimed
  chunks by default.
- **DefaultPartyInteractDoor** (Boolean, Default: `false`): Whether non-members can interact with doors in claimed
  chunks by default.
- **DefaultPartyInteractBench** (Boolean, Default: `false`): Whether non-members can interact with benches in claimed
  chunks by default.
- **DefaultPartyInteractChair** (Boolean, Default: `false`): Whether non-members can interact with chairs in claimed
  chunks by default.
- **DefaultPartyTamedDamageEnabled** (Boolean, Default: `false`): Whether damage to tamed entities is allowed for
  non-members
  in claimed chunks by default.
- **DefaultPartyInteractPortal** (Boolean, Default: `false`): Whether non-members can interact with portals in claimed
  chunks by default.

## Permission Settings (Allow Changes)

These settings control whether party owners are allowed to change specific permissions in their own party settings.

- **AllowPartyPVPSettingChanges** (Boolean, Default: `true`): If `true`, party owners can toggle PVP settings for their
  claims.
- **AllowPartyFriendlyFireSettingChanges** (Boolean, Default: `true`): If `true`, party owners can toggle friendly fire
  settings for their party.
- **AllowPartyPlaceBlockSettingChanges** (Boolean, Default: `true`): If `true`, party owners can toggle block placement
  permissions.
- **AllowPartyBreakBlockSettingChanges** (Boolean, Default: `true`): If `true`, party owners can toggle block breaking
  permissions.
- **AllowPartyInteractBlockSettingChanges** (Boolean, Default: `true`): If `true`, party owners can toggle general block
  interaction permissions.
- **AllowPartyAllowEntrySettingChanges** (Boolean, Default: `true`): If `true`, party owners can toggle entry
  permissions.
- **AllowPartyInteractChestSettingChanges** (Boolean, Default: `true`): If `true`, party owners can toggle chest
  interaction permissions.
- **AllowPartyInteractDoorSettingChanges** (Boolean, Default: `true`): If `true`, party owners can toggle door
  interaction permissions.
- **AllowPartyInteractBenchSettingChanges** (Boolean, Default: `true`): If `true`, party owners can toggle bench
  interaction permissions.
- **AllowPartyInteractChairSettingChanges** (Boolean, Default: `true`): If `true`, party owners can toggle chair
  interaction permissions.
- **AllowPartyInteractPortalSettingChanges** (Boolean, Default: `true`): If `true`, party owners can toggle portal
  interaction permissions.
- **AllowPartyTamedDamageSettingChanges** (Boolean, Default: `true`): If `true`, party owners can toggle tamed entity
  damage
  permissions.

## Command Settings

These settings define the aliases for the main commands of the mod.

- **PartyCommandAliases** (String Array, Default: `["scp", "sc-party", "party"]`): The aliases for the party command.
- **ClaimCommandAliases** (String Array, Default: `["sc", "sc-chunks", "scc", "chunk", "chunks", "claim", "claims"]`):
  The aliases for the claim command.

## World & Protection Settings

Global settings that affect claiming and protection across the server.

- **WorldNameBlacklistForClaiming** (String Array, Default: `[]`): A list of world names where claiming chunks is disabled.
- **WorldClaimLimits** (Object, Default: `{}`, Aselion fork): Worlds with a fixed claim budget per party, e.g.
  `{"farm": 4}`. Claims in a listed world count only against that number, not against the party's normal budget, and
  the number does not grow with `ClaimChunkGainInMinutes`, bonus chunks or member count. Claims in other worlds ignore
  claims in listed worlds. The claim map shows the count and budget of the world it is opened in. A negative value
  allows no claims. Empty keeps one budget for all worlds.
- **FullWorldProtection** (String Array, Default: `[]`): A list of world names where the entire world is protected as if
  it were claimed.
- **TitleTopClaimTitleText** (String, Default: `"Simple Claims"`): The text displayed at the top of the claim UI.
- **WildernessName** (String, Default: `"Wilderness"`): The text displayed when a player is in an unclaimed area.
- **CreativeModeBypassProtection** (Boolean, Default: `false`): If `true`, players in Creative Mode will bypass all
  claim protections.
- **BlocksThatIgnoreInteractRestrictions** (String Array, Default: `["gravestone"]`): A list of block IDs that can
  always be interacted with, even in claimed chunks where interactions are otherwise restricted.
- **TamedEntityRoleIdentifiers** (String Array, Default: `["Tamed_"]`): A list of NPC role name identifiers that
  identify an entity as tamed. This is used for protecting tamed animals from damage. The check is a "contains" check.
- **EnableAdjacentChunkRestriction** (Boolean, Default: `false`): If `true`, players can only claim chunks that are
  adjacent to their existing claims.
- **EnablePerimeterReservation** (Boolean, Default: `false`): If `true`, a perimeter around claimed chunks will be
  reserved, preventing others from claiming too close.
- **PartyInactivityHours** (Integer, Default: `-1`): The number of hours a party can be inactive before it is
  automatically disbanded. Use `-1` to disable this feature. All members of the party must be inactive for this to
  trigger.

## Visual & Map Settings

Settings related to the user interface and visual feedback.

- **EnableParticleBorders** (Boolean, Default: `true`): If `true`, particles will be used to show the boundaries of
  claimed chunks.
- **ForceSimpleClaimsChunkWorldMap** (Boolean, Default: `true`): If `true`, forces the use of the Simple Claims chunk
  map.
- **RenderClaimNamesOnWorldMap** (Boolean, Default: `false`): If `true`, the name of the party that owns a chunk and a
  colored border will be rendered directly on the world map.
- **ShowPerimeterReservationOnTheMap** (Boolean, Default: `false`): If `true`, the reserved perimeter will be visible on
  the world map.
- **NotifyPartyChatToggling** (Boolean, Default: `true`): If `true`, all party members will be notified when someone
  toggles their party chat.

## Chunk Loading (Aselion fork)

Keeps claimed chunks ticking while no player is near, so machines in a claim keep running while the owners are offline.
Every claimed chunk of a party stays loaded as long as at least one party member was online within `ActiveHours`.
There is no per-party or server-wide budget and nothing to switch on per party: the claim limits per world
(`WorldClaimLimits`, the normal party budget) are the budget.

Off by default. When off it is inert: no keep-alive loop runs, nothing is written to the database, and the
`/sc chunkload` commands only answer that it is disabled. Startup logs `chunk loading: off` or `chunk loading: on (...)`.

```json
"ChunkLoading": {
  "Enabled": false,
  "ActiveHours": 48,
  "WarnAboveLoadedChunks": 500,
  "ExcludedWorlds": []
}
```

- **Enabled** (Boolean, Default: `false`): Turns the feature on for this server.
- **ActiveHours** (Integer, Default: `48`): A party's claims stay loaded while a member was online within this many
  hours. A member online now keeps them loaded.
- **WarnAboveLoadedChunks** (Integer, Default: `500`): While more claimed chunks than this are kept loaded, a WARN with
  the counts is logged (on crossing, then at most hourly). Nothing is cut off. `-1` disables the warning.
- **ExcludedWorlds** (String Array, Default: `[]`): Worlds whose claims are never kept loaded.

Commands (under `/simpleclaims`, alias `/sc`; need `simpleclaims.claim`, `list` needs `simpleclaims.admin.chunkload`):

- `/sc chunkload list`: kept and ticking chunks per world and per active party, the keep-up cost and the world's
  average tick.
- `/sc chunkload status`: your own party, and until when its claims stay loaded.

How: the last time a member of each party was online is kept in the table `chunk_loading_activity` of
`SimpleClaims.db` (written on join and leave, and every 5 minutes while a member is online), so it survives restarts and
handoffs; for a party without a row the members' last-seen times from the name cache count. Activity and claims are
re-evaluated every 5 seconds and right after a join, leave, claim or unclaim. Claims in a world that is not loaded
(not created yet, or removed by a farm reset) are skipped. Every 500 ms the active timer of each kept chunk column and
of all its sections is reset (since Hytale 0.7.0 a column and each 32-block section tick separately), and unloaded
chunks are loaded ticking. Chunks of a party that turned inactive are no longer kept up and unload like any other
chunk. Each log line starts with `chunk loading:`; a line with the total and the count per world is written whenever
it changes.

Up to `1.0.39-aselion.5`, chunk loading needed an admin switch per party (`/sc chunkload on|off`), a chunk choice
(`select`, `select-clear`) and the limits `MaxChunksPerParty`, `MaxChunksTotal`, `RequiresAdminApproval`. These are gone;
the old keys are ignored and the old tables `chunk_loading_parties` and `chunk_loading_selection` are no longer read.

## Experimental Settings

Use these with caution as they might still be in development.

- **EXPERIMENTAL-EnableAlloyEntryTesting** (Boolean, Default: `false`): Enables experimental entry testing logic. This
  is a workaround for Hytale's interaction bugs.
- **EXPERIMENTAL-RenderMapInClaimUI** (Boolean, Default: `true`): If `true`, renders a map showing claimed chunks inside
  the claiming GUI.
- **MIGRATION-MigrateOldClaimOverrides** (Boolean, Default: `true`): One-time migration: converts old
  `CLAIM_CHUNK_AMOUNT` to new base+bonus system.
