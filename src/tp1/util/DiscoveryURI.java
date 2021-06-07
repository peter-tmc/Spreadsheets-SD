package tp1.util;

import java.net.URI;
import java.time.Instant;

public class DiscoveryURI implements Comparable<DiscoveryURI> {
    private String uri;
    private String timestamp;

    public DiscoveryURI() {
    }
    
    public DiscoveryURI(String uri, String now) {
        this.uri = uri;
        timestamp = now;
    }
    
    public String getURI() {
        return uri;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setURI(String uri) {
        this.uri = uri;
    }

    public void setTimestamp(String now) {
        timestamp = now;
    }

    @Override
    public boolean equals(Object d) {
        if (d == this) {
            return true;
        }
        if (!(d instanceof DiscoveryURI)) {
            return false;
        }
        DiscoveryURI c = (DiscoveryURI) d;
        return this.uri.equals(c.getURI());
    }

    @Override
    public int compareTo(DiscoveryURI dUri) {
        //sorted from earliest timestamp to latest
        return Instant.parse(timestamp).compareTo(Instant.parse(dUri.timestamp));
    }
}

    
