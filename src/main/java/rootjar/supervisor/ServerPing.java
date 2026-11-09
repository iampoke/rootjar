package rootjar.supervisor;

import rootjar.addons.AddonSources;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A Minecraft server list ping: the handshake the multiplayer menu performs, asking for status and reading the
 * JSON reply. It reports the exact online count straight from the server, with no stdin traffic, no console
 * line to parse, and no dependence on join messages surviving whatever plugin rewrote them - which is what
 * made the panel's player count read 0 until someone ran {@code list} by hand.
 */
public final class ServerPing {

    /** Longest status reply accepted. Real ones are a few hundred bytes; this only bounds a hostile server. */
    private static final int MAX_REPLY_BYTES = 1_048_576;

    /**
     * What a status ping reported. {@code sample} holds the names the server chose to publish, which is not
     * necessarily all of them - Paper caps its sample, and a server can hide players entirely.
     */
    public record Status(int online, int max, List<String> sample, String version) {
        /** True when the sample accounts for every player, so it can replace a name list outright. */
        public boolean completeSample() { return sample.size() == online; }
    }

    private ServerPing() {}

    /**
     * Pings {@code host:port} and returns the status, or null when the server did not answer as a Minecraft
     * server: still starting, stopped, not a Minecraft child at all, or unreachable. Null rather than an
     * exception, because every caller has a console-derived value it can keep using instead.
     */
    public static Status ping(String host, int port, int timeoutMs) {
        if (host == null || host.isBlank() || port <= 0 || port > 65535) return null;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(framed(handshake(host, port)));
            out.write(framed(new byte[]{0x00}));   // status request: packet id 0x00 and no fields
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int length = readVarInt(in);
            if (length <= 0 || length > MAX_REPLY_BYTES) return null;
            byte[] body = new byte[length];
            in.readFully(body);
            int[] at = {0};
            if (readVarInt(body, at) != 0x00) return null;          // packet id: status response
            int textLength = readVarInt(body, at);
            if (textLength < 0 || at[0] + textLength > body.length) return null;
            return parse(new String(body, at[0], textLength, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads the status JSON. {@code players.sample} may be absent, empty, or shorter than {@code online}; a
     * server that hides its players still reports an accurate count.
     */
    public static Status parse(String json) {
        String players = AddonSources.valueBody(json, "players");
        if (players.isEmpty()) return null;
        long online = AddonSources.num(players, "online");
        long max = AddonSources.num(players, "max");
        // A count outside any plausible range means the reply was not a status response; trusting it would
        // put nonsense in the sidebar.
        if (online < 0 || online > 1_000_000 || max < 0 || max > 1_000_000) return null;
        List<String> sample = new ArrayList<>();
        for (String entry : AddonSources.objects(AddonSources.valueBody(players, "sample"), null)) {
            String name = AddonSources.str(entry, "name");
            if (name.matches("[A-Za-z0-9_]{1,16}")) sample.add(name);
        }
        String version = AddonSources.str(AddonSources.valueBody(json, "version"), "name");
        return new Status((int) online, (int) max, List.copyOf(sample), version);
    }

    /** Handshake packet body: id 0x00, protocol version, address, port, then next state 1 (status). */
    private static byte[] handshake(String host, int port) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0x00);
        // Any protocol number works for status; servers answer it regardless of their own version.
        writeVarInt(body, -1);
        byte[] address = host.getBytes(StandardCharsets.UTF_8);
        writeVarInt(body, address.length);
        body.write(address);
        body.write((port >>> 8) & 0xFF);   // unsigned short, big endian
        body.write(port & 0xFF);
        writeVarInt(body, 1);
        return body.toByteArray();
    }

    /** Wraps a packet body in its length prefix. */
    private static byte[] framed(byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, body.length);
        out.write(body);
        return out.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) { out.write(value); return; }
            out.write((value & 0x7F) | 0x80);
            // Base 128, so seven bits per byte. Shifting by five wrote -1 as six bytes instead of five,
            // which every server rejects as a bad VarInt.
            value >>>= 7;
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0, shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift > 28) throw new IOException("varint too long");
        }
    }

    private static int readVarInt(byte[] bytes, int[] at) throws IOException {
        int value = 0, shift = 0;
        while (true) {
            if (at[0] >= bytes.length) throw new IOException("truncated varint");
            int b = bytes[at[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift > 28) throw new IOException("varint too long");
        }
    }

    /** VarInt as it goes on the wire. Public so the encoding is testable without opening a socket. */
    public static byte[] encodeVarInt(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, value);
        return out.toByteArray();
    }

    /** Reads one VarInt from {@code bytes}, advancing {@code at} past it. */
    public static int decodeVarInt(byte[] bytes, int[] at) throws IOException {
        return readVarInt(bytes, at);
    }

    /** The exact bytes a status ping opens with, length prefix included, for a given address and port. */
    public static byte[] handshakePacket(String host, int port) throws IOException {
        return framed(handshake(host, port));
    }
}
