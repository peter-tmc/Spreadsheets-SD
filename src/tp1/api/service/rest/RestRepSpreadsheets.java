package tp1.api.service.rest;

import java.util.SortedSet;

import javax.print.attribute.standard.Media;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import tp1.api.Spreadsheet;
import tp1.server.replication.utils.Operation;

@Path(RestSpreadsheets.PATH)
public interface RestRepSpreadsheets extends RestSpreadsheets {
    //TODO comentar as interfaces

	//URIInfo serve para saber se e o primario que ta a fazer as operacoes
    /* @POST
    @Consumes(MediaType.APPLICATION_JSON)
	@Path("/rep/{sheetId}")
    void createSpreadsheetRep(@Context UriInfo uriInfo, @HeaderParam(RestSpreadsheets.HEADER_VERSION) Long version, @PathParam("sheetId") String id, Spreadsheet sheet, 
	@QueryParam("password") String password, @QueryParam("serverSecret") String serverSecret);

    @DELETE
	@Path("/rep/{sheetId}")
    void deleteSpreadsheetRep(@Context UriInfo uriInfo, @HeaderParam(RestSpreadsheets.HEADER_VERSION) Long version, @PathParam("sheetId") String sheetId, @QueryParam("password") String password, @QueryParam("serverSecret") String serverSecret);

    @PUT
	@Path("/rep/{sheetId}/{cell}")
	@Consumes(MediaType.APPLICATION_JSON)
	void updateCellRep(@Context UriInfo uriInfo, @HeaderParam(RestSpreadsheets.HEADER_VERSION) Long version, @PathParam("sheetId") String sheetId, @PathParam("cell") String cell, String rawValue, 
			@QueryParam("userId") String userId, @QueryParam("password") String password, @QueryParam("serverSecret") String serverSecret);

    @POST
	@Path("/rep/{sheetId}/share/{userId}")
	void shareSpreadsheetRep(@Context UriInfo uriInfo, @HeaderParam(RestSpreadsheets.HEADER_VERSION) Long version, @PathParam("sheetId") String sheetId, @PathParam("userId") String userId, 
			@QueryParam("password") String password, @QueryParam("serverSecret") String serverSecret);

    @DELETE
	@Path("/rep/{sheetId}/share/{userId}")
	void unshareSpreadsheetRep(@Context UriInfo uriInfo, @HeaderParam(RestSpreadsheets.HEADER_VERSION) Long version, @PathParam("sheetId") String sheetId, @PathParam("userId") String userId, 
			@QueryParam("password") String password, @QueryParam("serverSecret") String serverSecret);

    @DELETE
	@Path("/rep/user/{userId}")
	void deleteUserRep(@Context UriInfo uriInfo, @HeaderParam(RestSpreadsheets.HEADER_VERSION) Long version, @PathParam("userId") String userId, @QueryParam("password") String password);
 */
	@POST
	@Path("/execute")
	@Consumes(MediaType.APPLICATION_JSON)
	void executeOperation(@Context UriInfo uriInfo, Operation op, @QueryParam("password") String serverSecret);

	@GET
	@Path("/rep")
	@Produces(MediaType.APPLICATION_JSON)
	long getVersionRep(@QueryParam("password") String serverSecret);

	@GET
	@Path("/rep/ops")
	@Produces(MediaType.APPLICATION_JSON)
	SortedSet<Operation> getVersionOperationsRep(@QueryParam("version") long version, @QueryParam("password") String serverSecret);
}
