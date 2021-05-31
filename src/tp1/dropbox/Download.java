package tp1.dropbox;

import java.io.IOException;
import org.pac4j.scribe.builder.api.DropboxApi20;

import tp1.dropbox.arguments.DownloadArgs;
import tp1.dropbox.arguments.UploadArgs;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.gson.Gson;

public class Download {
    private static final String apiKey = "yszklpvd11evujy";
	private static final String apiSecret = "putbwd4j4rklmq9";
	private static final String accessTokenStr = "Qj1kumo2dUQAAAAAAAAAAeTMezCIsloWxyc4MzdumyLCQVD7j8ZWXnSDIc3tVLd8";	
	private static final String DOWNLOAD_V2_URL = "https://content.dropboxapi.com/2/files/download";
    protected static final String CONTENT_TYPE = "application/octet-stream";
		
	private OAuth20Service service;
	private OAuth2AccessToken accessToken;
	
	private Gson json;

    public Download(){
        service = new ServiceBuilder(apiKey).apiSecret(apiSecret).build(DropboxApi20.INSTANCE);
		accessToken = new OAuth2AccessToken(accessTokenStr);
		
		json = new Gson();
    }

    public String execute(String path) {
        OAuthRequest download = new OAuthRequest(Verb.POST, DOWNLOAD_V2_URL);
        download.addHeader("Dropbox-API-Arg",json.toJson(new DownloadArgs(path)));
        download.addHeader("Content-Type", CONTENT_TYPE);
        service.signRequest(accessToken, download);

        Response r = null;

        try {
            r = service.execute(download);
            if(r.getCode() == 200)
                return r.getBody();
            else return null;
        }
        catch (Exception e) {
			e.printStackTrace();
			return null;
		}
    }
}
