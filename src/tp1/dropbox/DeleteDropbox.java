package tp1.dropbox;

import java.io.IOException;

import org.pac4j.scribe.builder.api.DropboxApi20;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.gson.Gson;

import tp1.dropbox.arguments.DeleteV2Args;
public class DeleteDropbox {
    private static final String apiKey = "yszklpvd11evujy";
	private static final String apiSecret = "putbwd4j4rklmq9";
	private static final String accessTokenStr = "Qj1kumo2dUQAAAAAAAAAAeTMezCIsloWxyc4MzdumyLCQVD7j8ZWXnSDIc3tVLd8";	
	private static final String DELETE_V2_URL = "https://api.dropboxapi.com/2/files/delete_v2";
    protected static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
		
	private OAuth20Service service;
	private OAuth2AccessToken accessToken;
	
	private Gson json;

    public DeleteDropbox() {
        service = new ServiceBuilder(apiKey).apiSecret(apiSecret).build(DropboxApi20.INSTANCE);
		accessToken = new OAuth2AccessToken(accessTokenStr);
		json = new Gson();
    }

    public boolean execute(String path) {
        OAuthRequest delete = new OAuthRequest(Verb.POST, DELETE_V2_URL);
        delete.addHeader("Content-Type", JSON_CONTENT_TYPE);
        delete.setPayload(json.toJson(new DeleteV2Args(path)));
        service.signRequest(accessToken, delete);

        Response r = null;

        try {
            r = service.execute(delete);
        }
        catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		
		if(r.getCode() == 200) {
			return true;
		} else {
			System.err.println("HTTP Error Code: " + r.getCode() + ": " + r.getMessage());
			try {
				System.err.println(r.getBody());
			} catch (IOException e) {
				System.err.println("No body in the response");
			}
			return false;
		}
    }
}
