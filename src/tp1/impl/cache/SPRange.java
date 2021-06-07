package tp1.impl.cache;

import java.util.Set;

public class SPRange {
    private String[][] values; 
    private String timestamp;
    private Set<String> sharedWith;
    
    public SPRange() {
    }

    public SPRange(String timestamp, Set<String> sharedWith, String[][] values) {
        this.values = values;
        this.timestamp = timestamp;
        this.sharedWith = sharedWith;
    }

    public String[][] getValues() {
        return values;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public Set<String> getSharedWIth() {
        return sharedWith;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
    
    public void setSharedWith(Set<String> sharedWith){
        this.sharedWith=sharedWith;
    }

    public void setValues(String[][] values){
        this.values=values;
    }

    public String toString() {
        String f = String.format("%s, %s, %s", values, timestamp.toString(), sharedWith);
        return f;
    }
}
