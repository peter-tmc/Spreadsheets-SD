package tp1.requests;

import java.util.List;
import java.util.SortedSet;

import com.google.gson.Gson;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import tp1.api.Spreadsheet;
import tp1.api.service.rest.RestSpreadsheets;
import tp1.impl.cache.SPRange;
import tp1.server.replication.utils.Operation;

public class RestClientRep extends RestClient {

    public RestClientRep(String serverSecret) {
        super(serverSecret);
    }

    public long getVersionsRep(String urlTarget) {
        System.out.println("chegou2");
        WebTarget target = client.target(urlTarget).path("/rep");
        short retries = 0;
        while (retries < MAX_RETRIES) {
            try {
                Response r = target.queryParam("password", serverSecret).request().accept(MediaType.APPLICATION_JSON)
                        .get();
                if (r.getStatus() == Status.OK.getStatusCode() && r.hasEntity()) {
                    return r.readEntity(Long.class);
                } else {
                    throw new WebApplicationException(r.getStatus());
                }
            } catch (ProcessingException pe) {
                System.out.println("Timeout occurred");
                pe.printStackTrace();
                retries++;
                try {
                    Thread.sleep(RETRY_PERIOD);
                } catch (InterruptedException e) {

                }
                System.out.println("Retrying to execute request.");
            }
        }
        return -1;
    }

    public SortedSet<Operation> getVersionOperationsRep(String urlTarget, long version, String serverSecret) {
        WebTarget target = client.target(urlTarget).path("/rep/ops");
        short retries = 0;
        while (retries < MAX_RETRIES) {
            try {
                Response r = target.queryParam("password", serverSecret).queryParam("version", version).request().accept(MediaType.APPLICATION_JSON)
                        .get();
                if (r.getStatus() == Status.OK.getStatusCode() && r.hasEntity()) {
                    //return (SortedSet<Operation>) r.readEntity(SortedSet.class);
                    return json.fromJson(r.readEntity(String.class), SortedSet.class);
                } else {
                    throw new WebApplicationException(r.getStatus());
                }
            } catch (ProcessingException pe) {
                System.out.println("Timeout occurred");
                pe.printStackTrace();
                retries++;
                try {
                    Thread.sleep(RETRY_PERIOD);
                } catch (InterruptedException e) {

                }
                System.out.println("Retrying to execute request.");
            }
        }
        return null;
    }

    public boolean secondaryExecute(String serverSecret, String uriTarget, Operation op) {
        WebTarget target = client.target(uriTarget).path("/execute");
        short retries = 0; 
        System.out.println("entrei secondaryExecute");
        while (retries < MAX_RETRIES) {
            try {
                Response r = target.queryParam("password", serverSecret).request()
                        .post(Entity.entity(op, MediaType.APPLICATION_JSON));
                if (r.getStatus() == Status.NO_CONTENT.getStatusCode())
                    return true;
                else
                    throw new WebApplicationException(r.getStatus());
            } catch (ProcessingException pe) {
                System.out.println("Timeout occurred");
                pe.printStackTrace();
                retries++;
                try {
                    Thread.sleep(RETRY_PERIOD);
                } catch (InterruptedException e) {

                }
                System.out.println("Retrying to execute request.");
            }
        }
        return false;
    }
}
