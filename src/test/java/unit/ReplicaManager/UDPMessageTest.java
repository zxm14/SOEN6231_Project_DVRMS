package unit.ReplicaManager;

import org.junit.jupiter.api.Test;
import server.UDPMessage;

import static org.junit.jupiter.api.Assertions.*;

public class UDPMessageTest {

    @Test
    void parseWithFields() {
        UDPMessage msg = UDPMessage.parse("VOTE_BYZANTINE:3");
        assertEquals(UDPMessage.Type.VOTE_BYZANTINE, msg.getType());
        assertEquals(1, msg.fieldCount());
        assertEquals("3", msg.getField(0));
    }

    @Test
    void serializeMessage() {
        UDPMessage msg = new UDPMessage(UDPMessage.Type.REPLACE_REQUEST, "3", "BYZANTINE_THRESHOLD");
        assertEquals("REPLACE_REQUEST:3:BYZANTINE_THRESHOLD", msg.serialize());
    }

    @Test
    void parseInvalidTypeThrows() {
        assertThrows(IllegalArgumentException.class, () -> UDPMessage.parse("BOGUS:x"));
    }
}
