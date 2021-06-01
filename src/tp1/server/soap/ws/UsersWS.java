package tp1.server.soap.ws;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.xml.namespace.QName;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;

import com.sun.xml.ws.client.BindingProviderProperties;

import jakarta.xml.ws.BindingProvider;

import jakarta.jws.WebService;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.WebServiceException;
import tp1.Discovery;
import tp1.api.User;
import tp1.api.service.rest.RestSpreadsheets;
import tp1.api.service.soap.SheetsException;
import tp1.api.service.soap.SoapSpreadsheets;
import tp1.api.service.soap.SoapUsers;
import tp1.api.service.soap.UsersException;

@WebService(serviceName = SoapUsers.NAME, targetNamespace = SoapUsers.NAMESPACE, endpointInterface = SoapUsers.INTERFACE)
public class UsersWS implements SoapUsers {
	private final Map<String, User> users = new HashMap<String, User>();

	private String domain;

	private Discovery discover;

	private static Logger Log = Logger.getLogger(UsersWS.class.getName());
	private Client client;

	public final static int MAX_RETRIES = 3;
	public final static int RETRY_PERIOD = 1000;
	public final static int CONNECTION_TIMEOUT = 10000;
	public final static int REPLY_TIMEOUT = 600;
	public final static String passwordServers= "serversidepsswd";

	public static final String USERS_WSDL = "/users/?wsdl";

	public UsersWS() {
	}

	public UsersWS(String domain, Discovery discover) {
		this.domain = domain;
		this.discover = discover;
		ClientConfig config = new ClientConfig();
		config.property(ClientProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
		config.property(ClientProperties.READ_TIMEOUT, REPLY_TIMEOUT);
		client = ClientBuilder.newClient(config);
	}

	@Override
	public String createUser(User user) throws UsersException {
		Log.info("createUser : " + user);

		// Check if user is valid, if not throw exception
		if (user.getUserId() == null || user.getPassword() == null || user.getFullName() == null
				|| user.getEmail() == null) {
			Log.info("User object invalid.");
			throw new UsersException("Invalid user instance.");
		}

		synchronized (this) {
			// Check if userId does not exist exists, if not throw exception
			if (users.containsKey(user.getUserId())) {
				Log.info("User already exists.");
				throw new UsersException("User already exists.");
			}
			// Add the user to the map of users
			users.put(user.getUserId(), user);
		}

		return user.getUserId();
	}

	@Override
	public User getUser(String userId, String password) throws UsersException {
		// Check if user is valid, if not throw exception
		if (userId == null || password == null) {
			Log.info("UserId or passwrod null.");
			throw new UsersException("UserId or password are null.");
		}

		User user = null;

		synchronized (this) {
			user = users.get(userId);

			// Check if user exists, if yes throw exception
			if (user == null) {
				Log.info("User does not exist.");
				throw new UsersException("User does not exist.");
			}

			// Check if the password is correct, if not throw exception
			if (!user.getPassword().equals(password)) {
				Log.info("Password is incorrect.");
				throw new UsersException("Password is incorrect.");
			}
		}
		return user;
	}

	@Override
	public User updateUser(String userId, String password, User user) throws UsersException {

		if (userId == null || password == null) {
			Log.info("UserId or passwrod null.");
			throw new UsersException();
		}

		if (user == null) {
			Log.info("User does not exist.");
			throw new UsersException();
		}

		User oldUser = null;
		synchronized (this) {
			oldUser = users.get(userId);

			if (oldUser == null)
				throw new UsersException();

			if (!oldUser.getPassword().equals(password)) {
				Log.info("Password is incorrect.");
				throw new UsersException();
			}

			if (user.getUserId() == null) {
				user.setUserId(userId);
			}

			if (!user.getUserId().equals(userId)) {
				user.setUserId(userId);
			}

			if (user.getPassword() == null) {
				user.setPassword(oldUser.getPassword());
			}

			if (user.getEmail() == null) {
				user.setEmail(oldUser.getEmail());
			}

			if (user.getFullName() == null) {
				user.setFullName(oldUser.getFullName());
			}
			users.put(userId, user);
		}

		return user;
	}

	@Override
	public User deleteUser(String userId, String password) throws UsersException {
		if (userId == null || password == null) {
			Log.info("UserId or passwrod null.");
			throw new UsersException("UserId or password are null.");
		}
		User user = null;
		synchronized (this) {
			user = users.get(userId);

			if (user == null) {
				Log.info("User does not exist.");
				throw new UsersException("User does not exist.");
			}

			if (!user.getPassword().equals(password)) {
				Log.info("Password is incorrect.");
				throw new UsersException("Password is incorrect.");
			}
			users.remove(userId);
		}
		new Thread(() -> {
			deleteUserSpreadsheets(userId);
		}).start();
		return user;
	}

	/**
	 * Sends a deleteUser request to a spreadsheets server to delete said user's
	 * spreadsheets
	 * 
	 * @param userId- userID of the deleted user
	 *
	 */
	private void deleteUserSpreadsheets(String userId) {
		Log.fine("DELETE USER SPREADSHEET");
		URI[] uris = null;
		while ((uris = discover.knownUrisOf(domain, "sheets")) == null) {
			try {
				Thread.sleep(500);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if (uris[0].toString().contains("soap")) {
			SoapSpreadsheets spsheets = null;
			short retries = 0;
			boolean success = false;
			while (!success && retries < MAX_RETRIES) {
				try {
					QName QNAME = new QName(SoapSpreadsheets.NAMESPACE, SoapSpreadsheets.NAME);
					Service service = Service.create(new URL(uris[0].toString() + SpreadsheetsWS.SPREADSHEETS_WSDL),
							QNAME);
					spsheets = service.getPort(tp1.api.service.soap.SoapSpreadsheets.class);
					success = true;
				} catch (WebServiceException e) {
					System.err.println("Could not contact the server: " + e.getMessage());
					retries++;
					Log.severe(e.toString());
					try {
						Thread.sleep(RETRY_PERIOD);
					} catch (InterruptedException e2) {
						// nothing to be done here, if this happens we will just retry sooner.
					}
				} catch (MalformedURLException e4) {
					System.err.println("malformed URL");
					Log.severe(e4.toString());
				}
			}
			// Set timeouts for executing operations
			((BindingProvider) spsheets).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT,
					CONNECTION_TIMEOUT);
			((BindingProvider) spsheets).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT,
					REPLY_TIMEOUT);

			retries = 0;
			success = false;
			while (!success && retries < MAX_RETRIES) {

				try {
					spsheets.deleteUser(userId, passwordServers);
					success = true;
				} catch (SheetsException e) {
					System.out.println("Cound not get user: " + e.getMessage());
				} catch (WebServiceException wse) {
					System.out.println("Communication error.");
					wse.printStackTrace();
					retries++;
					try {
						Thread.sleep(RETRY_PERIOD);
					} catch (InterruptedException e) {
						// nothing to be done here, if this happens we will just retry sooner.
					}
					System.out.println("Retrying to execute request.");
				}
			}

		} else {
			WebTarget target = client.target(uris[0].toString()).path(RestSpreadsheets.PATH);

			short retries = 0;

			boolean stop = false;
			while (retries < MAX_RETRIES && !stop) {
				try {
					Response r = target.path("user").path(userId).request().delete();
					if (r.getStatus() == Status.NO_CONTENT.getStatusCode())
						stop = true;
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
		}
		Log.fine("DELETE USER SPREADSHEET FIM");
	}

	@Override
	public List<User> searchUsers(String pattern) throws UsersException {
		List<User> toReturn = new ArrayList<>();
		synchronized (this) {
			if (pattern == "") {
				for (User u : users.values()) {
					User aux = new User(u.getUserId(), u.getFullName(), u.getEmail(), "");
					toReturn.add(aux);
				}
				return toReturn;
			}
			for (Map.Entry<String, User> entry : users.entrySet()) {
				if (entry.getValue().getFullName().toLowerCase().contains(pattern.toLowerCase())) {
					User aux = entry.getValue();
					User toAdd = new User(aux.getUserId(), aux.getFullName(), aux.getEmail(), "");
					toReturn.add(toAdd);
				}
			}
		}
		return toReturn;
	}
}
