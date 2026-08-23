package org.graalvm.argo.dataset.utils.network;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class SocketConnection implements Closeable {

    private final SocketChannel channel;
    private ByteBuffer buffer;
    private final Selector selector;
    private final AtomicInteger requestId;
    private final Map<Integer, Consumer<String>> callbackBacklog;
    private boolean isClosed;

    /**
     * This set contains IDs of the requests that received their responses as we read them
     * with readAvailableMessagesImpl. Used to wait until we get a response for a particular
     * request in sendMessage.
     * <p>
     * This set contains this state only between two invocations of readAvailableMessagesImpl.
     * Every invocation of this method empties and potentially updates the contents of this set.
     * Can only be used under assumption of a single-threaded execution.
     */
    private final Set<Integer> returnedIds;

    public SocketConnection(String host, int port) {
        buffer = ByteBuffer.allocate(8192);
        requestId = new AtomicInteger(0);
        callbackBacklog = new HashMap<>();
        returnedIds = new HashSet<>();
        isClosed = false;
        try {
            selector = Selector.open();
            channel = SocketChannel.open(new InetSocketAddress(host, port));
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void sendMessage(String msg, Consumer<String> callback) throws IOException {
        // Make sure we receive a response from this request with the current request ID.
        int currentRequestId = sendMessageImpl(msg, callback);
        while (!returnedIds.contains(currentRequestId) && !isClosed) {
            readAvailableMessagesImpl(true, SocketNetworkUtils.SYNC_REQUEST_TIMEOUT);
        }
    }

    public void sendMessageAsync(String msg, Consumer<String> callback) throws IOException {
        // Just send a message, response will be received when reading responses explicitly.
        sendMessageImpl(msg, callback);
    }

    private int sendMessageImpl(String msg, Consumer<String> callback) throws IOException {
        int currentRequestId = requestId.getAndIncrement();
        callbackBacklog.put(currentRequestId, callback);
        // Write msg to buffer.
        buffer.clear();
        byte[] msgBytes = msg.getBytes();
        buffer.putInt(currentRequestId);
        buffer.putInt(msgBytes.length);
        buffer.put(msgBytes);
        buffer.flip();
        // Non-blocking sockets are allowed to perform partial writes.
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        buffer.clear();
        return currentRequestId;
    }

    public void readMessages() throws IOException {
        readAvailableMessagesImpl(false, 0);
    }

    /**
     * @param blocking if set to true, method will wait for the timeout for the responses.
     * @param timeout only matters if blocking is set to true.
     * @throws IOException
     */
    private void readAvailableMessagesImpl(boolean blocking, long timeout) throws IOException {
        if (blocking) {
            selector.select(timeout);
        } else {
            selector.selectNow();
        }
        Set<SelectionKey> selectedKeys = selector.selectedKeys();
        Iterator<SelectionKey> iter = selectedKeys.iterator();
        returnedIds.clear();
        while (iter.hasNext()) {
            SelectionKey key = iter.next();
            if (key.isReadable()) {
                while (!read()) {}
            }
            iter.remove();
        }
    }

    private boolean read() throws IOException {
        if (channel.read(buffer) != -1) {
            // Prepare buffer.
            buffer.flip();
            // Read all responses.
            while (buffer.hasRemaining()) {
                // Making sure we have enough room to read the mandatory bytes.
                if (buffer.remaining() < 8) {
                    buffer.compact();
                    return false;
                }
                int requestId = buffer.getInt();
                int payloadLength = buffer.getInt();
                if (buffer.remaining() < payloadLength) {
                    // Roll back two integers that were already read.
                    buffer.position(buffer.position() - 4*2);
                    // Copy all unread bytes including two integers to the beginning, set position to continue writing.
                    buffer.compact();
                    return false;
                }
                byte[] payloadBytes = new byte[payloadLength];
                buffer.get(payloadBytes);
                String payload = new String(payloadBytes);
                returnedIds.add(requestId);
                callbackBacklog.get(requestId).accept(payload);
            }
            buffer.clear();
        } else {
            // TODO: should this be close()? Risk of closing twice.
            close();
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        channel.close();
        System.out.println("Connection closed!");
        buffer = null;
        isClosed = true;
    }
}
