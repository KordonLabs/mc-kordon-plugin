package com.kordonlabs.plugin.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.kordonlabs.plugin.common.command.CommandService;
import com.kordonlabs.plugin.common.platform.Permissions;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import com.kordonlabs.plugin.velocity.VelocitySender;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /kordon} on Velocity, registered without aliases. The command logic lives in the core's
 * {@link CommandService}; this class only adapts the command source.
 */
public final class VelocityKordonCommand implements SimpleCommand {

    private final Supplier<CommandService> commands;
    private final Messages messages;
    private final ThrottledLog log;

    /** {@code commands} supplies the running core's service, or null once the core has stopped. */
    public VelocityKordonCommand(Supplier<CommandService> commands, Messages messages, ThrottledLog log) {
        this.commands = commands;
        this.messages = messages;
        this.log = log;
    }

    @Override
    public void execute(Invocation invocation) {
        VelocitySender sender = new VelocitySender(invocation.source());
        try {
            if (!sender.hasPermission(Permissions.COMMAND)) {
                sender.send(messages.chat(MessageKey.CMD_NO_PERMISSION, "perm", Permissions.COMMAND));
                return;
            }
            CommandService service = commands.get();
            if (service == null) { // the core is stopping, but the sender still gets a reply
                sender.send(messages.chat(MessageKey.CMD_ERR_NETWORK));
                return;
            }
            service.execute(sender, invocation.arguments());
        } catch (Throwable t) {
            log.warn("command.kordon", "Kordon: /kordon failed: " + t);
            try {
                sender.send(messages.chat(MessageKey.CMD_ERR_NETWORK));
            } catch (Throwable ignored) {
                // the source is gone
            }
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        try {
            VelocitySender sender = new VelocitySender(invocation.source());
            if (!sender.hasPermission(Permissions.COMMAND)) return List.of();
            CommandService service = commands.get();
            if (service == null) return List.of();
            List<String> out = service.complete(sender, invocation.arguments());
            return out == null ? List.of() : out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * Always true: if it were false, Velocity would treat {@code /kordon} as unknown and forward the raw chat line to
     * the backend. Kordon answers with its own no-permission message instead.
     */
    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
