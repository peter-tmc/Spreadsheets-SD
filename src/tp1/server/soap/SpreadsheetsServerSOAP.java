package tp1.server.soap;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.xml.namespace.QName;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import jakarta.xml.ws.Endpoint;
import jakarta.xml.ws.Service;
import tp1.Discovery;
import tp1.api.service.soap.SoapUsers;
import tp1.server.soap.ws.SpreadsheetsWS;
import tp1.util.InsecureHostnameVerifier;

public class SpreadsheetsServerSOAP {
	private static Logger Log = Logger.getLogger(SpreadsheetsServerSOAP.class.getName());

	static {
		System.setProperty("java.net.preferIPv4Stack", "true");
		System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s\n");
	}

	public static final int PORT = 8080;
	public static final String SERVICE = "sheets";
	public static final String SOAP_SPREADSHEETS_PATH = "/soap/spreadsheets";

	public static void main(String[] args) {
		try {
			String ip = InetAddress.getLocalHost().getHostAddress();
			String serverURI = String.format("https://%s:%s/soap", ip, PORT);

			HttpsURLConnection.setDefaultHostnameVerifier(new InsecureHostnameVerifier());

			HttpsConfigurator configurator = new HttpsConfigurator(SSLContext.getDefault());
			HttpsServer server = HttpsServer.create(new InetSocketAddress(ip, PORT), 0);
			server.setHttpsConfigurator(configurator);
			String domain = args[0];
			Discovery discover = Discovery.getInstance();
			discover.start(null, domain, SERVICE, serverURI);
			Thread.sleep(2000);
			Thread.sleep(2000);
			URI[] uris = Discovery.getInstance().knownUrisOf("users");
			for (URI u : uris) {
				if (u.toString().contains("soap")) {
					QName QNAME = new QName(SoapUsers.NAMESPACE, SoapUsers.NAME);
					Service service = Service.create(new URL(u.toString() + "/users/?wsdl"), QNAME);
					service.getPort(SoapUsers.class);
				}
			}
			server.setExecutor(Executors.newCachedThreadPool());

			Endpoint soapSpreadsheetsEndpoint = Endpoint.create(new SpreadsheetsWS(domain, args[1], serverURI, discover));

			soapSpreadsheetsEndpoint.publish(server.createContext(SOAP_SPREADSHEETS_PATH));

			server.start();

			Log.info(String.format("%s Server ready @ %s\n", SERVICE, serverURI));

			// More code can be executed here...
		} catch (Exception e) {
			Log.severe(e.getMessage());
		}
	}
}
