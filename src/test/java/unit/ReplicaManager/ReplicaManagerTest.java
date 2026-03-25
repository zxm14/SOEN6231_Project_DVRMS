package unit.ReplicaManager;

import org.junit.jupiter.api.Test;
import server.PortConfig;
import server.ReplicaManager;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

public class ReplicaManagerTest {

    @Test
    void constructorSetsCorrectPorts() throws Exception {
        for (int id = 1; id <= 4; id++) {
            ReplicaManager rm = new ReplicaManager(id);

            Field rmPortField = ReplicaManager.class.getDeclaredField("rmPort");
            rmPortField.setAccessible(true);
            assertEquals(PortConfig.ALL_RMS[id - 1], rmPortField.getInt(rm),
                "RM port mismatch for id=" + id);

            Field replicaPortField = ReplicaManager.class.getDeclaredField("replicaPort");
            replicaPortField.setAccessible(true);
            assertEquals(PortConfig.ALL_REPLICAS[id - 1], replicaPortField.getInt(rm),
                "Replica port mismatch for id=" + id);
        }
    }
}
