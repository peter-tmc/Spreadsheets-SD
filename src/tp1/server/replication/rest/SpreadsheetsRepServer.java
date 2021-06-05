package tp1.server.replication.rest;

import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.logging.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.xml.namespace.QName;



import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import jakarta.xml.ws.Service;
import tp1.Discovery;
import tp1.api.service.soap.SoapUsers;
import tp1.server.replication.rest.resources.SpreadsheetRepResource;
import tp1.util.InsecureHostnameVerifier;
import tp1.zookeeper.ZookeeperProcessor;
import org.apache.zookeeper.CreateMode;

public class SpreadsheetsRepServer {

    private static Logger Log = Logger.getLogger(SpreadsheetsRepServer.class.getName());

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
            String domain = args[0];
            discover.start(null, domain, SERVICE, serverURI);
            Thread.sleep(2000);
            URI[] uris = Discovery.getInstance().knownUrisOf("users");
            for (URI u : uris) {
                if (u.toString().contains("soap")) {
                    QName QNAME = new QName(SoapUsers.NAMESPACE, SoapUsers.NAME);
                    Service service = Service.create(new URL(u.toString() + "/users/?wsdl"), QNAME);
                    service.getPort(SoapUsers.class);
                }
            }
            ZookeeperProcessor zk = new ZookeeperProcessor( "localhost:2181,kafka:2181");
            zk.write(domain, CreateMode.PERSISTENT);
            String path = zk.write(domain + "/bla_", serverURI, CreateMode.EPHEMERAL_SEQUENTIAL);
            config.register(new SpreadsheetRepResource(domain, args[1], serverURI, discover, zk, path));
            JdkHttpServerFactory.createHttpServer(URI.create(serverURI), config, SSLContext.getDefault());
            Log.info(String.format("%s Server ready @ %s\n", SERVICE, serverURI));

            // More code can be executed here...
        } catch (Exception e) {
            Log.severe(e.getMessage());
        }
    }
}