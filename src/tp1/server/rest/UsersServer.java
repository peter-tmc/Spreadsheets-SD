package tp1.server.rest;

import java.net.InetAddress;
import java.net.URI;
import java.util.logging.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import tp1.Discovery;
import tp1.server.rest.resources.UsersResource;
import tp1.util.InsecureHostnameVerifier;

public class UsersServer {

	private static Logger Log = Logger.getLogger(UsersServer.class.getName());

	static {
		System.setProperty("java.net.preferIPv4Stack", "true");
		System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s\n");
	}

	public static final int PORT = 8080;
	public static final String SERVICE = "users";

	public static void main(String[] args) {
		try {
			String ip = InetAddress.getLocalHost().getHostAddress();
			String serverURI = String.format("https://%s:%s/rest", ip, PORT);
			Discovery discover = Discovery.getInstance();

			HttpsURLConnection.setDefaultHostnameVerifier(new InsecureHostnameVerifier());
			discover.start(null, args[0], SERVICE, serverURI);

			ResourceConfig config = new ResourceConfig();
			config.register(new UsersResource(args[0], discover, args[1]));
			JdkHttpServerFactory.createHttpServer(URI.create(serverURI), config, SSLContext.getDefault());
			// Discovery discover = new Discovery(args[0], SERVICE, serverURI);

			Log.info(String.format("%s Server ready @ %s\n", SERVICE, serverURI));

			// More code can be executed here...
		} catch (Exception e) {
			Log.severe(e.getMessage());
		}
	}
}
