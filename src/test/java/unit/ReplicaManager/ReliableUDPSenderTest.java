package unit.ReplicaManager;

import org.junit.jupiter.api.Test;
import server.ReliableUDPSender;

import java.net.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReliableUDPSenderTest {

    @Test
    void ackReceived_returnsTrue() throws Exception {
        // Start a mock UDP server that replies with ACK
        DatagramSocket mockServer = new DatagramSocket(0); // ephemeral port
        mockServer.setSoTimeout(1000);
        int serverPort = mockServer.getLocalPort();

        Thread serverThread = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                mockServer.receive(packet);

                String ack = "ACK:ok";
                byte[] ackData = ack.getBytes(StandardCharsets.UTF_8);
                mockServer.send(new DatagramPacket(ackData, ackData.length,
                    packet.getAddress(), packet.getPort()));
            } catch (Exception e) {
                // test will fail via assertion
            }
        });
        serverThread.start();

        DatagramSocket clientSocket = null;
        try {
            ReliableUDPSender sender = new ReliableUDPSender();
            clientSocket = new DatagramSocket();
            boolean result = sender.send("TEST_MSG", InetAddress.getByName("localhost"), serverPort, clientSocket);
            assertTrue(result);
        } finally {
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
            if (!mockServer.isClosed()) {
                mockServer.close();
            }
            serverThread.join(2000);
            assertFalse(serverThread.isAlive(), "Mock server thread should terminate");
        }
    }

    @Test
    void allRetriesExhausted_returnsFalse() throws Exception {
        // Server that always responds with a non-ACK reply.
        // send() will loop through all MAX_RETRIES+1 attempts and return false.
        DatagramSocket noAckServer = new DatagramSocket(0);
        noAckServer.setSoTimeout(5000);
        int serverPort = noAckServer.getLocalPort();

        Thread serverThread = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                while (!noAckServer.isClosed()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    noAckServer.receive(p);
                    byte[] nope = "NOPE".getBytes(StandardCharsets.UTF_8);
                    noAckServer.send(new DatagramPacket(nope, nope.length,
                        p.getAddress(), p.getPort()));
                }
            } catch (Exception ignored) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();

        ReliableUDPSender sender = new ReliableUDPSender();
        try (DatagramSocket clientSocket = new DatagramSocket()) {
            boolean result = sender.send("TEST_MSG",
                InetAddress.getByName("localhost"), serverPort, clientSocket);
            assertFalse(result);
        } finally {
            noAckServer.close();
            serverThread.join(2000);
        }
    }
}
