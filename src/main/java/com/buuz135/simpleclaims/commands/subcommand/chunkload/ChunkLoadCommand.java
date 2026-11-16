package com.buuz135.simpleclaims.commands.subcommand.chunkload;

import com.buuz135.simpleclaims.chunkload.ChunkLoadManager;
import com.buuz135.simpleclaims.claim.ClaimManager;
import com.buuz135.simpleclaims.commands.CommandMessages;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Aselion fork: {@code /simpleclaims chunkload list|status} (also {@code /sc chunkload}). Shows which claimed chunks are
 * kept ticking: those of every party with a member online within {@code ChunkLoading.ActiveHours}. There is nothing to
 * switch or choose; the former {@code on}, {@code off}, {@code select} and {@code select-clear} are gone.
 */
public class ChunkLoadCommand extends AbstractCommandCollection {

    public static final String ADMIN_PERMISSION = CommandMessages.ADMIN_PERM + "chunkload";
    static final String PLAYER_PERMISSION = CommandMessages.BASE_PERM + "claim";
    static final Message DISABLED = Message.raw("Chunk loading is disabled on this server.").color(Color.RED);

    public ChunkLoadCommand() {
        super("chunkload", "Shows which claimed chunks are kept loaded while nobody is near (if enabled on this server)");
        this.requirePermission(PLAYER_PERMISSION);
        this.addSubCommand(new ListCommand());
        this.addSubCommand(new StatusCommand());
    }

    static Message info(String text) {
        return Message.raw(text).color(Color.YELLOW);
    }

    static Message error(String text) {
        return Message.raw(text).color(Color.RED);
    }

    /** Runs the work off the command thread, or answers that chunk loading is disabled. */
    static CompletableFuture<Void> run(CommandContext context, Consumer<ChunkLoadManager> work) {
        var manager = ChunkLoadManager.get();
        if (manager == null) {
            context.sendMessage(DISABLED);
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> work.accept(manager));
    }

    /** {@code list}: every active party, its kept chunks, and what it costs. Admins only. */
    static class ListCommand extends AbstractAsyncCommand {
        ListCommand() {
            super("list", "Lists the active parties, their loaded chunks and the cost");
            this.requirePermission(ADMIN_PERMISSION);
        }

        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            return run(context, manager -> {
                manager.describeServer().forEach(line -> context.sendMessage(info(line)));
                manager.describeParties().forEach(line -> context.sendMessage(info(line)));
            });
        }
    }

    /** {@code status}: chunk loading of your own party. */
    static class StatusCommand extends AbstractAsyncCommand {
        StatusCommand() {
            super("status", "Shows whether and until when your party's claimed chunks are kept loaded");
            this.requirePermission(PLAYER_PERMISSION);
        }

        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext context) {
            return run(context, manager -> {
                var party = context.sender() instanceof PlayerRef playerRef
                        ? ClaimManager.getInstance().getPartyFromPlayer(playerRef.getUuid()) : null;
                if (party == null) {
                    context.sendMessage(error("You are not in a party."));
                    return;
                }
                context.sendMessage(info(manager.describe(party.getId())));
            });
        }
    }
}
