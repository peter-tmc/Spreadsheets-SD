package tp1.server.rest.resources;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javax.xml.namespace.QName;

import com.google.gson.Gson;
import com.sun.xml.ws.client.BindingProviderProperties;
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
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.WebServiceException;
import tp1.Discovery;
import tp1.api.GoogleSheetsReturn;
import tp1.api.Spreadsheet;
import tp1.api.engine.AbstractSpreadsheet;
import tp1.api.service.rest.RestSpreadsheets;
import tp1.api.service.rest.RestUsers;
import tp1.api.service.soap.SheetsException;
import tp1.api.service.soap.SoapSpreadsheets;
import tp1.api.service.soap.SoapUsers;
import tp1.api.service.soap.UsersException;
import tp1.impl.engine.SpreadsheetEngineImpl;
import tp1.server.soap.ws.SpreadsheetsWS;
import tp1.server.soap.ws.UsersWS;
import tp1.util.CellRange;

public class SpreadsheetResource implements RestSpreadsheets {

	public final static int MAX_RETRIES = 3;
	public final static int RETRY_PERIOD = 1000;
	public final static int CONNECTION_TIMEOUT = 10000;
	public final static int REPLY_TIMEOUT = 600;
	private static final String GOOGLE_SHEETS = "sheets.googleapis.com";
	private static final String HTTPS_GOOGLE_SHEET = "https://sheets.googleapis.com/";
	private static final int VALUES_CACHE_EXPIRATION = 19;
	private int counter = 0;
	private final Map<String, Spreadsheet> spreadsheets = new HashMap<>();
	private static Logger Log = Logger.getLogger(SpreadsheetResource.class.getName());
	Discovery discovery;
	private String domain;
	private String serverURI;
	private Map<String, Map<String, CacheEntry>> sheetsValuesCache = new HashMap<>(); //guarda <sheetURL, <range, values>>
	private Client client;
	private Map<String, Set<String>> usersSpreadsheets = new HashMap<>();
	public String passwordServers;
	private Map<String, Instant> twSheets = new HashMap<>(); 
	private String googleKey = "AIzaSyCcdXajR6S0xAaMgyPBA-Js_MWFrQFqB8A";

	public SpreadsheetResource() {
	}

	public SpreadsheetResource(String domain, String serverSecret, String serverURI, Discovery discover) {
		this.domain = domain;
		this.serverURI = serverURI;
		this.discovery = discover;
		ClientConfig config = new ClientConfig();
		config.property(ClientProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
		config.property(ClientProperties.READ_TIMEOUT, REPLY_TIMEOUT);
		client = ClientBuilder.newClient(config);
		passwordServers = serverSecret;
	}

	@Override
	public String createSpreadsheet(Spreadsheet sheet, String password) {
		if (!(sheet.getSheetId() == null && sheet.getSheetURL() == null && sheet.getColumns() >= 0
				&& sheet.getRows() >= 0)) {
			throw new WebApplicationException(Status.BAD_REQUEST);
		}
		String owner = sheet.getOwner();

		int response = 0;
		try {
			response = getUser(domain, owner, password);
			if (response != Status.OK.getStatusCode()) {
				throw new WebApplicationException(Status.BAD_REQUEST);
			}
		} catch (UsersException e1) {
			throw new WebApplicationException(Status.BAD_REQUEST);
		}

		String spreadID = null;

		spreadID = String.format("%s-%d", owner, counter++);
		sheet.setSheetId(spreadID);
		String url = String.format("%s/spreadsheets/%s", serverURI, spreadID);
		sheet.setSheetURL(url);
		Set<String> sw = sheet.getSharedWith();
		if (sw == null) {
			sw = new HashSet<>();
			sheet.setSharedWith(sw);
		}
		synchronized (this) {
			Set<String> aux = usersSpreadsheets.get(owner);
			if (aux == null) {
				aux = new HashSet<String>();
			}
			aux.add(spreadID);
			usersSpreadsheets.put(owner, aux);
			spreadsheets.put(spreadID, sheet);
			twSheets.put(spreadID, Instant.now());
		}
		return spreadID;
	}

	@Override
	public void deleteSpreadsheet(String sheetId, String password) {
		String owner = sheetId.split("-")[0];

		int response = 0;
		try {
			response = getUser(domain, owner, password);
			if (response != Status.OK.getStatusCode()) {
				throw new WebApplicationException(Status.FORBIDDEN);
			}
		} catch (UsersException e1) {
			throw new WebApplicationException(Status.BAD_REQUEST);
		}

		Spreadsheet sheet = null;
		synchronized (this) {
			sheet = spreadsheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND);
			}
			Set<String> aux = usersSpreadsheets.get(owner);
			if (aux != null)
				aux.remove(sheetId);
			spreadsheets.remove(sheetId);
			twSheets.remove(sheetId);
		}
	}

	@Override
	public Spreadsheet getSpreadsheet(String sheetId, String userId, String password) {
		int response = 0;
		try {
			response = getUser(domain, userId, password);
			if (response != Status.OK.getStatusCode()) {
				throw new WebApplicationException(Status.fromStatusCode(response));
			}
		} catch (UsersException e1) {
			throw new WebApplicationException(Status.BAD_REQUEST);
		}

		Spreadsheet sheet = null;
		synchronized (this) {
			sheet = spreadsheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND);
			}
			String userAux = String.format("%s@%s", userId, domain);
			if (!(sheet.getSharedWith().contains(userAux) || sheet.getOwner().equals(userId))) {
				throw new WebApplicationException(Status.FORBIDDEN);
			}
			return sheet;
		}
	}

	@Override

	public String[][] getSpreadsheetValues(String sheetId, String userId, String password) {
		if (userId == null || sheetId == null) {
			throw new WebApplicationException(Status.BAD_REQUEST);
		}

		String[][] values;
		int response = 0;
		try {
			response = getUser(domain, userId, password);
			if (response != Status.OK.getStatusCode()) {
				throw new WebApplicationException(Status.fromStatusCode(response));
			}
		} catch (UsersException e1) {
			throw new WebApplicationException(Status.BAD_REQUEST);
		}

		Spreadsheet sheet;
		synchronized (this) {
			sheet = spreadsheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND);
			}
			String userAux = String.format("%s@%s", userId, domain);
			if (!(sheet.getSharedWith().contains(userAux) || sheet.getOwner().equals(userId))) {
				throw new WebApplicationException(Status.FORBIDDEN);
			}
		}

		values = SpreadsheetEngineImpl.getInstance().computeSpreadsheetValues(new AbstractSpreadsheet() {
			@Override
			public int rows() {
				return sheet.getRows();
			}

			@Override
			public int columns() {
				return sheet.getColumns();
			}

			@Override
			public String sheetId() {
				return sheet.getSheetId();
			}

			@Override
			public String cellRawValue(int row, int col) {
				try {
					return sheet.getCellRawValue(row, col);
				} catch (IndexOutOfBoundsException e) {
					return "#ERR?";
				}
			}

			@Override
			public String[][] getRangeValues(String sheetURL, String range) {

				String[][] val;
				try {
					val = getRangeValuesRequest(sheetURL, range, sheet);
				} catch (SheetsException e) {
					e.printStackTrace();
					return null;
				}

				return val;
			}
		});

		return values;
	}

	@Override
	public void updateCell(String sheetId, String cell, String rawValue, String userId, String password) {
		if (sheetId == null || userId == null) {
			throw new WebApplicationException(Status.BAD_REQUEST);
		}

		int response = 0;
		try {
			response = getUser(domain, userId, password);
			if (response != Status.OK.getStatusCode()) {
				throw new WebApplicationException(Status.fromStatusCode(response));
			}
		} catch (UsersException e1) {
			throw new WebApplicationException(Status.BAD_REQUEST);
		}

		Spreadsheet sheet = null;
		synchronized (this) {
			sheet = spreadsheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND);
			}
			String userAux = String.format("%s@%s", userId, domain);
			if (!(sheet.getSharedWith().contains(userAux) || sheet.getOwner().equals(userId))) {
				throw new WebApplicationException(Status.BAD_REQUEST);
			}
			sheet.setCellRawValue(cell, rawValue);
			twSheets.put(sheetId, Instant.now());
		}
	}

	@Override
	public void shareSpreadsheet(String sheetId, String userId, String password) {
		String[] str = userId.split("@");
		int response = 0;
		try {
			response = getUser(str[1], str[0], ""); // request with empty password just to check if the user exists
			if (response != Status.FORBIDDEN.getStatusCode() && response != Status.OK.getStatusCode()) {
				throw new WebApplicationException(Status.fromStatusCode(response));
			}
		} catch (UsersException e1) {
			if (e1.getMessage().equals("User does not exist."))
				throw new WebApplicationException(Status.NOT_FOUND);
		}

		response = 0;
		String owner = sheetId.split("-")[0];
		try {
			response = getUser(domain, owner, password);
			if (response != Status.OK.getStatusCode()) {
				throw new WebApplicationException(Status.fromStatusCode(response));
			}
		} catch (UsersException e1) {
			throw new WebApplicationException(Status.BAD_REQUEST);
		}

		synchronized (this) {
			Spreadsheet sheet = spreadsheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND);
			}

			Set<String> set = sheet.getSharedWith();
			if (set.contains(userId)) {
				throw new WebApplicationException(Status.CONFLICT);
			}
			set.add(userId);
			sheet.setSharedWith(set);
			twSheets.put(sheetId, Instant.now());
		}
	}

	@Override
	public void unshareSpreadsheet(String sheetId, String userId, String password) {
		String[] str = userId.split("@");
		int response = 0;
		try {
			response = getUser(str[1], str[0], ""); // request with empty password just to check if the user exists
			if (response != Status.FORBIDDEN.getStatusCode() && response != Status.OK.getStatusCode()) {
				throw new WebApplicationException(Status.fromStatusCode(response));
			}
		} catch (UsersException e1) {
			if (e1.getMessage().equals("User does not exist."))
				throw new WebApplicationException(Status.NOT_FOUND);
		}

		response = 0;
		String owner = sheetId.split("-")[0];
		try {
			response = getUser(domain, owner, password);
			if (response != Status.OK.getStatusCode()) {
				throw new WebApplicationException(Status.fromStatusCode(response));
			}
		} catch (UsersException e1) {
			throw new WebApplicationException(Status.BAD_REQUEST);
		}

		synchronized (this) {
			Spreadsheet sheet = spreadsheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND);
			}

			Set<String> set = sheet.getSharedWith();
			if (!set.contains(userId)) {
				throw new WebApplicationException(Status.NOT_FOUND);
			}
			set.remove(userId);
			twSheets.put(sheetId, Instant.now());
		}
	}

	/**
	 * Sends a getUser request to a users server
	 * 
	 * @param userId   - user to be acquired
	 * @param domain   - user's domain
	 * @param password - user's password
	 * 
	 * @return if the server we are trying to connect is REST this return has the
	 *         status code of the response if the server we are trying to connect is
	 *         SOAP this return is 200
	 * 
	 * @throws UsersException if the server we are trying to connect is SOAP and the
	 *                        request answered with an exception
	 */
	private int getUser(String domain, String userId, String password) throws UsersException {
		URI[] uris = null;
		while ((uris = discovery.knownUrisOf(domain, "users")) == null) {
			try {
				Thread.sleep(500);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if (uris[0].toString().contains("soap")) {
			SoapUsers users = null;
			short retries = 0;
			boolean success = false;
			while (!success && retries < MAX_RETRIES) {
				try {
					QName QNAME = new QName(SoapUsers.NAMESPACE, SoapUsers.NAME);
					Service service = Service.create(new URL(uris[0].toString() + UsersWS.USERS_WSDL), QNAME);
					users = service.getPort(SoapUsers.class);
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
			((BindingProvider) users).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT,
					CONNECTION_TIMEOUT);
			((BindingProvider) users).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);

			retries = 0;
			while (retries < MAX_RETRIES) {

				try {
					users.getUser(userId, password);
					return 200;
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

			WebTarget target = client.target(uris[0].toString()).path(RestUsers.PATH);

			short retries = 0;

			while (retries < MAX_RETRIES) {
				try {
					Response r = target.path(userId).queryParam("password", password).request()
							.accept(MediaType.APPLICATION_JSON).get();
					return r.getStatus();
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
			throw new WebApplicationException(Status.REQUEST_TIMEOUT);
		}
		return -1;
	}

	@Override
	public SPRange getSpreadsheetValuesRange(String sheetId, String range, String userId, String password, String timestamp) {
		Instant tw = twSheets.get(sheetId);
		if (tw == null) {
			System.out.println("importrange3");
			throw new WebApplicationException(Status.NOT_FOUND);
		}
		if(!timestamp.equals("")) {
			Instant twReceived = Instant.parse(timestamp);
			if(twReceived.equals(tw)) {
				SPRange spr = new SPRange();
				spr.setTimestamp(twReceived.toString());
				return spr;
			}
		}
		
		if (!password.equals(passwordServers)) {
			throw new WebApplicationException(Status.FORBIDDEN);
		}
		String[][] values;
		System.out.println("userID:" + userId);
		System.out.println("sheetID:" + sheetId);
		System.out.println("range:" + range);
		if (userId == null || sheetId == null) {
			System.out.println("importrange1");
			throw new WebApplicationException(Status.BAD_REQUEST);
		}

		Spreadsheet sheet;
		synchronized (this) {
			sheet = spreadsheets.get(sheetId);
			
			System.out.println("importrange2");
			if (sheet == null) {
				
				System.out.println("importrange3");
				throw new WebApplicationException(Status.NOT_FOUND);
			}
			String aux = userId.split("@")[0];
			if (!(sheet.getSharedWith().contains(userId) || sheet.getOwner().equals(aux))) {
				System.out.println("importrange4");
				throw new WebApplicationException(Status.FORBIDDEN);
			}
		}
		System.out.println("importrange5");
		CellRange cr = new CellRange(range);
		String[][] rangeVal = cr.extractRangeValuesFrom(sheet.getRawValues());
		values = SpreadsheetEngineImpl.getInstance().computeSpreadsheetValues(new AbstractSpreadsheet() {
			@Override
			public int rows() {
				return cr.rows();
			}

			@Override
			public int columns() {
				return cr.cols();
			}

			@Override
			public String sheetId() {
				return sheet.getSheetId();
			}

			@Override
			public String cellRawValue(int row, int col) {
				try {
					return rangeVal[row][col];
				} catch (IndexOutOfBoundsException e) {
					return "#ERROR?";
				}
			}

			@Override
			public String[][] getRangeValues(String sheetURL, String range) {

				String[][] val;
				try {
					val = getRangeValuesRequest(sheetURL, range, sheet);
				} catch (SheetsException e) {
					e.printStackTrace();
					return null;
				}
				return val;
			}
		});
		System.out.println("importrange6");
		SPRange res = new SPRange(tw.toString(), sheet.getSharedWith(), values);
		System.out.println("importrange7");
		System.out.println(res.toString());
		return res;
	}
	protected void putValuesInCache(String sheetURL, String range, SPRange val) {
		synchronized (this) {
			System.out.println("put1");
			Map<String, CacheEntry> aux = sheetsValuesCache.get(sheetURL);
			System.out.println("put2");
			if (aux == null) {
				aux = new HashMap<>();
				sheetsValuesCache.put(sheetURL, aux);
			}
			CacheEntry entry = aux.get(range);
			if(entry == null) {
				entry = new CacheEntry(val);
				aux.put(range, entry);
			}
			if(!val.getTimestamp().equals(entry.getTw().toString()))
				entry = new CacheEntry(val);
			else
				entry.setTc(Instant.now());
		}
	}

	/**
	 * Sends a getSpreadsheetValuesRange request to a spreadsheets server
	 * 
	 * @param sheetURL- sheet's url from where the values will be taken
	 * @param range     - range of the values to be taken
	 * @param sheet     - original sheet
	 * 
	 * @return the values of the sheet in the specified sheetURL within a specified
	 * 
	 * @throws UsersException if the server we are trying to connect is SOAP and the
	 *                        request answered with an exception
	 */
	private String[][] getRangeValuesRequest(String sheetURL, String range, Spreadsheet sheet) throws SheetsException {
		
		System.out.println("reqrange1");
		Map<String, CacheEntry> mapaux = sheetsValuesCache.get(sheetURL);
		CacheEntry ce = null;
		String tc = "";
		if(mapaux != null) {
			ce = mapaux.get(range);
			System.out.println("reqrange2");
			
			if(ce != null) {
				System.out.println("cache1");
				tc = ce.getTc().toString();
				System.out.println("cache2");
				//Duration timeBetween = Duration.between(Instant.now(), ce.getTc());
				System.out.println("cache3");
				//if(timeBetween.getSeconds() < VALUES_CACHE_EXPIRATION) {
					
				if(!Instant.now().isAfter(ce.getTc().plusSeconds(VALUES_CACHE_EXPIRATION))) {
					System.out.println("cache4");
					return ce.getValues();
				}
			}
		}
			
		if (sheetURL.contains("rest")) {
			
			System.out.println("req1");
			System.out.println(sheetURL);
			WebTarget target = client.target(sheetURL).path("range");
			System.out.println("req2");
			short retries = 0;
			while (retries < MAX_RETRIES) {
				try {
					String userAux = String.format("%s@%s", sheet.getOwner(), domain);
					System.out.println("req3");
					//String sheetId, String range, String userId, String password, String timestamp
					Response r = target.queryParam("range", range).queryParam("userId", userAux)
							.queryParam("password", passwordServers).queryParam("timestamp", tc)
							.request()
							.accept(MediaType.APPLICATION_JSON)
							.get();	
					System.out.println("req4");
					System.out.println(r.toString());
					Gson json = new Gson();
					if (r.getStatus() == Status.OK.getStatusCode() && r.hasEntity()) {
						System.out.println("req5");
						//VAI GSON VAI GSON VAI GSON
						SPRange val = json.fromJson(r.readEntity(String.class), SPRange.class);
						System.out.println("req6");
						putValuesInCache(sheetURL, range, val);
						//Log.severe(val.toString());
						return val.getValues();
					} else {
						System.err.println(r.getStatus());
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
		} else {
			if (sheetURL.contains("soap")) {
				SoapSpreadsheets spsheets = null;
				short retries = 0;
				boolean success = false;
				String[] str = sheetURL.split("/spreadsheets/");
				while (!success && retries < MAX_RETRIES) {
					try {
						QName QNAME = new QName(SoapSpreadsheets.NAMESPACE, SoapSpreadsheets.NAME);
						Service service = Service.create(new URL(str[0] + SpreadsheetsWS.SPREADSHEETS_WSDL), QNAME);
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
				while (retries < MAX_RETRIES) {

					try {
						String userAux = String.format("%s@%s", sheet.getOwner(), domain);
						String[][] values = spsheets.getSpreadsheetValuesRange(str[1], range, userAux);
						//putValuesInCache(sheetURL, range, values);
						return values;
					} catch (SheetsException e) {
						System.out.println("Cound not get spreadsheet: " + e.getMessage());
						throw e;
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
				if (sheetURL.contains(GOOGLE_SHEETS)) {
					String sheetId = sheetURL.split(HTTPS_GOOGLE_SHEET)[1];
					WebTarget target = client.target(HTTPS_GOOGLE_SHEET).path("v4/spreadsheets");

					short retries = 0;
					while (retries < MAX_RETRIES) {
						try {
							Response r = target.path(sheetId).path("values").path(range).queryParam("key", googleKey)
									.request().accept(MediaType.APPLICATION_JSON).get();
							if (r.getStatus() == Status.OK.getStatusCode() && r.hasEntity()) {
								String[][] val = r.readEntity(GoogleSheetsReturn.class).getValues();
								//putValuesInCache(sheetURL, range, val);
								//Log.severe(val.toString());
								return val;
							} else {
								System.err.println(r.getStatus());
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

					/*try {
						HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
						com.google.api.client.json.JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
						GoogleCredential credential = null;
						Sheets sheetsService = new Sheets.Builder(httpTransport, jsonFactory, credential)
								.setApplicationName("Google-SheetsSample/0.1").build();
						Sheets.Spreadsheets.Values.Get request;
						request = sheetsService.spreadsheets().values().get(sheetId, range);
						
						List<List<Object>> valuesList = response.getValues();
						String[][] values = new String[valuesList.size()][valuesList.get(0).size()];
						for (int i = 0; i < valuesList.size(); i++) {
							List<Object> row = valuesList.get(i);
						
							for (int j = 0; j < row.size(); j++)
								values[i][j] = (String) row.get(j);
						}
					} catch (IOException e) {
						e.printStackTrace();
					} catch (GeneralSecurityException e) {
						e.printStackTrace();
					}
					*/
				}
			}
		}
		return getValuesInCache(sheetURL, range);
	}

	protected String[][] getValuesInCache(String sheetURL, String range) {
		synchronized (this) {
			Map<String, CacheEntry> aux = sheetsValuesCache.get(sheetURL);
			return aux.get(range).getValues();
		}
	}

	@Override
	public void deleteUser(String userId, String password) {
		if (!password.equals(passwordServers)) {
			throw new WebApplicationException(Status.FORBIDDEN);
		}
		synchronized (this) {
			Set<String> sheetsUser = usersSpreadsheets.remove(userId);
			if (sheetsUser != null) {
				for (String spId : sheetsUser) {
					spreadsheets.remove(spId);
					twSheets.remove(spId);
				}
			}
		}
	}
}