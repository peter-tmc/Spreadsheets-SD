package tp1.api.service.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import tp1.api.Spreadsheet;

public interface RestRepSpreadsheets extends RestSpreadsheets {
    
    //TODO comentar as interfaces

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    void createSpreadsheetRep(String id, Spreadsheet sheet, @QueryParam("password") String password, String serverSecret);

    @DELETE
	@Path("/{sheetId}")
    void deleteSpreadsheetRep(@PathParam("sheetId") String sheetId, @QueryParam("password") String password, String serverSecret);

    @PUT
	@Path("/{sheetId}/{cell}")
	@Consumes(MediaType.APPLICATION_JSON)
	void updateCellRep( @PathParam("sheetId") String sheetId, @PathParam("cell") String cell, String rawValue, 
			@QueryParam("userId") String userId, @QueryParam("password") String password, String serverSecret);

    @POST
	@Path("/{sheetId}/share/{userId}")
	void shareSpreadsheetRep( @PathParam("sheetId") String sheetId, @PathParam("userId") String userId, 
			@QueryParam("password") String password, String serverSecret);

    @DELETE
	@Path("/{sheetId}/share/{userId}")
	void unshareSpreadsheetRep( @PathParam("sheetId") String sheetId, @PathParam("userId") String userId, 
			@QueryParam("password") String password, String serverSecret);

    @DELETE
	@Path("/user/{userId}")
	void deleteUserRep(@PathParam("userId") String userId, @QueryParam("password") String password);
}
