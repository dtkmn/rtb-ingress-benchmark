package demo.adtech;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BidRequest {
    public String id;
    public Site site;
    public App app;
    public Device device;
    public User user;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Site {
        public String id;
        public String domain;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class App {
        public String id;
        public String bundle;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Device {
        public String ip;
        public String os;
        public String ua;
        public int lmt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class User {
        public String id;
    }
}
