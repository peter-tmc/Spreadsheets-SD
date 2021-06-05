package tp1.server.rest.resources;

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

import jakarta.inject.Singleton;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.WebServiceException;
import tp1.Discovery;
import tp1.api.User;
import tp1.api.service.rest.RestSpreadsheets;
import tp1.api.service.rest.RestUsers;
import tp1.api.service.soap.SheetsException;
import tp1.api.service.soap.SoapSpreadsheets;
import tp1.server.soap.ws.SpreadsheetsWS;

@Singleton
public class UsersResource implements RestUsers {

	private final Map<String, User> users = new HashMap<String, User>();
	private String domain;
	private Discovery discover;
	private Client client;
	private final static int MAX_RETRIES = 3;
	private final static int RETRY_PERIOD = 1000;
	private final static int CONNECTION_TIMEOUT = 10000;
	private final static int REPLY_TIMEOUT = 600;
	private  String passwordServers;
	private static Logger Log = Logger.getLogger(UsersResource.class.getName());

	public UsersResource() {
	}

	public UsersResource(String domain, Discovery discover, String passwordServers) {
		this.domain = domain;
		this.discover = discover;
		ClientConfig config = new ClientConfig();
		config.property(ClientProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
		config.property(ClientProperties.READ_TIMEOUT, REPLY_TIMEOUT);
		client = ClientBuilder.newClient(config);
		this.passwordServers = passwordServers;
	}

	@Override
	public String createUser(User user) {
		Log.info("createUser : " + user);

		// Check if user is valid, if not return HTTP BAD REQUEST (400)
		if (user.getUserId() == null || user.getPassword() == null || user.getFullName() == null
				|| user.getEmail() == null) {
			Log.info("User object invalid.");
			throw new WebApplicationException(Status.BAD_REQUEST);
		}
		synchronized (this) {
			// Check if userId does not exist, if it exists return HTTP CONFLICT (409)
			if (users.containsKey(user.getUserId())) {
				Log.info("User already exists.");
				throw new WebApplicationException(Status.CONFLICT);
			}

			// Add the user to the map of users
			users.put(user.getUserId(), user);
		}
		return user.getUserId();
	}

	@Override
	public User getUser(String userId, String password) {
		Log.info("getUser : user = " + userId + "; pwd = " + password);
		User user = null;
		synchronized (this) {
			user = users.get(userId);

			// Check if user exists
			if (user == null) {
				Log.info("User does not exist.");
				throw new WebApplicationException(Status.NOT_FOUND);
			}

			// Check if the password is correct
			if (!user.getPassword().equals(password)) {
				Log.info("Password is incorrect.");
				throw new WebApplicationException(Status.FORBIDDEN);
			}
		}
		return user;
	}

	@Override
	public User updateUser(String userId, String password, User user) {
		Log.info("updateUser : user = " + userId + "; pwd = " + password + " ; user = " + user);
		if (userId == null || password == null) {
			Log.info("UserId or passwrod null.");
			throw new WebApplicationException(Status.BAD_REQUEST);
		}

		synchronized (this) {
			User userAux = users.get(userId);

			// Check if user exists
			if (userAux == null) {
				Log.info("User does not exist.");
				throw new WebApplicationException(Status.NOT_FOUND);
			}

			// Check if the password is correct
			if (!userAux.getPassword().equals(password)) {
				Log.info("Password is incorrect.");
				throw new WebApplicationException(Status.FORBIDDEN);
			}

			if (user.getUserId() == null) {
				user.setUserId(userAux.getUserId());
			}

			if (user.getPassword() == null) {
				user.setPassword(userAux.getPassword());
			}

			if (user.getEmail() == null) {
				user.setEmail(userAux.getEmail());
			}

			if (user.getFullName() == null) {
				user.setFullName(userAux.getFullName());
			}

			if (!user.getUserId().equals(userId)) {
				return userAux;
			}

			users.put(userId, user);
		}
		return user;
	}

	@Override
	public User deleteUser(String userId, String password) {
		Log.info("deleteUser : user = " + userId + "; pwd = " + password);
		User user = null;
		synchronized (this) {
			user = users.get(userId);

			// Check if user exists
			if (user == null) {
				Log.info("User does not exist.");
				throw new WebApplicationException(Status.NOT_FOUND);
			}

			// Check if the password is correct
			if (!user.getPassword().equals(password)) {
				Log.info("Password is incorrect.");
				throw new WebApplicationException(Status.FORBIDDEN);
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
					System.out.println("Cound not delete spreadsheets: " + e.getMessage());
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
					Response r = target.path("user").path(userId).queryParam("password", passwordServers)
							.request().delete();
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
	}

	@Override
	public List<User> searchUsers(String pattern) {
		Log.info("searchUsers : pattern = " + pattern);
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
