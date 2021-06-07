package tp1.impl.cache;

import java.time.Instant;
import java.util.Set;

public class CacheEntry {
    private String[][] values; 
    private Instant tw;
    private Set<String> sharedWith;
    private Instant tc;

    public CacheEntry() {
    }

    public CacheEntry(SPRange result) {
        this.values = result.getValues();
        this.tw = Instant.parse(result.getTimestamp());
        this.sharedWith = result.getSharedWIth();
        tc = Instant.now();
    }

    public String[][] getValues() {
        return values;
    }

    public Instant getTw() {
        return tw;
    }

    public Set<String> getSharedWIth() {
        return sharedWith;
    }

    public Instant getTc() {
        return tc;
    }

    public void setTimestamp(Instant timestamp) {
        this.tw = timestamp;
    }
    
    public void setSharedWith(Set<String> sharedWith){
        this.sharedWith=sharedWith;
    }

    public void setValues(String[][] values){
        this.values=values;
    }

    public void setTc(Instant timestamp) {
        tc = timestamp;
    }
}
