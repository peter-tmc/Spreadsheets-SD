package tp1.server.rest;

import jakarta.xml.ws.Service;
import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import tp1.Discovery;
import tp1.api.service.soap.SoapUsers;
import tp1.server.rest.resources.SpreadsheetResourceOAuth;
import tp1.util.DiscoveryURI;
import tp1.util.InsecureHostnameVerifier;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.xml.namespace.QName;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.logging.Logger;

public class SpreadsheetsServerOAuth {

    private static Logger Log = Logger.getLogger(SpreadsheetsServerOAuth.class.getName());

    static {
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s\n");
    }

    public static final int PORT = 8080;
    public static final String SERVICE = "sheets";

    public static void main(String[] args) {
        try {
            String ip = InetAddress.getLocalHost().getHostAddress();

            HttpsURLConnection.setDefaultHostnameVerifier(new InsecureHostnameVerifier());
            ResourceConfig config = new ResourceConfig();
            Discovery discover = Discovery.getInstance();
            String serverURI = String.format("https://%s:%s/rest", ip, PORT);
            discover.start(null, args[0], SERVICE, serverURI);
            Thread.sleep(2000);
            DiscoveryURI[] uris = Discovery.getInstance().knownUrisOf("users");
            for (DiscoveryURI u : uris) {
                if (u.getURI().contains("soap")) {
                    QName QNAME = new QName(SoapUsers.NAMESPACE, SoapUsers.NAME);
                    Service service = Service.create(new URL(u.getURI() + "/users/?wsdl"), QNAME);
                    service.getPort(SoapUsers.class);
                }
            }
            config.register(new SpreadsheetResourceOAuth(args[0], Boolean.parseBoolean(args[1]), args[2], args[3], args[4], args[5], serverURI, discover));
            JdkHttpServerFactory.createHttpServer(URI.create(serverURI), config, SSLContext.getDefault());

            Log.info(String.format("%s Server ready @ %s\n", SERVICE, serverURI));

            // More code can be executed here...
        } catch (Exception e) {
            Log.severe(e.getMessage());
        }
    }
}
