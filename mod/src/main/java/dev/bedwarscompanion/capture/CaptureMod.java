package dev.bedwarscompanion.capture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.scoreboard.*;
import net.minecraft.util.*;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.nio.file.Path;
import java.util.*;

@Mod(modid = "bedwarscapture", name = "Bed Wars Capture (Diagnostic)", version = "0.1.0-diagnostic",
     clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]", acceptableRemoteVersions = "*")
public class CaptureMod {
    private final Minecraft mc = Minecraft.getMinecraft();
    private volatile DiagnosticWriter writer;
    private boolean islands;
    private boolean connected;
    private Map<String, Object> previousSnapshot;
    private String lastWarning = "";
    private int ticks;
    private long rejectedChat;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new CaptureCommand());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            DiagnosticWriter active = writer;
            if (active != null) active.shutdown();
        }, "bedwars-capture-shutdown"));
    }
    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % 20 != 0 || writer == null) return;
        if (!writer.accepting()) {
            if (!writer.status().equals(lastWarning)) {
                lastWarning = writer.status();
                tell(lastWarning);
            }
            return;
        }
        boolean nowConnected = mc.theWorld != null && mc.getNetHandler() != null;
        if (nowConnected != connected) {
            connected = nowConnected;
            islands = false; // Reconfirm after every disconnect/world gap; no guessed identity continuity.
            previousSnapshot = null;
            writer.emit("connection", map("connected", connected));
        }
        if (connected) snapshot();
    }
    @SubscribeEvent
    public void worldUnloaded(WorldEvent.Unload event) {
        if (!event.world.isRemote || writer == null || !writer.accepting()) return;
        islands = false;
        previousSnapshot = null;
        writer.emit("roster_gate_closed", map("reason", "world-unloaded"));
    }
    private void snapshot() {
        Scoreboard board = mc.theWorld.getScoreboard();
        ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
        // Team-coloured sidebar slots override the global sidebar on some servers.
        if (mc.thePlayer != null) {
            ScorePlayerTeam localTeam = board.getPlayersTeam(mc.thePlayer.getName());
            if (localTeam != null && localTeam.getChatFormat().getColorIndex() >= 0) {
                ScoreObjective coloured = board.getObjectiveInDisplaySlot(3 + localTeam.getChatFormat().getColorIndex());
                if (coloured != null) objective = coloured;
            }
        }
        if (objective == null || !MessageFilter.plain(objective.getDisplayName()).contains("BED WARS")) {
            if (islands) {
                islands = false;
                writer.emit("roster_gate_closed", map("reason", "bedwars-sidebar-lost"));
            }
            previousSnapshot = null;
            return;
        }
        Map<String, Object> data = map("sidebar_title", clip(objective.getDisplayName()));
        List<String> lines = new ArrayList<>();
        for (Score score : board.getSortedScores(objective)) {
            String name = score.getPlayerName();
            if (name == null || name.startsWith("#")) continue;
            lines.add(clip(ScorePlayerTeam.formatPlayerName(board.getPlayersTeam(name), name)));
            if (lines.size() == 32) break;
        }
        data.put("sidebar_lines", lines);
        data.put("islands_manually_confirmed", islands);
        if (islands) {
            List<Map<String, Object>> roster = new ArrayList<>();
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (roster.size() >= 100) break;
                Map<String, Object> person = map("visible_name", clip(info.getGameProfile().getName()));
                person.put("supplied_uuid", info.getGameProfile().getId() == null ? null : info.getGameProfile().getId().toString());
                person.put("identity_status", "unresolved");
                person.put("display_name", info.getDisplayName() == null ? null : clip(info.getDisplayName().getFormattedText()));
                person.put("game_type", info.getGameType() == null ? null : info.getGameType().name());
                ScorePlayerTeam team = board.getPlayersTeam(info.getGameProfile().getName());
                if (team != null) {
                    person.put("team_registered_name", clip(team.getRegisteredName()));
                    person.put("team_prefix", clip(team.getColorPrefix()));
                    person.put("team_suffix", clip(team.getColorSuffix()));
                    person.put("team_format", team.getChatFormat().toString());
                }
                roster.add(person);
            }
            roster.sort(Comparator.comparing(p -> String.valueOf(p.get("supplied_uuid"))));
            data.put("participants", roster);
        }
        if (!data.equals(previousSnapshot)) {
            previousSnapshot = data;
            writer.emit("snapshot", data);
        }
    }
    @SubscribeEvent
    public void chat(ClientChatReceivedEvent event) {
        if (writer == null || !writer.accepting() || event.type == 2) return;
        // Packet type 0 is not proof of system origin on legacy servers.
        String text = event.message.getFormattedText();
        if (MessageFilter.candidate(text) && !hasInteractiveChat(event.message)) {
            Map<String, Object> payload = map("formatted_text", text);
            payload.put("packet_type", event.type);
            payload.put("classification", "unvalidated-system-candidate");
            writer.emit("message_candidate", payload);
        } else rejectedChat++;
    }
    private boolean hasInteractiveChat(IChatComponent component) {
        if (component.getChatStyle().getChatClickEvent() != null) return true;
        for (IChatComponent child : component.getSiblings()) if (hasInteractiveChat(child)) return true;
        return false;
    }
    private static String clip(String value) {
        return value == null ? null : value.substring(0, Math.min(value.length(), 256));
    }
    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put(key, value); return result;
    }
    private void tell(String text) {
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText("[BW Capture] " + text));
    }
    private class CaptureCommand extends CommandBase {
        @Override public String getCommandName() { return "bwcapture"; }
        @Override public String getCommandUsage(ICommandSender sender) {
            return "/bwcapture start|islands|pause|status|stop|mark <spawn|bed|kill|final|spectating|rejoin|end>";
        }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }
        @Override public void processCommand(ICommandSender sender, String[] args) {
            if (args.length == 0) { tell(getCommandUsage(sender)); return; }
            switch (args[0]) {
                case "start":
                    if (writer != null && !writer.finished()) { tell("A capture is active or draining. " + writer.status()); return; }
                    Path path = mc.mcDataDir.toPath().resolve("bedwars-companion/captures");
                    writer = new DiagnosticWriter(path);
                    islands = false; connected = mc.theWorld != null; previousSnapshot = null; lastWarning = ""; rejectedChat = 0;
                    tell("Diagnostic capture started. On your island, run /bwcapture islands. No account lookups.");
                    break;
                case "islands":
                    if (!active()) return;
                    islands = true; previousSnapshot = null;
                    writer.emit("manual_marker", map("label", "islands"));
                    tell("Roster capture enabled by your island confirmation.");
                    break;
                case "pause":
                    if (!active()) return;
                    islands = false; previousSnapshot = null;
                    writer.emit("manual_marker", map("label", "roster-paused"));
                    tell("Roster capture paused; system candidates and sidebar capture continue.");
                    break;
                case "stop":
                    if (!active()) return;
                    writer.emit("diagnostic_counts", map("rejected_chat", rejectedChat));
                    writer.stop(); islands = false;
                    tell("Stopping asynchronously. " + writer.file());
                    break;
                case "status":
                    tell(writer == null ? "Capture off." : writer.status() + "; roster=" + islands + "; " + writer.file());
                    break;
                case "mark":
                    if (!active()) return;
                    if (args.length != 2 || !Arrays.asList("spawn", "bed", "kill", "final", "spectating", "rejoin", "end").contains(args[1])) {
                        tell(getCommandUsage(sender)); return;
                    }
                    writer.emit("manual_marker", map("label", args[1]));
                    tell("Marked " + args[1]);
                    break;
                default: tell(getCommandUsage(sender));
            }
        }
        private boolean active() {
            if (writer == null || !writer.accepting()) { tell("Start a capture first."); return false; }
            return true;
        }
    }
}
