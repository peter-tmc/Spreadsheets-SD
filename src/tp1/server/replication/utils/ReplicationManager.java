package tp1.server.replication.utils;

import java.util.LinkedList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public class ReplicationManager {

    private long version;
    private List<String> replicasURI;
    private SortedSet<Operation> operationsLog;
    
    public ReplicationManager() {
        version = 0;
        replicasURI = new LinkedList<>(); //TODO linked list?
        operationsLog = new TreeSet<>();
    }

    public void setVersion(long version) {
        this.version=version;
    }

    public long getVersion() {
        return this.version;
    }


    public List<String> getReplicasURI() {
        return this.replicasURI;
    }

    public void setReplicasURI(List<String> replicasURI) {
        this.replicasURI = replicasURI;
    }

    public void addReplicaURI(String uri) {
        replicasURI.add(uri);
    }

    public void addOperation(Operation op) {
        version = op.getVersion();
        operationsLog.add(op);
    }

    public SortedSet<Operation> getOpsStartingIn(long version) {
        Operation aux = new Operation();
        aux.setVersion(version);
        return operationsLog.tailSet(aux);        
    }
}
