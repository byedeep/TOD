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
    private String captureMode = "unspecified";
    // Arm experimental sidebar detection for every client run. It still waits for
    // recognised Bed Wars pregame/active context before creating a capture.
    private boolean autoEnabled = true;
    private boolean autoCapture;
    private boolean autoRoster;
    private boolean rosterPaused;
    private boolean closing;
    private final AutoCapture automation = new AutoCapture();
    private RosterTransport transport;
    private RosterRelay relay;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        transport = new RosterTransport(mc.mcDataDir.toPath().resolve("bedwars-companion"));
        relay = new RosterRelay(transport::offer);
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new CaptureCommand());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            DiagnosticWriter active = writer;
            if (active != null) active.shutdown();
            transport.shutdown();
        }, "bedwars-capture-shutdown"));
    }
    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % 20 != 0) return;
        sampleDiscordRoster();
        if (autoEnabled) automate();
        if (writer == null) return;
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
        if (event.world.isRemote && relay != null) relay.end();
        if (!event.world.isRemote || writer == null || !writer.accepting()) return;
        islands = false;
        autoRoster = false;
        previousSnapshot = null;
        writer.emit("roster_gate_closed", map("reason", "world-unloaded"));
        if (autoCapture) stopCapture("world-unloaded");
    }
    private void sampleDiscordRoster() {
        if (mc.theWorld == null || mc.getNetHandler() == null) { relay.end(); return; }
        Scoreboard board = mc.theWorld.getScoreboard();
        ScoreObjective objective = sidebar(board);
        AutoCapture.Scene scene = AutoCapture.scene(objective == null ? "" : objective.getDisplayName(), sidebarLines(board, objective));
        List<RosterRelay.Player> players = new ArrayList<>();
        if (scene == AutoCapture.Scene.ACTIVE) {
            Set<String> seen = new HashSet<>();
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                String name = info.getGameProfile().getName();
                if (name == null || !name.matches("[A-Za-z0-9_]{1,16}") || !seen.add(name.toLowerCase(Locale.ROOT))) continue;
                // Explicit spectators are not active team members. Dead-player retention
                // varies by server; no full initial-lineup claim is made by this preview.
                if (info.getGameType() == net.minecraft.world.WorldSettings.GameType.SPECTATOR) continue;
                ScorePlayerTeam team = board.getPlayersTeam(name);
                String color = team == null ? "pending" : RosterRelay.team(team.getRegisteredName(), team.getColorPrefix(), team.getChatFormat().toString());
                players.add(new RosterRelay.Player(name, color));
                if (players.size() >= 100) break;
            }
        }
        relay.sample(scene, players);
    }
    private ScoreObjective sidebar(Scoreboard board) {
        ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
        // Team-coloured sidebar slots override the global sidebar on some servers.
        if (mc.thePlayer != null) {
            ScorePlayerTeam localTeam = board.getPlayersTeam(mc.thePlayer.getName());
            if (localTeam != null && localTeam.getChatFormat().getColorIndex() >= 0) {
                ScoreObjective coloured = board.getObjectiveInDisplaySlot(3 + localTeam.getChatFormat().getColorIndex());
                if (coloured != null) objective = coloured;
            }
        }
        return objective;
    }
    private List<String> sidebarLines(Scoreboard board, ScoreObjective objective) {
        List<String> lines = new ArrayList<>();
        if (objective == null) return lines;
        for (Score score : board.getSortedScores(objective)) {
            String name = score.getPlayerName();
            if (name == null || name.startsWith("#")) continue;
            lines.add(clip(ScorePlayerTeam.formatPlayerName(board.getPlayersTeam(name), name)));
            if (lines.size() == 32) break;
        }
        return lines;
    }
    private void beginCapture(boolean automatic, String evidence) {
        writer = new DiagnosticWriter(mc.mcDataDir.toPath().resolve("bedwars-companion/captures"));
        Map<String, Object> modeMarker = map("label", "mode-selected");
        modeMarker.put("manually_selected_mode", captureMode);
        writer.emit("manual_marker", modeMarker);
        autoCapture = automatic; autoRoster = false; rosterPaused = false; closing = false;
        automation.reset();
        islands = false; connected = mc.theWorld != null && mc.getNetHandler() != null;
        previousSnapshot = null; lastWarning = ""; rejectedChat = 0;
        if (automatic) {
            Map<String, Object> marker = map("label", "auto-start");
            marker.put("evidence", evidence);
            marker.put("lifecycle_status", "experimental-unverified");
            writer.emit("automation_marker", marker);
            tell("Automatic diagnostic capture started (" + evidence + ").");
        }
    }
    private void stopCapture(String reason) {
        if (writer == null || !writer.accepting()) return;
        if (autoCapture) writer.emit("automation_marker", map("label", "auto-stop-" + reason));
        writer.emit("diagnostic_counts", map("rejected_chat", rejectedChat));
        writer.stop(); closing = true; islands = false; autoRoster = false;
        automation.reset();
        tell("Finishing diagnostic capture. " + writer.file());
    }
    private void automate() {
        // Do not continually retry after storage failure, queue/file/time limits.
        if (writer != null && !writer.accepting() && (!closing || writer.status().startsWith("failed-"))) {
            autoEnabled = false;
            tell("Auto capture disabled: " + writer.status() + ". Check /bwcapture status.");
            return;
        }
        AutoCapture.Scene scene = AutoCapture.Scene.OTHER;
        if (mc.theWorld != null && mc.getNetHandler() != null) {
            Scoreboard board = mc.theWorld.getScoreboard();
            ScoreObjective objective = sidebar(board);
            scene = AutoCapture.scene(objective == null ? "" : objective.getDisplayName(), sidebarLines(board, objective));
        }
        if (writer == null || writer.finished()) {
            if (scene == AutoCapture.Scene.OTHER) return;
            beginCapture(true, scene == AutoCapture.Scene.PREGAME ? "pregame-sidebar" : "active-sidebar-partial-start");
        }
        if (!writer.accepting() || !autoCapture) return;
        automation.observe(scene);
        if (automation.shouldStop(scene)) {
            stopCapture(scene == AutoCapture.Scene.PREGAME ? "next-pregame" : "sidebar-lost");
            return;
        }
        boolean ready = automation.rosterReady() && !rosterPaused;
        if (ready != autoRoster) {
            autoRoster = ready;
            previousSnapshot = null;
            writer.emit("automation_marker", map("label", ready ? "auto-roster-enabled" : "auto-roster-paused"));
        }
        // Never carry a manual override into a detected lobby/pregame in auto mode.
        if (scene != AutoCapture.Scene.ACTIVE) islands = false;
    }
    private void snapshot() {
        Scoreboard board = mc.theWorld.getScoreboard();
        ScoreObjective objective = sidebar(board);
        if (objective == null || !MessageFilter.plain(objective.getDisplayName()).contains("BED WARS")) {
            if (islands) {
                islands = false;
                writer.emit("roster_gate_closed", map("reason", "bedwars-sidebar-lost"));
            }
            previousSnapshot = null;
            return;
        }
        Map<String, Object> data = map("sidebar_title", clip(objective.getDisplayName()));
        data.put("manually_selected_mode", captureMode);
        List<String> lines = sidebarLines(board, objective);
        data.put("sidebar_lines", lines);
        data.put("islands_manually_confirmed", islands);
        data.put("roster_capture_basis", islands ? "manual-islands" : autoRoster ? "experimental-active-sidebar" : "disabled");
        if (islands || autoRoster) {
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
            return "/bwcapture auto [solo|doubles|3v3v3v3|off] | start [solo|doubles|3v3v3v3] | islands | pause | status | stop | mark <spawn|bed|kill|final|spectating|rejoin|end>";
        }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }
        @Override public void processCommand(ICommandSender sender, String[] args) {
            if (args.length == 0) { tell(getCommandUsage(sender)); return; }
            switch (args[0]) {
                case "auto":
                    if (args.length == 2 && args[1].equalsIgnoreCase("off")) {
                        relay.enabled(false);
                        autoEnabled = false;
                        if (autoCapture) stopCapture("disabled");
                        tell("Auto capture disabled.");
                        return;
                    }
                    // Same mode validation as manual start; arm without recording the lobby.
                case "start":
                    if (writer != null && !writer.finished()) { tell("A capture is active or draining. " + writer.status()); return; }
                    String mode = args.length == 1 ? "unspecified" : args[1].toLowerCase(Locale.ROOT);
                    if (mode.equals("duos")) mode = "doubles";
                    if (mode.equals("3s") || mode.equals("threes")) mode = "3v3v3v3";
                    if (args.length > 2 || (args.length == 2 && !Arrays.asList("solo", "doubles", "3v3v3v3").contains(mode))) {
                        tell(getCommandUsage(sender)); return;
                    }
                    captureMode = mode;
                    relay.enabled(true);
                    autoEnabled = args[0].equals("auto");
                    closing = true; // A previously completed manual capture is not a failure.
                    if (autoEnabled) {
                        tell("Auto capture armed (mode=" + captureMode + "). Play normally; /bwcapture stop disables it. Experimental match detection.");
                    } else {
                        beginCapture(false, "manual");
                        tell("Diagnostic capture started (mode=" + captureMode + "). On your island, run /bwcapture islands.");
                    }
                    break;
                case "islands":
                    if (!active()) return;
                    relay.enabled(true);
                    islands = true; rosterPaused = false; previousSnapshot = null;
                    writer.emit("manual_marker", map("label", "islands"));
                    tell("Roster capture enabled by your island confirmation.");
                    break;
                case "pause":
                    if (!active()) return;
                    relay.enabled(false);
                    islands = false; autoRoster = false; rosterPaused = true; previousSnapshot = null;
                    writer.emit("manual_marker", map("label", "roster-paused"));
                    tell("Roster capture paused; system candidates and sidebar capture continue.");
                    break;
                case "stop":
                    relay.enabled(false);
                    autoEnabled = false;
                    stopCapture("manual-stop");
                    tell("Auto capture disabled.");
                    break;
                case "status":
                    tell(transport.status());
                    tell("auto=" + autoEnabled + "; mode=" + captureMode + "; " + (writer == null ? "Capture off." : writer.status() + "; roster=" + (islands || autoRoster) + "; " + writer.file()));
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
