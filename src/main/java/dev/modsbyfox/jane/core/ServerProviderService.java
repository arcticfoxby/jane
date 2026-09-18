package dev.modsbyfox.jane.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serves only the exact top-level JARs captured in the prepared required manifest. */
public final class ServerProviderService implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger("jane");
    public record File(ManifestEntry entry, Path path) {
        public File {
            java.util.Objects.requireNonNull(entry, "entry");
            java.util.Objects.requireNonNull(path, "path");
        }
    }

    private final Path gameDir;
    private final Map<String, File> files;
    private final ServerProviderTokens tokens;
    private final ServerSocket listener;
    private final ThreadPoolExecutor workers;
    private final Thread acceptThread;
    private final Set<Socket> active = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ServerProviderService(Path gameDir, Map<String, File> files, int bindPort, Clock clock) throws IOException {
        if (bindPort < 0 || bindPort > 65535) throw new IOException("Invalid provider bind port");
        this.gameDir = gameDir;
        this.files = Map.copyOf(files);
        for (var item : this.files.entrySet()) {
            if (!item.getKey().equals(item.getValue().entry().sha512()))
                throw new IOException("Provider hash mapping differs from manifest");
        }
        this.tokens = new ServerProviderTokens(clock);
        this.listener = new ServerSocket(bindPort, 16);
        this.workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "Jane ServerProvider worker");
            thread.setDaemon(true);
            return thread;
        }, (task, executor) -> { if (task instanceof Connection connection) connection.close(); });
        this.acceptThread = new Thread(this::accept, "Jane ServerProvider accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
        LOGGER.log(System.Logger.Level.INFO, "Jane ServerProvider listening on port " + listener.getLocalPort());
    }

    public int port() { return listener.getLocalPort(); }

    public String issue(RequiredManifest manifest) {
        if (listener.isClosed()) throw new IllegalStateException("ServerProvider is stopped");
        return tokens.issue(manifest.entries().stream().map(ManifestEntry::sha512)
                .collect(java.util.stream.Collectors.toSet()));
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                socket.setSoTimeout(30_000);
                active.add(socket);
                workers.execute(new Connection(socket));
            } catch (IOException exception) {
                if (!listener.isClosed()) LOGGER.log(System.Logger.Level.WARNING, "Jane ServerProvider accept failed", exception);
            }
        }
    }

    private final class Connection implements Runnable {
        private final Socket socket;
        private Connection(Socket socket) { this.socket = socket; }
        private void close() {
            active.remove(socket);
            try { socket.close(); } catch (IOException ignored) { }
        }
        @Override public void run() {
            try (socket; DataInputStream in = new DataInputStream(socket.getInputStream());
                 DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
                ServerProviderWire.Request request;
                try { request = ServerProviderWire.readRequest(in); }
                catch (IOException exception) { out.writeByte(ServerProviderWire.BAD_REQUEST); return; }
                String token = request.token();
                if (!tokens.known(token)) {
                    out.writeByte(ServerProviderWire.UNAUTHORIZED);
                    LOGGER.log(System.Logger.Level.WARNING, "Jane ServerProvider rejected unauthorized request");
                    return;
                }
                if (!tokens.acquire(token, request.sha512())) {
                    out.writeByte(ServerProviderWire.NOT_ALLOWED);
                    LOGGER.log(System.Logger.Level.WARNING, "Jane ServerProvider rejected request outside capability");
                    return;
                }
                boolean released = false;
                try {
                    File file = files.get(request.sha512());
                    if (file == null) {
                        tokens.release(token);
                        released = true;
                        out.writeByte(ServerProviderWire.NOT_ALLOWED);
                        return;
                    }
                    Path safe;
                    try {
                        safe = PathSafety.existingJarInMods(gameDir, file.path());
                        if (java.nio.file.Files.size(safe) != file.entry().fileSize()) throw new IOException("Size changed");
                    } catch (IOException exception) {
                        tokens.release(token);
                        released = true;
                        out.writeByte(ServerProviderWire.STALE);
                        LOGGER.log(System.Logger.Level.WARNING, "Jane ServerProvider rejected stale file");
                        return;
                    }
                    try (FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                        if (channel.size() != file.entry().fileSize() || !file.entry().sha512().equals(hash(channel))) {
                            tokens.release(token);
                            released = true;
                            out.writeByte(ServerProviderWire.STALE);
                            LOGGER.log(System.Logger.Level.WARNING, "Jane ServerProvider rejected stale file for " + file.entry().modId());
                            return;
                        }
                        channel.position(0);
                        out.writeByte(ServerProviderWire.OK);
                        out.writeLong(file.entry().fileSize());
                        byte[] buffer = new byte[64 * 1024];
                        var stream = Channels.newInputStream(channel);
                        int count;
                        while ((count = stream.read(buffer)) != -1) out.write(buffer, 0, count);
                        out.flush();
                        LOGGER.log(System.Logger.Level.INFO, "Jane ServerProvider served " + file.entry().modId()
                                + " hash " + file.entry().sha512().substring(0, 12));
                    } catch (IOException exception) {
                        LOGGER.log(System.Logger.Level.WARNING, "Jane ServerProvider transfer failed for " + file.entry().modId());
                    }
                } finally { if (!released) tokens.release(token); }
            } catch (IOException exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Jane ServerProvider connection failed");
            } finally { close(); }
        }
    }

    private static String hash(FileChannel channel) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] buffer = new byte[64 * 1024];
            var stream = Channels.newInputStream(channel);
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    @Override public void close() {
        try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : active) try { socket.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
        tokens.clear();
        try { acceptThread.join(1000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
