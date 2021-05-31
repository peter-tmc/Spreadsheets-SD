package tp1.dropbox;

import java.io.IOException;
import org.pac4j.scribe.builder.api.DropboxApi20;

import tp1.dropbox.arguments.UploadArgs;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.gson.Gson;

public class UploadDropbox {
    private static final String apiKey = "yszklpvd11evujy";
	private static final String apiSecret = "putbwd4j4rklmq9";
	private static final String accessTokenStr = "Qj1kumo2dUQAAAAAAAAAAeTMezCIsloWxyc4MzdumyLCQVD7j8ZWXnSDIc3tVLd8";	
	private static final String UPLOAD_V2_URL = "https://content.dropboxapi.com/2/files/upload";
    protected static final String CONTENT_TYPE = "application/octet-stream";
		
	private OAuth20Service service;
	private OAuth2AccessToken accessToken;
	
	private Gson json;

    public UploadDropbox(){
        service = new ServiceBuilder(apiKey).apiSecret(apiSecret).build(DropboxApi20.INSTANCE);
		accessToken = new OAuth2AccessToken(accessTokenStr);
		
		json = new Gson();
    }

    public boolean execute(String path, String mode, boolean autorename, boolean mute, boolean strict_conflict, byte[] file) {
        OAuthRequest upload = new OAuthRequest(Verb.POST, UPLOAD_V2_URL);
        upload.addHeader("Content-Type", CONTENT_TYPE);
        upload.addHeader("Dropbox-API-Arg",json.toJson(new UploadArgs(path, mode, autorename, mute, strict_conflict)));
        upload.setPayload(file);
        service.signRequest(accessToken, upload);

        Response r = null;

        try {
            r = service.execute(upload);
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
