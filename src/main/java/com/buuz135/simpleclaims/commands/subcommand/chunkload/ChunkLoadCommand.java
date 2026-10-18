package com.buuz135.simpleclaims.commands.subcommand.chunkload;

import com.buuz135.simpleclaims.chunkload.ChunkLoadManager;
import com.buuz135.simpleclaims.claim.ClaimManager;
import com.buuz135.simpleclaims.claim.party.PartyInfo;
import com.buuz135.simpleclaims.commands.CommandMessages;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.awt.Color;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Aselion fork: {@code /simpleclaims chunkload ...} (also {@code /sc chunkload}). Keeps a party's claimed chunks ticking
 * while nobody is near. Inert unless {@code ChunkLoading.Enabled} is set in the config.
 */
public class ChunkLoadCommand extends AbstractCommandCollection {

    public static final String ADMIN_PERMISSION = CommandMessages.ADMIN_PERM + "chunkload";
    static final String PLAYER_PERMISSION = CommandMessages.BASE_PERM + "claim";
    static final Message DISABLED = Message.raw("Chunk loading is disabled on this server.").color(Color.RED);

    public ChunkLoadCommand() {
        super("chunkload", "Keeps claimed chunks loaded while nobody is near (if enabled on this server)");
        this.requirePermission(PLAYER_PERMISSION);
        this.addSubCommand(new Switch(true));
        this.addSubCommand(new Switch(false));
        this.addSubCommand(new ListCommand());
        this.addSubCommand(new StatusCommand());
        this.addSubCommand(new SelectCommand());
        this.addSubCommand(new SelectClearCommand());
    }

    static Message info(String text) {
        return Message.raw(text).color(Color.YELLOW);
    }

    static Message error(String text) {
        return Message.raw(text).color(Color.RED);
    }

    /** Where a player stands: world and chunk. Read on the player's world thread. */
    record Position(World world, int chunkX, int chunkZ) {
    }

    /**
     * Runs the work on the sender's world thread if the sender is a player in a world (position known), otherwise
     * right away without a position (console).
     */
    static CompletableFuture<Void> run(CommandContext context, java.util.function.BiConsumer<CommandSender, Position> work) {
        CommandSender sender = context.sender();
        if (ChunkLoadManager.get() == null) {
            context.sendMessage(DISABLED);
            return CompletableFuture.completedFuture(null);
        }
        if (sender instanceof PlayerRef playerRef) {
            var ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                var store = ref.getStore();
                World world = store.getExternalData().getWorld();
                return CompletableFuture.runAsync(() -> {
                    var pos = playerRef.getTransform().getPosition();
                    work.accept(sender, new Position(world, ChunkUtil.chunkCoordinate((int) Math.floor(pos.x())), ChunkUtil.chunkCoordinate((int) Math.floor(pos.z()))));
                }, world);
            }
        }
        return CompletableFuture.runAsync(() -> work.accept(sender, null));
    }

    /**
     * A party by "here" (the claim you stand in), "mine" (your party), its id, its owner's name or its name.
     */
    @Nullable
    static PartyInfo resolveParty(String arg, CommandSender sender, @Nullable Position position) {
        var claims = ClaimManager.getInstance();
        if (arg.equalsIgnoreCase("here")) {
            if (position == null) return null;
            var chunk = claims.getChunk(position.world().getName(), position.chunkX(), position.chunkZ());
            return chunk == null ? null : claims.getPartyById(chunk.getPartyOwner());
        }
        if (arg.equalsIgnoreCase("mine")) {
            return sender instanceof PlayerRef playerRef ? claims.getPartyFromPlayer(playerRef.getUuid()) : null;
        }
        try {
            var party = claims.getPartyById(UUID.fromString(arg));
            if (party != null) return party;
        } catch (IllegalArgumentException ignored) {
        }
        for (var party : claims.getParties().values()) {
            var owner = party.getOwner() == null ? null : claims.getPlayerNameTracker().getPlayerName(party.getOwner());
            if (owner != null && owner.equalsIgnoreCase(arg)) return party;
        }
        for (var party : claims.getParties().values()) {
            if (party.getName() != null && party.getName().equalsIgnoreCase(arg)) return party;
        }
        return null;
    }

    static String senderName(CommandSender sender) {
        return sender instanceof PlayerRef playerRef ? playerRef.getUsername() : "console";
    }

    /** {@code on <party>} and {@code off <party>}. */
    static class Switch extends AbstractAsyncCommand {
        private final boolean on;
        private final RequiredArg<String> party;

        Switch(boolean on) {
            super(on ? "on" : "off", on
                    ? "Keeps a party's claimed chunks loaded: here, mine, an owner name or a party id"
                    : "Stops keeping a party's claimed chunks loaded: here, mine, an owner name or a party id");
            this.on = on;
            this.party = this.withRequiredArg("party", "here, mine, owner name or party id", ArgTypes.STRING);
            this.requirePermission(PLAYER_PERMISSION);
        }

        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            String arg = party.get(context);
            return run(context, (sender, position) -> {
                var manager = ChunkLoadManager.get();
                if (manager == null) {
                    sender.sendMessage(DISABLED);
                    return;
                }
                var target = resolveParty(arg, sender, position);
                if (target == null) {
                    sender.sendMessage(error("No party found for '" + arg + "'."));
                    return;
                }
                boolean admin = sender.hasPermission(ADMIN_PERMISSION);
                boolean owner = sender instanceof PlayerRef playerRef && target.isOwner(playerRef.getUuid());
                if (!admin && (manager.getConfig().isRequiresAdminApproval() || !owner)) {
                    sender.sendMessage(error(manager.getConfig().isRequiresAdminApproval()
                            ? "Only an admin can switch chunk loading on or off for a party."
                            : "Only the party owner or an admin can switch chunk loading on or off."));
                    return;
                }
                boolean changed = manager.setEnabled(target.getId(), on, senderName(sender));
                sender.sendMessage(info("Chunk loading " + (on ? "on" : "off") + " for party " + ChunkLoadManager.partyLabel(target.getId())
                        + (changed ? "." : " (it already was).")
                        + (on ? " Up to " + manager.getConfig().getMaxChunksPerParty() + " claimed chunks stay loaded." : "")));
            });
        }
    }

    /** {@code list}: every party with chunk loading on, and what it costs. Admins only. */
    static class ListCommand extends AbstractAsyncCommand {
        ListCommand() {
            super("list", "Lists the parties with chunk loading on, their loaded chunks and the cost");
            this.requirePermission(ADMIN_PERMISSION);
        }

        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            return run(context, (sender, position) -> {
                var manager = ChunkLoadManager.get();
                if (manager == null) {
                    sender.sendMessage(DISABLED);
                    return;
                }
                manager.describeServer().forEach(line -> sender.sendMessage(info(line)));
                manager.describeParties().forEach(line -> sender.sendMessage(info(line)));
            });
        }
    }

    /** {@code status}: chunk loading of your own party. */
    static class StatusCommand extends AbstractAsyncCommand {
        StatusCommand() {
            super("status", "Shows whether your party's claimed chunks are kept loaded");
            this.requirePermission(PLAYER_PERMISSION);
        }

        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            return run(context, (sender, position) -> {
                var manager = ChunkLoadManager.get();
                if (manager == null) {
                    sender.sendMessage(DISABLED);
                    return;
                }
                var party = resolveParty("mine", sender, position);
                if (party == null) {
                    sender.sendMessage(error("You are not in a party."));
                    return;
                }
                sender.sendMessage(info(manager.describe(party.getId())));
            });
        }
    }

    /** {@code select}: the party owner adds the claimed chunk they stand in to the chunks kept loaded, or removes it. */
    static class SelectCommand extends AbstractAsyncCommand {
        SelectCommand() {
            super("select", "Chooses the claimed chunk you stand in to be kept loaded, or unchooses it (party owner)");
            this.requirePermission(PLAYER_PERMISSION);
        }

        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            return run(context, (sender, position) -> {
                var manager = ChunkLoadManager.get();
                if (manager == null) {
                    sender.sendMessage(DISABLED);
                    return;
                }
                if (!(sender instanceof PlayerRef playerRef) || position == null) {
                    sender.sendMessage(error("Only a player in a world can choose chunks."));
                    return;
                }
                var party = ClaimManager.getInstance().getPartyFromPlayer(playerRef.getUuid());
                if (party == null || !party.isOwner(playerRef.getUuid())) {
                    sender.sendMessage(error("Only the party owner can choose which chunks stay loaded."));
                    return;
                }
                var dimension = position.world().getName();
                var chunk = ClaimManager.getInstance().getChunk(dimension, position.chunkX(), position.chunkZ());
                if (chunk == null || !chunk.getPartyOwner().equals(party.getId())) {
                    sender.sendMessage(error("Stand in a chunk your party has claimed."));
                    return;
                }
                switch (manager.toggleSelected(party.getId(), dimension, position.chunkX(), position.chunkZ())) {
                    case SELECTED -> sender.sendMessage(info("This chunk is now chosen to stay loaded ("
                            + manager.getSelection(party.getId()).size() + "/" + manager.getConfig().getMaxChunksPerParty() + ")."
                            + (manager.isEnabledFor(party.getId()) ? "" : " Chunk loading is not switched on for your party yet.")));
                    case UNSELECTED -> sender.sendMessage(info("This chunk is no longer chosen ("
                            + manager.getSelection(party.getId()).size() + "/" + manager.getConfig().getMaxChunksPerParty() + ")."
                            + (manager.getSelection(party.getId()).isEmpty() ? " With none chosen, your oldest claims stay loaded." : "")));
                    case LIMIT_REACHED -> sender.sendMessage(error("You already chose " + manager.getConfig().getMaxChunksPerParty()
                            + " chunks. Unchoose one first, or use /sc chunkload select-clear."));
                }
            });
        }
    }

    /** {@code select-clear}: back to the default, the party's oldest claims. */
    static class SelectClearCommand extends AbstractAsyncCommand {
        SelectClearCommand() {
            super("select-clear", "Forgets your chosen chunks; your oldest claims stay loaded again (party owner)");
            this.requirePermission(PLAYER_PERMISSION);
        }

        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            return run(context, (sender, position) -> {
                var manager = ChunkLoadManager.get();
                if (manager == null) {
                    sender.sendMessage(DISABLED);
                    return;
                }
                var party = resolveParty("mine", sender, position);
                if (party == null || !(sender instanceof PlayerRef playerRef) || !party.isOwner(playerRef.getUuid())) {
                    sender.sendMessage(error("Only the party owner can choose which chunks stay loaded."));
                    return;
                }
                manager.clearSelection(party.getId());
                sender.sendMessage(info("Chosen chunks cleared; your oldest claims stay loaded."));
            });
        }
    }
}
