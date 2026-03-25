package server;

import java.net.*;
import java.nio.charset.StandardCharsets;

public class ReliableUDPSender {

    private static final int BUFFER_SIZE = 8192;
    private static final int INITIAL_TIMEOUT_MS = 500;
    private static final int MAX_RETRIES = 5;

    /**
     * Send a message and wait for ACK. Retries with exponential backoff.
     * @return true if ACK received, false if all retries exhausted.
     */
    public boolean send(String message, InetAddress address, int port, DatagramSocket socket) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);

        int timeout = INITIAL_TIMEOUT_MS;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                socket.send(packet);
                socket.setSoTimeout(timeout);

                byte[] ackBuf = new byte[BUFFER_SIZE];
                DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length);
                socket.receive(ackPacket);

                String response = new String(ackPacket.getData(), 0, ackPacket.getLength(), StandardCharsets.UTF_8);
                if (response.startsWith("ACK:")) {
                    return true;
                }
            } catch (SocketTimeoutException e) {
                timeout *= 2;
            } catch (Exception e) {
                System.err.println("ReliableUDPSender error: " + e.getMessage());
                return false;
            }
        }
        return false;
    }
}
