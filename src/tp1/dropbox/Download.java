package tp1.dropbox;

import org.pac4j.scribe.builder.api.DropboxApi20;

import jakarta.ws.rs.ProcessingException;
import tp1.dropbox.arguments.DownloadArgs;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.gson.Gson;

public class Download {
   
	private static final String DOWNLOAD_V2_URL = "https://content.dropboxapi.com/2/files/download";
    protected static final String CONTENT_TYPE = "application/octet-stream";
    private static final long RETRY_PERIOD = 7000;
		
	private OAuth20Service service;
	private OAuth2AccessToken accessToken;
	private String accessTokenStr;
	private Gson json;

    public Download(String apiKey, String apiSecret, String acessTokenStr){
        this.accessTokenStr=acessTokenStr;
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
        int retries = 0;
        while(retries < 3)
        try {
            r = service.execute(download);
            /* System.out.println(r.getCode());
            System.out.println(r.getBody()); */
            if(r.getCode() == 200)
                return r.getBody();
            if(r.getCode() == 429) {
                try {
                    System.out.println("download :" + r.getCode());
                    Thread.sleep(RETRY_PERIOD);
                } catch (InterruptedException e) {
    
                }
                continue;
            }
            throw new Exception(r.toString());
        }
        catch(ProcessingException pe) {
            try {
                Thread.sleep(RETRY_PERIOD);
            } catch (InterruptedException e) {

            }
        }
        catch (Exception e) {
			e.printStackTrace();
			return null;
		}
        return null;
    }
}
