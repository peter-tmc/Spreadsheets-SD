package tp1.server.replication.utils;

import java.util.List;
import java.util.Objects;

public class Operation implements Comparable<Operation> {
    private OperationType type;
    private long version;
    private List<String> arguments;

    public Operation(OperationType type, List<String> args, long version) {
        this.type = type;
        this.arguments = args;
        this.version = version;
    }

    public Operation() {
	}

	public OperationType getType() {
        return this.type;   
    }
    public long getVersion() {
        return this.version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
    

    public void setType(OperationType type) {
        this.type = type;
    }

    public List<String> getArguments() {
        return this.arguments;
    }

    public void setArguments(List<String> arguments) {
        this.arguments = arguments;
    }

    @Override
    public int compareTo(Operation op) {
        return Long.valueOf(version).compareTo(op.getVersion());
    }


    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof Operation)) {
            return false;
        }
        Operation operation = (Operation) o;
        //TODO maybe mudar isto
        return Objects.equals(type, operation.type) && version == operation.version && Objects.equals(arguments, operation.arguments);
    }


}
