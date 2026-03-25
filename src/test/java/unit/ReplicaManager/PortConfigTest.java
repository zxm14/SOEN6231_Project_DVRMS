package unit.ReplicaManager;

import org.junit.jupiter.api.Test;
import server.PortConfig;

import static org.junit.jupiter.api.Assertions.*;

public class PortConfigTest {

    @Test
    void rmAndReplicaPorts() {
        assertEquals(7001, PortConfig.RM_1);
        assertEquals(7002, PortConfig.RM_2);
        assertEquals(7003, PortConfig.RM_3);
        assertEquals(7004, PortConfig.RM_4);
        assertArrayEquals(new int[]{7001, 7002, 7003, 7004}, PortConfig.ALL_RMS);

        assertEquals(6001, PortConfig.REPLICA_1);
        assertEquals(6002, PortConfig.REPLICA_2);
        assertEquals(6003, PortConfig.REPLICA_3);
        assertEquals(6004, PortConfig.REPLICA_4);
        assertArrayEquals(new int[]{6001, 6002, 6003, 6004}, PortConfig.ALL_REPLICAS);
    }

    @Test
    void officePortMapping() {
        assertEquals(5001, PortConfig.officePort(1, "MTL"));
        assertEquals(5002, PortConfig.officePort(1, "WPG"));
        assertEquals(5003, PortConfig.officePort(1, "BNF"));
        assertEquals(5012, PortConfig.officePort(2, "WPG"));
        assertEquals(5033, PortConfig.officePort(4, "BNF"));

        assertThrows(IllegalArgumentException.class, () -> PortConfig.officePort(1, "TORONTO"));
    }
}
