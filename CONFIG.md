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

Keeps claimed chunks ticking while no player is near, so machines in a claim keep running while the owner is offline.
Off by default. When off it is inert: no keep-alive loop runs, nothing is written to the database, and the
`/sc chunkload` commands only answer that it is disabled. Startup logs `chunk loading: off` or `chunk loading: on (...)`.

```json
"ChunkLoading": {
  "Enabled": false,
  "MaxChunksPerParty": 9,
  "MaxChunksTotal": 64,
  "RequiresAdminApproval": true,
  "ExcludedWorlds": []
}
```

- **Enabled** (Boolean, Default: `false`): Turns the feature on for this server.
- **MaxChunksPerParty** (Integer, Default: `9`): Claimed chunks one party keeps loaded at most.
- **MaxChunksTotal** (Integer, Default: `64`): Chunks the server keeps loaded at most over all parties. Parties are
  served in the order chunk loading was switched on for them.
- **RequiresAdminApproval** (Boolean, Default: `true`): If `true`, only admins (`simpleclaims.admin.chunkload`) switch
  it on or off for a party. If `false`, a party owner may switch it for their own party too.
- **ExcludedWorlds** (String Array, Default: `[]`): Worlds whose claims are never kept loaded, e.g. `["farm"]`. Chunks
  chosen there with `select` are ignored.

Commands (under `/simpleclaims`, alias `/sc`; need `simpleclaims.claim`, `list` needs `simpleclaims.admin.chunkload`):

- `/sc chunkload on <party>` / `/sc chunkload off <party>`: `<party>` is `here` (the claim you stand in), `mine`, the
  owner's name or the party id. Works from the console with a name or id.
- `/sc chunkload list`: kept, ticking and capped chunks per party, the keep-up cost and the world's average tick.
- `/sc chunkload status`: your own party.
- `/sc chunkload select`: the party owner chooses the claimed chunk they stand in (toggle, up to `MaxChunksPerParty`).
  With chunks chosen only those load; with none chosen the party's oldest claims load. `select-clear` forgets the choice.

How: every 500 ms the active timer of each kept chunk column and of all its sections is reset (since Hytale 0.7.0 a
column and each 32-block section tick separately), and unloaded chunks are loaded ticking. State is in the tables
`chunk_loading_parties` and `chunk_loading_selection` of `SimpleClaims.db`, so it survives restarts and handoffs.

## Experimental Settings

Use these with caution as they might still be in development.

- **EXPERIMENTAL-EnableAlloyEntryTesting** (Boolean, Default: `false`): Enables experimental entry testing logic. This
  is a workaround for Hytale's interaction bugs.
- **EXPERIMENTAL-RenderMapInClaimUI** (Boolean, Default: `true`): If `true`, renders a map showing claimed chunks inside
  the claiming GUI.
- **MIGRATION-MigrateOldClaimOverrides** (Boolean, Default: `true`): One-time migration: converts old
  `CLAIM_CHUNK_AMOUNT` to new base+bonus system.
