package unit.ReplicaManager;

import org.junit.jupiter.api.Test;
import server.UDPMessage;

import static org.junit.jupiter.api.Assertions.*;

public class UDPMessageTest {

    @Test
    void parseWithFields() {
        // Vote format includes voter ID: VOTE_BYZANTINE:<targetId>:<voterId>
        UDPMessage msg = UDPMessage.parse("VOTE_BYZANTINE:3:1");
        assertEquals(UDPMessage.Type.VOTE_BYZANTINE, msg.getType());
        assertEquals(2, msg.fieldCount());
        assertEquals("3", msg.getField(0));
        assertEquals("1", msg.getField(1));
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
