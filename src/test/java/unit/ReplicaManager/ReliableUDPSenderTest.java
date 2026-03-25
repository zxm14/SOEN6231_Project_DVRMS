package unit.ReplicaManager;

import org.junit.jupiter.api.Test;
import server.ReliableUDPSender;

import java.net.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class ReliableUDPSenderTest {

    @Test
    void ackReceived_returnsTrue() throws Exception {
        // Start a mock UDP server that replies with ACK
        DatagramSocket mockServer = new DatagramSocket(0); // ephemeral port
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

        ReliableUDPSender sender = new ReliableUDPSender();
        DatagramSocket clientSocket = new DatagramSocket();
        boolean result = sender.send("TEST_MSG", InetAddress.getByName("localhost"), serverPort, clientSocket);

        assertTrue(result);

        clientSocket.close();
        mockServer.close();
        serverThread.join(2000);
    }
}
