package tp1.server.rest.resources;

import com.google.gson.Gson;
import com.sun.xml.ws.client.BindingProviderProperties;
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
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import tp1.Discovery;
import tp1.api.Spreadsheet;
import tp1.api.engine.AbstractSpreadsheet;
import tp1.api.service.rest.RestSpreadsheets;
import tp1.api.service.rest.RestUsers;
import tp1.api.service.soap.SheetsException;
import tp1.api.service.soap.SoapSpreadsheets;
import tp1.api.service.soap.SoapUsers;
import tp1.api.service.soap.UsersException;
import tp1.dropbox.CreateDirectory;
import tp1.dropbox.DeleteDropbox;
import tp1.dropbox.Download;
import tp1.dropbox.UploadDropbox;
import tp1.dropbox.arguments.DeleteV2Args;
import tp1.impl.engine.SpreadsheetEngineImpl;
import tp1.server.soap.ws.SpreadsheetsWS;
import tp1.server.soap.ws.UsersWS;
import tp1.util.CellRange;

import javax.xml.namespace.QName;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class SpreadsheetResourceOAuth implements RestSpreadsheets {

    public final static int MAX_RETRIES = 3;
    public final static int RETRY_PERIOD = 1000;
    public final static int CONNECTION_TIMEOUT = 10000;
    public final static int REPLY_TIMEOUT = 600;
    public final static String passwordServers = "serversidepsswd";
    private static final String OVERWRITE = "overwrite";
    private int counter = 0;
    //private final Map<String, Spreadsheet> spreadsheets = new HashMap<>();
    private static Logger Log = Logger.getLogger(SpreadsheetResourceOAuth.class.getName());
    Discovery discovery;
    private String domain;
    private String serverURI;
    private Map<String, Map<String, String[][]>> sheetsValuesCache = new HashMap<>();
    private Client client;
    //private Map<String, Set<String>> usersSpreadsheets = new HashMap<>();
    private CreateDirectory createDirectory;
    private DeleteDropbox deleteDropbox;
    private Download download;
    private UploadDropbox uploadDropbox;
    private Gson json;

    public SpreadsheetResourceOAuth() {
    }

    public SpreadsheetResourceOAuth(String domain, boolean clean, String serverURI, Discovery discover) {
        this.domain = domain;
        this.serverURI = serverURI;
        this.discovery = discover;
        ClientConfig config = new ClientConfig();
        config.property(ClientProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
        config.property(ClientProperties.READ_TIMEOUT, REPLY_TIMEOUT);
        client = ClientBuilder.newClient(config);
       
        createDirectory = new CreateDirectory();
        deleteDropbox = new DeleteDropbox();
        download = new Download();
        uploadDropbox = new UploadDropbox();
        json = new Gson();
        if (clean) {
            cleanDropbox();
        }
        createDirectory.execute("/"+domain);
    }

    private void cleanDropbox() {
        // fazer delete dos ficheiros deste domain na dropbox
        // TODO
        // criar folder novo para este domain
        deleteDropbox.execute("/"+domain);
    }

    @Override
    public String createSpreadsheet(Spreadsheet sheet, String password) {
        // dropbox usar writemode add e autorename false
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
        int num = counter++;
        spreadID = String.format("%s-%d", owner, num);
        sheet.setSheetId(spreadID);
        String url = String.format("%s/spreadsheets/%s", serverURI, spreadID);
        sheet.setSheetURL(url);
        Set<String> sw = sheet.getSharedWith();
        if (sw == null) {
            sw = new HashSet<>();
            sheet.setSharedWith(sw);
        }
        
            /*
             * Set<String> aux = usersSpreadsheets.get(owner); 
             * if (aux == null) { aux = new
             * HashSet<String>(); } aux.add(spreadID); usersSpreadsheets.put(owner, aux);
             * spreadsheets.put(spreadID, sheet);
             */
            String path = String.format("/%s/%s/%s", domain, owner, num);
            String jsonSheet = json.toJson(sheet);
            uploadDropbox.execute(path, OVERWRITE, false, false, false, jsonSheet.getBytes());
        
        return spreadID;
    }

    @Override
    public void deleteSpreadsheet(String sheetId, String password) {
        String[] splitted = sheetId.split("-");
        String owner = splitted[0];
        String num = splitted[1];

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
       
            /*
             * sheet = spreadsheets.get(sheetId); if (sheet == null) { throw new
             * WebApplicationException(Status.NOT_FOUND); } Set<String> aux =
             * usersSpreadsheets.get(owner); if (aux != null) aux.remove(sheetId);
             * spreadsheets.remove(sheetId);
             */
            String path = String.format("/%s/%s/%s", domain, owner, num);
            if (!deleteDropbox.execute(path))
                throw new WebApplicationException(Status.NOT_FOUND);
        
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
        
            /*
             * sheet = spreadsheets.get(sheetId); if (sheet == null) { throw new
             * WebApplicationException(Status.NOT_FOUND); } String userAux =
             * String.format("%s@%s", userId, domain); if
             * (!(sheet.getSharedWith().contains(userAux) ||
             * sheet.getOwner().equals(userId))) { throw new
             * WebApplicationException(Status.FORBIDDEN); } return sheet;
             */
            String[] splitted = sheetId.split("-");
            if(splitted.length != 2)
                throw new WebApplicationException(Status.NOT_FOUND);
            String owner = splitted[0];
            String num = splitted[1];
            String path = String.format("/%s/%s/%s", domain, owner, num);
            String sheetJson = download.execute(path);
            sheet = json.fromJson(sheetJson, Spreadsheet.class);
            if (sheet == null)
                throw new WebApplicationException(Status.NOT_FOUND);
            String userAux = String.format("%s@%s", userId, domain);
            if (!(sheet.getSharedWith().contains(userAux) || sheet.getOwner().equals(userId))) {
                throw new WebApplicationException(Status.FORBIDDEN);
            }
            return sheet;
        
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
        
            /*sheet = spreadsheets.get(sheetId);
            if (sheet == null) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
            String userAux = String.format("%s@%s", userId, domain);
            if (!(sheet.getSharedWith().contains(userAux) || sheet.getOwner().equals(userId))) {
                throw new WebApplicationException(Status.FORBIDDEN);
            }*/
            String[] splitted = sheetId.split("-");
            String owner = splitted[0];
            String num = splitted[1];
            String path = String.format("/%s/%s/%s", domain, owner, num);
            String sheetJson = download.execute(path);
            sheet = json.fromJson(sheetJson, Spreadsheet.class);
            if (sheet == null)
                throw new WebApplicationException(Status.NOT_FOUND);
            String userAux = String.format("%s@%s", userId, domain);
            if (!(sheet.getSharedWith().contains(userAux) || sheet.getOwner().equals(userId))) {
                throw new WebApplicationException(Status.FORBIDDEN);
            
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
        
            /*sheet = spreadsheets.get(sheetId);
            if (sheet == null) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
            String userAux = String.format("%s@%s", userId, domain);
            if (!(sheet.getSharedWith().contains(userAux) || sheet.getOwner().equals(userId))) {
                throw new WebApplicationException(Status.BAD_REQUEST);
            }
            sheet.setCellRawValue(cell, rawValue);*/
            String[] splitted = sheetId.split("-");
            String owner = splitted[0];
            String num = splitted[1];
            String path = String.format("/%s/%s/%s", domain, owner, num);
            String sheetJson = download.execute(path);
            sheet = json.fromJson(sheetJson, Spreadsheet.class);
            if (sheet == null)
                throw new WebApplicationException(Status.NOT_FOUND);
            String userAux = String.format("%s@%s", userId, domain);
            if (!(sheet.getSharedWith().contains(userAux) || sheet.getOwner().equals(userId))) {
                throw new WebApplicationException(Status.FORBIDDEN);
            }
            sheet.setCellRawValue(cell, rawValue);
            sheetJson = json.toJson(sheet);
            uploadDropbox.execute(path, OVERWRITE, false, false, false, sheetJson.getBytes());
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
        String[] splitted = sheetId.split("-");
        String owner = splitted[0];
        String num = splitted[1];
        try {
            response = getUser(domain, owner, password);
            if (response != Status.OK.getStatusCode()) {
                throw new WebApplicationException(Status.fromStatusCode(response));
            }
        } catch (UsersException e1) {
            throw new WebApplicationException(Status.BAD_REQUEST);
        }

        
            /*Spreadsheet sheet = spreadsheets.get(sheetId);
            if (sheet == null) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }

            Set<String> set = sheet.getSharedWith();
            if (set.contains(userId)) {
                throw new WebApplicationException(Status.CONFLICT);
            }
            set.add(userId);
            sheet.setSharedWith(set);*/
            
            String path = String.format("/%s/%s/%s", domain, owner, num);
            String sheetJson = download.execute(path);
            Spreadsheet sheet = json.fromJson(sheetJson, Spreadsheet.class);
            if (sheet == null)
                throw new WebApplicationException(Status.NOT_FOUND);
            String userAux = String.format("%s@%s", userId, domain);
            Set<String> set = sheet.getSharedWith();
            if (set.contains(userAux)) {
                throw new WebApplicationException(Status.CONFLICT);
            }
            set.add(userId);
            //sheet.setSharedWith(set);
            sheetJson = json.toJson(sheet);
            uploadDropbox.execute(path, OVERWRITE, false, false, false, sheetJson.getBytes());
        
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
        String[] splitted = sheetId.split("-");
        String owner = splitted[0];
        String num = splitted[1];
        try {
            response = getUser(domain, owner, password);
            if (response != Status.OK.getStatusCode()) {
                throw new WebApplicationException(Status.fromStatusCode(response));
            }
        } catch (UsersException e1) {
            throw new WebApplicationException(Status.BAD_REQUEST);
        }

        
            /*Spreadsheet sheet = spreadsheets.get(sheetId);
            if (sheet == null) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }

            Set<String> set = sheet.getSharedWith();
            if (!set.contains(userId)) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
            set.remove(userId);*/
            String path = String.format("/%s/%s/%s", domain, owner, num);
            String sheetJson = download.execute(path);
            Spreadsheet sheet = json.fromJson(sheetJson, Spreadsheet.class);
            if (sheet == null)
                throw new WebApplicationException(Status.NOT_FOUND);
            Set<String> set = sheet.getSharedWith();
            if (!set.contains(userId)) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
            set.remove(userId);
            //sheet.setSharedWith(set);
            sheetJson = json.toJson(sheet);
            uploadDropbox.execute(path, OVERWRITE, false, false, false, sheetJson.getBytes());
        
    }

    /**
     * Sends a getUser request to a users server
     *
     * @param userId   - user to be acquired
     * @param domain   - user's domain
     * @param password - user's password
     * @return if the server we are trying to connect is REST this return has the
     *         status code of the response if the server we are trying to connect is
     *         SOAP this return is 200
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
    public String[][] getSpreadsheetValuesRange(String sheetId, String range, String userId) {
        String[][] values;
        if (userId == null || sheetId == null) {
            throw new WebApplicationException(Status.BAD_REQUEST);
        }

        Spreadsheet sheet;
       
            /*sheet = spreadsheets.get(sheetId);
            if (sheet == null) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
            String aux = userId.split("@")[0];
            if (!(sheet.getSharedWith().contains(userId) || sheet.getOwner().equals(aux))) {
                throw new WebApplicationException(Status.FORBIDDEN);
            }*/
            String[] splitted = sheetId.split("-");
            String owner = splitted[0];
            String num = splitted[1];
            String path = String.format("/%s/%s/%s", domain, owner, num);
            String sheetJson = download.execute(path);
            sheet = json.fromJson(sheetJson, Spreadsheet.class);
            if (sheet == null) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
            String aux = userId.split("@")[0];
            if (!(sheet.getSharedWith().contains(userId) || sheet.getOwner().equals(aux))) {
                throw new WebApplicationException(Status.FORBIDDEN);
            
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

        return values;
    }

    protected void putValuesInCache(String sheetURL, String range, String[][] values) {
        synchronized(this) {
            Map<String, String[][]> aux = sheetsValuesCache.get(sheetURL);
            if (aux == null)
                aux = new HashMap<>();
            aux.put(range, values);
            sheetsValuesCache.put(sheetURL, aux);
        }
    }

    /**
     * Sends a getSpreadsheetValuesRange request to a spreadsheets server
     *
     * @param sheetURL- sheet's url from where the values will be taken
     * @param range     - range of the values to be taken
     * @param sheet     - original sheet
     * @return the values of the sheet in the specified sheetURL within a specified
     * @throws UsersException if the server we are trying to connect is SOAP and the
     *                        request answered with an exception
     */
    private String[][] getRangeValuesRequest(String sheetURL, String range, Spreadsheet sheet) throws SheetsException {
        if (sheetURL.contains("rest")) {

            WebTarget target = client.target(sheetURL).path("range");

            short retries = 0;
            while (retries < MAX_RETRIES) {
                try {
                    String userAux = String.format("%s@%s", sheet.getOwner(), domain);
                    Response r = target.queryParam("range", range).queryParam("userId", userAux).request()
                            .accept(MediaType.APPLICATION_JSON).get();
                    String[][] val = r.readEntity(String[][].class);
                    putValuesInCache(sheetURL, range, val);
                    return val;
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

            SoapSpreadsheets spsheets = null;
            short retries = 0;
            boolean success = false;
            String[] str = sheetURL.split("/spreadsheets/");
            while (!success && retries < MAX_RETRIES) {
                try {
                    QName QNAME = new QName(SoapSpreadsheets.NAMESPACE, SoapSpreadsheets.NAME);
                    Service service = Service.create(new URL(str[0] + SpreadsheetsWS.SPREADSHEETS_WSDL), QNAME);
                    spsheets = service.getPort(SoapSpreadsheets.class);
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
                    putValuesInCache(sheetURL, range, values);
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

        }
        return getValuesInCache(sheetURL, range);
    }

    protected String[][] getValuesInCache(String sheetURL, String range) {
        synchronized (this) {
            Map<String, String[][]> aux = sheetsValuesCache.get(sheetURL);
            return aux.get(range);
        }
    }

    @Override
    public void deleteUser(String userId, String password) {
        if (!password.equals(passwordServers)) {
            throw new WebApplicationException(Status.FORBIDDEN);
        }

        
            /*Set<String> sheetsUser = usersSpreadsheets.remove(userId);
            if (sheetsUser != null) {
                for (String spId : sheetsUser) {
                    spreadsheets.remove(spId);
                }
            }*/
            String path = String.format("/%s/%s", domain, userId);
            deleteDropbox.execute(path);
        
    }
}