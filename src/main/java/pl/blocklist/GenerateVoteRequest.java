package pl.blocklist;

public class GenerateVoteRequest {

    private final String ip;

    public GenerateVoteRequest(String ip) {
        this.ip = ip;
    }

    public String getIp() {
        return this.ip;
    }
}
