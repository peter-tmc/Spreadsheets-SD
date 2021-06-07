package tp1.server.replication.rest.resources;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
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
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.Response.Status;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.WebServiceException;
import tp1.Discovery;
import tp1.api.GoogleSheetsReturn;
import tp1.api.Spreadsheet;
import tp1.api.engine.AbstractSpreadsheet;
import tp1.api.service.rest.RestRepSpreadsheets;
import tp1.api.service.rest.RestSpreadsheets;
import tp1.api.service.rest.RestUsers;
import tp1.api.service.soap.SheetsException;
import tp1.api.service.soap.SoapSpreadsheets;
import tp1.api.service.soap.SoapUsers;
import tp1.api.service.soap.UsersException;
import tp1.impl.cache.CacheEntry;
import tp1.impl.cache.SPRange;
import tp1.impl.engine.SpreadsheetEngineImpl;
import tp1.requests.RestClientRep;
import tp1.server.replication.utils.Operation;
import tp1.server.replication.utils.OperationType;
import tp1.server.replication.utils.ReplicationManager;
import tp1.server.replication.zookeeper.ZookeeperProcessor;
import tp1.server.soap.ws.SpreadsheetsWS;
import tp1.server.soap.ws.UsersWS;
import tp1.util.CellRange;
import tp1.util.DiscoveryURI;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import java.util.List;

public class SpreadsheetRepResource implements RestRepSpreadsheets {

	private final static int MAX_RETRIES = 3;
	private final static int RETRY_PERIOD = 1000;
	private final static int CONNECTION_TIMEOUT = 10000;
	private final static int REPLY_TIMEOUT = 600;
	private static final String GOOGLE_SHEETS = "sheets.googleapis.com";
	private static final String HTTPS_GOOGLE_SHEET = "https://sheets.googleapis.com/";
	private static final int VALUES_CACHE_EXPIRATION = 20;
	private static final long TIME_TOLERANCE = 5000;
	private final Map<String, Spreadsheet> spreadsheets = new HashMap<>();
	private static Logger Log = Logger.getLogger(SpreadsheetRepResource.class.getName());
	private Discovery discovery;
	private String domain;
	private String serverURI;
	private Map<String, Map<String, CacheEntry>> sheetsValuesCache = new HashMap<>(); //guarda <sheetURL, <range, values>>
	private Client client;
	private Map<String, Set<String>> usersSpreadsheets = new HashMap<>();
	private String passwordServers;
	private Map<String, Instant> twSheets = new HashMap<>();
	private String googleKey = "AIzaSyCcdXajR6S0xAaMgyPBA-Js_MWFrQFqB8A";
	private ZookeeperProcessor zk;
	private String path;
	private boolean primary;
	private URI primaryURI;
	private RestClientRep rClientRep;
	private ReplicationManager repManager;
	private Gson json;
	private boolean updating;

	private class Executer {
		private boolean executed = false;
		Instant now;

		public Executer() {
			now = Instant.now();
		}

		public boolean getExecuted() {
			return executed;
		}

		public Instant getNow() {
			return now;
		}

		public void run(List<String> urisSec, Operation op) {
			for (String uri : urisSec) {
				new Thread(() -> {
					/* System.out.println("execRun"); */
					executed = executed || (rClientRep.secondaryExecute(passwordServers, uri, op));
					/* System.out.println("execRun:::::::::: " + executed); */
				}).start();
			}
		}
	}

	public SpreadsheetRepResource() {
	}

	public SpreadsheetRepResource(String domain, String serverSecret, String serverURI, Discovery discover,
			ZookeeperProcessor zk, String path, ReplicationManager repManager) {
		/* System.err.println("construtor"); */
		this.domain = domain;
		this.serverURI = serverURI;
		this.discovery = discover;
		this.repManager = repManager;
		ClientConfig config = new ClientConfig();
		config.property(ClientProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
		config.property(ClientProperties.READ_TIMEOUT, REPLY_TIMEOUT);
		client = ClientBuilder.newClient(config);
		passwordServers = serverSecret;
		this.zk = zk;
		this.path = path;
		this.primary = false;
		this.primaryURI = URI.create(serverURI);
		rClientRep = new RestClientRep(serverSecret);
		json = new Gson();
		//System.out.println("antesgetchildren");
		String pathFather = path.split("/sheets")[0];
		updating = false;
		//System.out.println(pathFather);
		zk.getChildren(pathFather, new Watcher() {
			//TODO fazer isto a mao a primeira vez
			@Override
			public void process(WatchedEvent event) {
				List<String> lst = zk.getChildren(pathFather, this);
				List<String> znodes = new LinkedList<>();
				int minNum = Integer.MAX_VALUE;
				String minPath = "";
				for (String s : lst) {
					int curr = Integer.parseInt(s.split("_")[1]);
					if (curr < minNum) {
						minNum = curr;
						minPath = s;
					}
					String childPath = pathFather + "/" + s;
					znodes.add(childPath);
				}
				URI oldPrimaryURI = primaryURI;
				String auxPath = pathFather + "/" + minPath;
				primaryURI = zk.readURI(auxPath);
				if (auxPath.equals(path))
					primary = true;
				List<String> replicasURI = new LinkedList<>();
				for (String node : znodes) {
					if (!node.equals(path)) {
						URI uri = zk.readURI(node);
						replicasURI.add(uri.toString());
					}
				}
				repManager.setReplicasURI(replicasURI);
				long biggestVersion = repManager.getVersion();
				String uriOfMostUpdated = null;
				/* if (!primaryURI.equals(oldPrimaryURI)) {
					//TODO sync dos secundarios com o novo 
					for (String uriAux : lstURIs) {
						long v = rClientRep.getVersionsRep(uriAux);
						if (v > biggestVersion) {
							biggestVersion = v;
							uriOfMostUpdated = uriAux;
						}
					}
					//executeOps(rClientRep.getVersionOperationsRep(uriOfMostUpdated, repManager.getVersion(), serverSecret));
				} */

			}
		});
		List<String> lst = zk.getChildren(pathFather);
		List<String> znodes = new LinkedList<>();
		int minNum = Integer.MAX_VALUE;
		String minPath = "";
		for (String s : lst) {
			int curr = Integer.parseInt(s.split("_")[1]);
			if (curr < minNum) {
				minNum = curr;
				minPath = s;
			}
			String childPath = pathFather + "/" + s;
			znodes.add(childPath);
		}
		URI oldPrimaryURI = primaryURI;
		String auxPath = pathFather + "/" + minPath;
		primaryURI = zk.readURI(auxPath);
		if (auxPath.equals(path))
			primary = true;
		List<String> replicasURI = new LinkedList<>();
		for (String node : znodes) {
			if (!node.equals(path)) {
				URI uri = zk.readURI(node);
				replicasURI.add(uri.toString());
			}
		}
		repManager.setReplicasURI(replicasURI);
		long biggestVersion = repManager.getVersion();
		/* String uriOfMostUpdated = null;
		if (!primaryURI.equals(oldPrimaryURI)) {
			//TODO sync dos secundarios com o novo 
			for (String uriAux : lstURIs) {
				System.out.println("chegou");
				long v = rClientRep.getVersionsRep(uriAux);
				if (v > biggestVersion) {
					biggestVersion = v;
					uriOfMostUpdated = uriAux;
				}
			}
			//executeOps(rClientRep.getVersionOperationsRep(uriOfMostUpdated, repManager.getVersion(), serverSecret));
		} */
	}

	private void executeOps(SortedSet<Operation> operations) {
		synchronized (this) {
			for (Operation op : operations) {
				executeOp(op);
			}
		}
	}

	@Override
	public String createSpreadsheet(Spreadsheet sheet, String password) {
		//TODO fazer a cena do primario mandar a operacao aos secundarios e avancar quando pelo menos um secundario tenha executado
		if (primary) {
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

			spreadID = String.format("%s_%s", owner, UUID.randomUUID().toString());

			sheet.setSheetId(spreadID);
			Set<String> sw = sheet.getSharedWith();
			if (sw == null) {
				sw = new HashSet<>();
				sheet.setSharedWith(sw);
			}
			String url = String.format("%s/spreadsheets/%s", serverURI, spreadID);
			sheet.setSheetURL(url);
			boolean executed = false;
			OperationType type = OperationType.CREATE;
			List<String> list = new ArrayList<>(2);
			list.add(json.toJson(sheet));
			list.add(password);
			synchronized (this) {
				long v = repManager.getVersion();
				System.out.println("v antes: " + v);
				Operation op = new Operation(type, list, ++v);
				System.out.println("v depois: "+v);
				System.out.println("op version: " + op.getVersion());
				Executer exec = new Executer();
				List<String> urisSec = repManager.getReplicasURI();
				exec.run(urisSec, op);
				while (exec.getNow().plusMillis(TIME_TOLERANCE).isAfter(Instant.now())) {
					if (exec.getExecuted()) {
						//TODO se falhar pode se meter os pedidos aos secundarios dentro do synchronized
						Set<String> aux = usersSpreadsheets.get(owner);
						if (aux == null) {
							aux = new HashSet<String>();
						}
						aux.add(spreadID);
						usersSpreadsheets.put(owner, aux);
						spreadsheets.put(spreadID, sheet);
						twSheets.put(spreadID, Instant.now());
						System.out.println("antes: " + repManager.getVersion());
						repManager.addOperation(op);
						System.out.println("depois: " + repManager.getVersion());
						System.out.println();
						return spreadID;
					}
				}
				//TODO se passar demasiado tempo desde as respostas dos secundarios assumir que falharam e falhar a operacao tbm
			}
			throw new WebApplicationException(Status.GATEWAY_TIMEOUT);
		} else {
			throw new WebApplicationException(
					Response.temporaryRedirect(UriBuilder.fromUri(primaryURI).queryParam("password", password).build())
							.build());
		}
	}

	@Override
	public void deleteSpreadsheet(String sheetId, String password) {
		if (primary) {
			String owner = sheetId.split("_")[0];
			int response = 0;
			try {
				response = getUser(domain, owner, password);
				if (response != Status.OK.getStatusCode()) {
					throw new WebApplicationException(Status.FORBIDDEN);
				}
			} catch (UsersException e1) {
				throw new WebApplicationException(Status.BAD_REQUEST);
			}

			List<String> urisSec = repManager.getReplicasURI();
			boolean executed = false;
			OperationType type = OperationType.DELETE;
			List<String> list = new ArrayList<>(2);
			list.add(sheetId);
			list.add(password);

			synchronized (this) {
				long v = repManager.getVersion();
				Operation op = new Operation(type, list, ++v);
				Spreadsheet sheet = null;
				sheet = spreadsheets.get(sheetId);
				if (sheet == null) {
					throw new WebApplicationException(Status.NOT_FOUND);
				}
				Executer exec = new Executer();
				exec.run(urisSec, op);
				while (exec.getNow().plusMillis(TIME_TOLERANCE).isAfter(Instant.now())) {
					if (exec.getExecuted()) {
						Set<String> aux = usersSpreadsheets.get(owner);
						if (aux != null)
							aux.remove(sheetId);
						spreadsheets.remove(sheetId);
						twSheets.remove(sheetId);
						repManager.addOperation(op);
						return;
					}
				}
			}
		} else
			throw new WebApplicationException(Response
					.temporaryRedirect(
							UriBuilder.fromUri(primaryURI).path(sheetId).queryParam("password", password).build())
					.build());
	}

	@Override
	public Spreadsheet getSpreadsheet(Long version, String sheetId, String userId, String password) {
		//TODO verificar versoes
		if (!updating) {
			long v = version.longValue();
			if (repManager.getVersion() < v) {
				new Thread(() -> {
					updateReplica();
				}).start();
				throw new WebApplicationException(Response
						.temporaryRedirect(UriBuilder.fromUri(primaryURI).path(sheetId).queryParam("userId", userId)
								.queryParam("password", password).build())
						.header(RestSpreadsheets.HEADER_VERSION, version).build());
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
					throw new WebApplicationException(Status.FORBIDDEN);
				}
				return sheet;
			}
		} else
			throw new WebApplicationException(Response
					.temporaryRedirect(UriBuilder.fromUri(primaryURI).path(sheetId).queryParam("userId", userId)
							.queryParam("password", password).build())
					.header(RestSpreadsheets.HEADER_VERSION, version).build());
	}

	@Override
	public String[][] getSpreadsheetValues(Long version, String sheetId, String userId, String password) {
		System.out.println("versao que chegou: " + version.longValue());
		System.out.println("versao que temos: " + repManager.getVersion());
		System.out.println("esta a dar update? :" + updating);
		if (!updating) {
			long v = version.longValue();
			if (repManager.getVersion() < v) {
				System.out.println("entrou no if");
				new Thread(() -> {
					updateReplica();
				}).start();
				throw new WebApplicationException(Response
						.temporaryRedirect(UriBuilder.fromUri(primaryURI).path(sheetId).path("values")
								.queryParam("userId", userId).queryParam("password", password).build())
						.header(RestSpreadsheets.HEADER_VERSION, version).build());
			}
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
		} else
			throw new WebApplicationException(Response
					.temporaryRedirect(UriBuilder.fromUri(primaryURI).path(sheetId).queryParam("userId", userId)
							.queryParam("password", password).build())
					.header(RestSpreadsheets.HEADER_VERSION, version).build());

	}

	private void updateReplica() {
		updating = true;
		executeOps(rClientRep.getVersionOperationsRep(primaryURI.toString(), repManager.getVersion(), passwordServers));
		updating = false;
	}

	@Override
	public void updateCell(String sheetId, String cell, String rawValue, String userId, String password) {
		if (primary) {
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

			boolean executed = false;
			OperationType type = OperationType.UPDATE;
			List<String> list = new ArrayList<>(5);
			list.add(sheetId);
			list.add(cell);
			list.add(rawValue);
			list.add(userId);
			list.add(password);
			List<String> urisSec = repManager.getReplicasURI();
			long v = repManager.getVersion();
			Operation op = new Operation(type, list, ++v);
			synchronized (this) {
				Spreadsheet sheet = null;
				sheet = spreadsheets.get(sheetId);
				if (sheet == null) {
					throw new WebApplicationException(Status.NOT_FOUND);
				}
				String userAux = String.format("%s@%s", userId, domain);
				if (!(sheet.getSharedWith().contains(userAux) || sheet.getOwner().equals(userId))) {
					throw new WebApplicationException(Status.BAD_REQUEST);
				}
				Executer exec = new Executer();
				exec.run(urisSec, op);
				while (exec.getNow().plusMillis(TIME_TOLERANCE).isAfter(Instant.now())) {
					if (exec.getExecuted()) {
						sheet.setCellRawValue(cell, rawValue);
						twSheets.put(sheetId, Instant.now());
						repManager.addOperation(op);
					}
				}
			}
		} else
			throw new WebApplicationException(Response.temporaryRedirect(UriBuilder.fromUri(primaryURI).path(sheetId)
					.path(cell).queryParam("userId", userId).queryParam("password", password).build()).build());
	}

	@Override
	public void shareSpreadsheet(String sheetId, String userId, String password) {
		//try{
		if (primary) {
			String[] str = userId.split("@");
			int response = 0;
			/* System.out.println("antes getUser"); */
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
			String owner = sheetId.split("_")[0];
			/* System.out.println("depois getUser"); */
			try {
				response = getUser(domain, owner, password);
				if (response != Status.OK.getStatusCode()) {
					throw new WebApplicationException(Status.fromStatusCode(response));
				}
			} catch (UsersException e1) {
				throw new WebApplicationException(Status.BAD_REQUEST);
			}

			boolean executed = false;
			OperationType type = OperationType.SHARE;
			List<String> list = new ArrayList<>(2);
			list.add(sheetId);
			list.add(userId);
			List<String> urisSec = repManager.getReplicasURI();
			long v = repManager.getVersion();
			Operation op = new Operation(type, list, ++v);
			synchronized (this) {
				Spreadsheet sheet = spreadsheets.get(sheetId);
				if (sheet == null) {
					throw new WebApplicationException(Status.NOT_FOUND);
				}
				Set<String> set = sheet.getSharedWith();
				if (set.contains(userId)) {
					throw new WebApplicationException(Status.CONFLICT);
				}
				Executer exec = new Executer();
				exec.run(urisSec, op);
				while (exec.getNow().plusMillis(TIME_TOLERANCE).isAfter(Instant.now())) {
					if (exec.getExecuted()) {
						set.add(userId);
						sheet.setSharedWith(set);
						twSheets.put(sheetId, Instant.now());
						repManager.addOperation(op);
					}
				}
			}
		} else
			throw new WebApplicationException(Response.temporaryRedirect(UriBuilder.fromUri(primaryURI).path(sheetId)
					.path("share").path(userId).queryParam("password", password).build()).build());
		/* }
		catch(Exception e) {
			System.err.println(e.getMessage());
			e.getStackTrace();
			
		} */
	}

	@Override
	public void unshareSpreadsheet(String sheetId, String userId, String password) {
		if (primary) {
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
			String owner = sheetId.split("_")[0];
			try {
				response = getUser(domain, owner, password);
				if (response != Status.OK.getStatusCode()) {
					throw new WebApplicationException(Status.fromStatusCode(response));
				}
			} catch (UsersException e1) {
				throw new WebApplicationException(Status.BAD_REQUEST);
			}

			boolean executed = false;
			OperationType type = OperationType.UNSHARE;
			List<String> list = new ArrayList<>(2);
			list.add(sheetId);
			list.add(userId);
			List<String> urisSec = repManager.getReplicasURI();
			long v = repManager.getVersion();
			Operation op = new Operation(type, list, ++v);
			synchronized (this) {
				Spreadsheet sheet = spreadsheets.get(sheetId);
				if (sheet == null) {
					throw new WebApplicationException(Status.NOT_FOUND);
				}

				Set<String> set = sheet.getSharedWith();
				if (!set.contains(userId)) {
					throw new WebApplicationException(Status.NOT_FOUND);
				}
				Executer exec = new Executer();
				exec.run(urisSec, op);
				while (exec.getNow().plusMillis(TIME_TOLERANCE).isAfter(Instant.now())) {
					if (exec.getExecuted()) {
						set.remove(userId);
						twSheets.put(sheetId, Instant.now());
						repManager.addOperation(op);
					}
				}
			}
		} else
			throw new WebApplicationException(Response.temporaryRedirect(UriBuilder.fromUri(primaryURI).path(sheetId)
					.path("share").path(userId).queryParam("password", password).build()).build());
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
		DiscoveryURI[] uris = null;
		while ((uris = discovery.knownUrisOf(domain, "users")) == null) {
			try {
				Thread.sleep(500);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if (uris[0].getURI().contains("soap")) {
			SoapUsers users = null;
			short retries = 0;
			boolean success = false;
			while (!success && retries < MAX_RETRIES) {
				try {
					while (users == null) {
						QName QNAME = new QName(SoapUsers.NAMESPACE, SoapUsers.NAME);
						Service service = Service.create(new URL(uris[0].getURI() + UsersWS.USERS_WSDL), QNAME);
						users = service.getPort(SoapUsers.class);
						success = true;
					}
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
				} catch (Exception e) {
					e.getStackTrace();
					throw new WebApplicationException(Status.GONE);
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
				} catch (Exception e) {
					e.getStackTrace();
					throw new WebApplicationException(Status.EXPECTATION_FAILED);
				}
			}
		} else {

			WebTarget target = client.target(uris[0].getURI()).path(RestUsers.PATH);

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
	public SPRange getSpreadsheetValuesRange(String sheetId, String range, String userId, String password,
			String timestamp) {
		Instant tw = twSheets.get(sheetId);
		if (tw == null) {
			throw new WebApplicationException(Status.NOT_FOUND);
		}
		if (!timestamp.equals("")) {
			Instant twReceived = Instant.parse(timestamp);
			if (twReceived.equals(tw)) {
				SPRange spr = new SPRange();
				spr.setTimestamp(twReceived.toString());
				return spr;
			}
		}

		if (!password.equals(passwordServers)) {
			throw new WebApplicationException(Status.FORBIDDEN);
		}
		String[][] values;
		if (userId == null || sheetId == null) {
			throw new WebApplicationException(Status.BAD_REQUEST);
		}

		Spreadsheet sheet;
		synchronized (this) {
			sheet = spreadsheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND);
			}
			String aux = userId.split("@")[0];
			if (!(sheet.getSharedWith().contains(userId) || sheet.getOwner().equals(aux))) {
				throw new WebApplicationException(Status.FORBIDDEN);
			}
		}
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
		SPRange res = new SPRange(tw.toString(), sheet.getSharedWith(), values);
		return res;
	}

	protected void putValuesInCache(String sheetURL, String range, SPRange val) {
		synchronized (this) {
			Map<String, CacheEntry> aux = sheetsValuesCache.get(sheetURL);
			if (aux == null) {
				aux = new HashMap<>();
				sheetsValuesCache.put(sheetURL, aux);
			}
			CacheEntry entry = aux.get(range);
			if (entry == null) {
				entry = new CacheEntry(val);
				aux.put(range, entry);
			}
			if (!val.getTimestamp().equals(entry.getTw().toString()))
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

		Map<String, CacheEntry> mapaux = sheetsValuesCache.get(sheetURL);
		CacheEntry ce = null;
		String tc = "";
		if (mapaux != null) {
			ce = mapaux.get(range);

			if (ce != null) {
				tc = ce.getTc().toString();
				//Duration timeBetween = Duration.between(Instant.now(), ce.getTc());
				//if(timeBetween.getSeconds() < VALUES_CACHE_EXPIRATION) {

				if (!Instant.now().isAfter(ce.getTc().plusSeconds(VALUES_CACHE_EXPIRATION))) {
					return ce.getValues();
				}
			}
		}

		if (sheetURL.contains("rest")) {

			WebTarget target = client.target(sheetURL).path("range");
			short retries = 0;
			while (retries < MAX_RETRIES) {
				try {
					String userAux = String.format("%s@%s", sheet.getOwner(), domain);
					//String sheetId, String range, String userId, String password, String timestamp
					Response r = target.queryParam("range", range).queryParam("userId", userAux)
							.queryParam("password", passwordServers).queryParam("timestamp", tc).request()
							.accept(MediaType.APPLICATION_JSON).get();
					if (r.getStatus() == Status.OK.getStatusCode() && r.hasEntity()) {
						//VAI GSON VAI GSON VAI GSON
						SPRange val = json.fromJson(r.readEntity(String.class), SPRange.class);
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
						String[][] values = spsheets.getSpreadsheetValuesRange(str[1], range, userAux, passwordServers);
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
				}
			}
		}
		return getValuesInCache(sheetURL, range);
	}

	protected String[][] getValuesInCache(String sheetURL, String range) {
		synchronized (this) {
			Map<String, CacheEntry> aux = sheetsValuesCache.get(sheetURL);
			if (aux == null)
				return null;
			return aux.get(range).getValues();
		}
	}

	@Override
	public void deleteUser(String userId, String password) {
		if (primary) {
			if (!password.equals(passwordServers)) {
				throw new WebApplicationException(Status.FORBIDDEN);
			}
			boolean executed = false;
			OperationType type = OperationType.DELETEUSER;
			List<String> list = new ArrayList<>(2);
			list.add(userId);
			list.add(password);
			List<String> urisSec = repManager.getReplicasURI();
			long v = repManager.getVersion();
			Operation op = new Operation(type, list, ++v);
			Executer exec = new Executer();
			exec.run(urisSec, op);
			while (exec.getNow().plusMillis(TIME_TOLERANCE).isAfter(Instant.now())) {
				if (exec.getExecuted()) {
					synchronized (this) {
						Set<String> sheetsUser = usersSpreadsheets.remove(userId);
						if (sheetsUser != null) {
							for (String spId : sheetsUser) {
								spreadsheets.remove(spId);
								twSheets.remove(spId);
							}
						}
					}
					repManager.addOperation(op);
				}
			}
		} else
			throw new WebApplicationException(Response.temporaryRedirect(
					UriBuilder.fromUri(primaryURI).path("user").path(userId).queryParam("password", password).build())
					.build());
	}

	/* @Override
	public void createSpreadsheetRep(UriInfo uriInfo, Long version, String id, Spreadsheet sheet, String password,
			String serverSecret) {
		isPrimary(uriInfo);
		if (!serverSecret.equals(passwordServers))
			throw new WebApplicationException(Status.FORBIDDEN);
		List<String> list = new ArrayList<>(6);
		list.add(json.toJson(uriInfo));
		list.add(version.toString());
		list.add(id);
		list.add(json.toJson(sheet));
		list.add(password);
		list.add(serverSecret);
		Operation op = new Operation(OperationType.CREATE, list, version);
		checkVersion(op);
		String owner = sheet.getOwner();
		String spreadID = id;
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
	}
	
	private void checkVersion(Operation op) {
		if (repManager.getVersion() != op.getVersion() - 1) {
			executeOps(rClientRep.getVersionOperationsRep(primaryURI.toString(), op.getVersion(), passwordServers));
		}
		repManager.addOperation(op);
	}
	
	@Override
	public void deleteSpreadsheetRep(UriInfo uriInfo, Long version, String sheetId, String password,
			String serverSecret) {
		isPrimary(uriInfo);
		if (!serverSecret.equals(passwordServers))
			throw new WebApplicationException(Status.FORBIDDEN);
		List<String> list = new ArrayList<>(5);
		list.add(json.toJson(uriInfo));
		list.add(version.toString());
		list.add(sheetId);
		list.add(password);
		list.add(serverSecret);
		Operation op = new Operation(OperationType.DELETE, list, version);
		checkVersion(op);
		String owner = sheetId.split("_")[0];
	
		Spreadsheet sheet = null;
		synchronized (this) {
			/* sheet = spreadsheets.get(sheetId);
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
	public void updateCellRep(UriInfo uriInfo, Long version, String sheetId, String cell, String rawValue,
			String userId, String password, String serverSecret) {
		isPrimary(uriInfo);
		if (!serverSecret.equals(passwordServers))
			throw new WebApplicationException(Status.FORBIDDEN);
		List<String> list = new ArrayList<>(8);
		list.add(json.toJson(uriInfo));
		list.add(version.toString());
		list.add(sheetId);
		list.add(cell);
		list.add(rawValue);
		list.add(userId);
		list.add(password);
		list.add(serverSecret);
		Operation op = new Operation(OperationType.UPDATE, list, version);
		checkVersion(op);
		Spreadsheet sheet = null;
		synchronized (this) {
			sheet = spreadsheets.get(sheetId);
			/* if (sheet == null) {
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
	
	//URIInfo serve para ver se e o primario que ta a fazer a operacao
	@Override
	public void shareSpreadsheetRep(UriInfo uriInfo, Long version, String sheetId, String userId, String password,
			String serverSecret) {
		isPrimary(uriInfo);
		if (!serverSecret.equals(passwordServers))
			throw new WebApplicationException(Status.FORBIDDEN);
		//checkVersion(version);
		synchronized (this) {
			/* Spreadsheet sheet = spreadsheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND);
			} */

	//Set<String> set = sheet.getSharedWith();
	/* if (set.contains(userId)) {
		throw new WebApplicationException(Status.CONFLICT);
	}*/
	/* set.add(userId);
	sheet.setSharedWith(set);
	twSheets.put(sheetId, Instant.now()); 
	}
	}
	/*
	@Override
	public void unshareSpreadsheetRep(UriInfo uriInfo, Long version, String sheetId, String userId, String password,
	String serverSecret) {
	isPrimary(uriInfo);
	if (!serverSecret.equals(passwordServers))
	throw new WebApplicationException(Status.FORBIDDEN);
	//checkVersion(version);
	synchronized (this) {
	Spreadsheet sheet = spreadsheets.get(sheetId);
	/*if (sheet == null) {
		throw new WebApplicationException(Status.NOT_FOUND);
	}
	
	Set<String> set = sheet.getSharedWith();
	/*if (!set.contains(userId)) {
		throw new WebApplicationException(Status.NOT_FOUND);
	}
	set.remove(userId);
	twSheets.put(sheetId, Instant.now());
	}
	}
	/*
	@Override
	public void deleteUserRep(UriInfo uriInfo, Long version, String userId, String password) {
	isPrimary(uriInfo);
	if (!password.equals(passwordServers)) {
	throw new WebApplicationException(Status.FORBIDDEN);
	}
	//checkVersion(version);
	synchronized (this) {
	Set<String> sheetsUser = usersSpreadsheets.remove(userId);
	if (sheetsUser != null) {
		for (String spId : sheetsUser) {
			spreadsheets.remove(spId);
			twSheets.remove(spId);
		}
	}
	}
	} */

	@Override
	public void executeOperation(UriInfo uriInfo, Operation op, String serverSecret) {
		//isPrimary(uriInfo);

		if (!serverSecret.equals(passwordServers))
			throw new WebApplicationException(Status.FORBIDDEN);

		//checkVersion(op);
		executeOp(op);
	}

	private void executeOp(Operation op) {
		switch (op.getType()) {
			case CREATE:
				create(op.getArguments());
				break;
			case DELETE:
				delete(op.getArguments());
				break;
			case UPDATE:
				update(op.getArguments());
				break;
			case SHARE:
				share(op.getArguments());
				break;
			case UNSHARE:
				unshare(op.getArguments());
				break;
			case DELETEUSER:
				deleteUserSpreadsheets(op.getArguments());
			default:
		}
		repManager.addOperation(op);
	}

	private void deleteUserSpreadsheets(List<String> arguments) {
		String userId = arguments.get(0);
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

	private void create(List<String> arguments) {
		Spreadsheet sheet = json.fromJson(arguments.get(0), Spreadsheet.class);
		String owner = sheet.getOwner();
		String spreadID = sheet.getSheetId();
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
	}

	private void delete(List<String> arguments) {
		String id = arguments.get(0);
		String owner = id.split("_")[0];
		Spreadsheet sheet = null;
		synchronized (this) {
			sheet = spreadsheets.get(id);
			Set<String> aux = usersSpreadsheets.get(owner);
			if (aux != null)
				aux.remove(id);
			spreadsheets.remove(id);
			twSheets.remove(id);
		}
	}

	private void update(List<String> arguments) {
		String sheetId = arguments.get(0);
		String cell = arguments.get(1);
		String rawValue = arguments.get(2);
		String userId = arguments.get(3);
		String password = arguments.get(4);
		Spreadsheet sheet = null;
		synchronized (this) {
			sheet = spreadsheets.get(sheetId);
			sheet.setCellRawValue(cell, rawValue);
			twSheets.put(sheetId, Instant.now());
		}
	}

	private void share(List<String> arguments) {
		String sheetId = arguments.get(0);
		String userId = arguments.get(1);
		Spreadsheet sheet = null;
		synchronized (this) {
			sheet = spreadsheets.get(sheetId);
			Set<String> set = sheet.getSharedWith();
			set.add(userId);
			sheet.setSharedWith(set);
			twSheets.put(sheetId, Instant.now());
		}
	}

	private void unshare(List<String> arguments) {
		String sheetId = arguments.get(0);
		String userId = arguments.get(1);
		Spreadsheet sheet = null;
		synchronized (this) {
			sheet = spreadsheets.get(sheetId);
			Set<String> set = sheet.getSharedWith();
			set.remove(userId);
			twSheets.put(sheetId, Instant.now());
		}
	}

	private void checkVersion(Operation op) {
		if (repManager.getVersion() != op.getVersion() - 1) {
			executeOps(rClientRep.getVersionOperationsRep(primaryURI.toString(), op.getVersion(), passwordServers));
		}
		repManager.addOperation(op);
	}

	@Override
	public long getVersionRep(String serverSecret) {
		if (!serverSecret.equals(passwordServers)) {
			throw new WebApplicationException(Status.FORBIDDEN);
		}
		return repManager.getVersion();
	}

	@Override
	public SortedSet<Operation> getVersionOperationsRep(long newVersion, String serverSecret) {
		if (!serverSecret.equals(passwordServers)) {
			throw new WebApplicationException(Status.FORBIDDEN);
		}
		return repManager.getOpsStartingIn(newVersion);
	}

	private void isPrimary(UriInfo uriInfo) {
		System.out.println("primary: " + primaryURI.toString());
		System.out.println("AbsolutePath" + uriInfo.getAbsolutePath().toString());
		System.out.println("BseUri :" + uriInfo.getBaseUri().toString());
		System.out.println("RequestUri :" + uriInfo.getRequestUri().toString());
		System.out.println("getPath :" + uriInfo.getPath());
		if (!uriInfo.getBaseUri().equals(primaryURI))
			throw new WebApplicationException(Status.TOO_MANY_REQUESTS);
	}

}