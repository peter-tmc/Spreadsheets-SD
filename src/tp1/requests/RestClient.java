package tp1.requests;

import com.google.gson.Gson;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import tp1.api.Spreadsheet;
import tp1.api.service.rest.RestSpreadsheets;
import tp1.impl.cache.SPRange;

public class RestClient {
    protected ClientConfig config;
    protected Client client;
    protected Gson json;
    protected String serverSecret;
    private final static int CONNECT_TIMEOUT = 10000;
    private final static int READ_TIMEOUT = 600;
    protected final static int MAX_RETRIES = 3;
    protected final static int RETRY_PERIOD = 1000;

    public RestClient(String serverSecret) {
        this.config = new ClientConfig();
        this.config.property(ClientProperties.CONNECT_TIMEOUT, CONNECT_TIMEOUT);
        this.config.property(ClientProperties.READ_TIMEOUT, READ_TIMEOUT);
        this.config.property(ClientProperties.FOLLOW_REDIRECTS, true);
        this.client = ClientBuilder.newClient(config);
        json = new Gson();
        this.serverSecret = serverSecret;
    }

    //TODO acabar isto e para a replicacao tbm e para o soap etc
    public SPRange getValuesRangeRequest(String sheetURL, String domain, String range, Spreadsheet sheet,
            String tc) {
        WebTarget target = client.target(sheetURL).path("range");
        short retries = 0;
        while (retries < MAX_RETRIES) {
            try {
                String userAux = String.format("%s@%s", sheet.getOwner(), domain);
                Response r = target.queryParam("range", range).queryParam("userId", userAux)
                        .queryParam("password", serverSecret).queryParam("timestamp", tc).request()
                        .accept(MediaType.APPLICATION_JSON).get();
                if (r.getStatus() == Status.OK.getStatusCode() && r.hasEntity()) {
                    return json.fromJson(r.readEntity(String.class), SPRange.class);
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
}
