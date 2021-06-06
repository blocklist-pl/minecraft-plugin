package pl.blocklist;

public class GenerateVoteResponse {

    private final String url;

    private final String code;

    public GenerateVoteResponse(String url, String code) {
        this.url = url;
        this.code = code;
    }

    public String getUrl() {
        return this.url;
    }

    public String getCode() {
        return this.code;
    }
}
