package com.kordonlabs.plugin.bungee.command;

import com.kordonlabs.plugin.bungee.BungeeSender;
import com.kordonlabs.plugin.common.command.CommandService;
import com.kordonlabs.plugin.common.platform.Permissions;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.util.List;
import java.util.function.Supplier;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

/**
 * The {@code /kordon} command, registered with the permission {@code kordon.command} and no aliases (to avoid clashing
 * with XCord's {@code /xcord}). Because the proxy checks the permission itself, a sender without it gets
 * {@code cmd.no_permission} and the command is not forwarded to the backend. Everything else lives in the core's
 * {@link CommandService}; this class only adapts the sender.
 */
public final class BungeeKordonCommand extends Command implements TabExecutor {

    private final Supplier<CommandService> commands;
    private final Messages messages;
    private final ThrottledLog log;

    /** {@code commands} supplies the running core's service, or null once the core has stopped. */
    public BungeeKordonCommand(Supplier<CommandService> commands, Messages messages, ThrottledLog log) {
        super("kordon", Permissions.COMMAND);
        this.commands = commands;
        this.messages = messages;
        this.log = log;
        try {
            setPermissionMessage(messages.chat(MessageKey.CMD_NO_PERMISSION, "perm", Permissions.COMMAND));
        } catch (LinkageError olderFork) {
            // Some old forks lack the setter; the proxy's own no_permission text is shown instead.
        }
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        BungeeSender wrapped = new BungeeSender(sender);
        try {
            CommandService service = commands.get();
            if (service == null) { // the core is stopping, but the sender still gets a reply
                wrapped.send(messages.chat(MessageKey.CMD_ERR_NETWORK));
                return;
            }
            service.execute(wrapped, args);
        } catch (Throwable t) {
            log.warn("command.kordon", "Kordon: /kordon failed: " + t);
            try {
                wrapped.send(messages.chat(MessageKey.CMD_ERR_NETWORK));
            } catch (Throwable ignored) {
                // the sender is gone
            }
        }
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        try {
            CommandService service = commands.get();
            return service == null ? List.of() : service.complete(new BungeeSender(sender), args);
        } catch (Throwable t) {
            return List.of();
        }
    }
}
